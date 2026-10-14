# moderation（MediaWiki Moderation 扩展移植）

审核：没有豁免权的用户提交的**页面编辑 / 创建、文件上传与文件删除**不会立即生效，
而是进入审核队列；由**核查员 / 管理员**在 `特殊:审核`（`Special:Moderation`）里通过或拒绝。
通过后由内核以**原提交人**身份应用变更（页面版本 / 文件写入），拒绝则原样丢弃。

| 入口 | 说明 |
| --- | --- |
| `特殊:审核`（`Special:Moderation`） | 待审 / 已通过 / 已拒绝队列：`?status=pending\|approved\|rejected`、`?type=edit\|upload\|filedelete`、`?title=标题片段` 过滤 + 状态页签计数；编辑显示「当前版本 → 待审内容」的行级差异，上传可预览（图片直接显示 / 其它给下载链接），每行有「通过 / 拒绝」+ 审核意见输入框 |
| `/api/moderation/approve`、`/api/moderation/reject` | 通过 / 拒绝（`id` + `comment`），只有核查员 / 管理员可用 |
| `/api/moderation/pending-file/{id}` | 待审上传文件的预览（只有审核员能取；`no-store`） |
| `/plugins/moderation/moderation.js`、`moderation.css` | 队列页交互与样式（站点级注入，由 `HtmlHeadProvider` 提供） |

## 拦截入口（全部走平台钩子）

| 动作 | 钩子 | 说明 |
| --- | --- | --- |
| 编辑 / 创建页面 | `EditGuard` + `EditContext` | 网页编辑表单与 `api.php?action=edit` 都走；把提交写进队列并返回 `HANDLED(redirect=/wiki/标题?moderation=pending)`，core 不再保存 |
| 上传文件 | `ActionGuard`（`action=upload`，载荷 `ActionContext.payload()`） | 文件内容**暂存**到 `{run-path}/pending/`（不是拒绝），返回 `HANDLED` + `pending=true` / `moderated=true`（前端字段与迁移前一致） |
| 删除文件 | `ActionGuard`（`action=delete`） | 删除请求进队列，core 不执行删除 |
| 编辑页提示 | `EditFormField.notice` | 没有豁免权的用户在保存按钮附近看到「保存后更改将进入审核队列」 |
| 页面提示条 | `PageDisplayHook.beforeContent` | 「此页面有 N 条待审更改」+ 查看队列链接、保存后的「已提交，等待审核」提示 |
| 「待审创建」页 | `PageDisplayHook.renderMissingPage` | 页面**还不存在**且有待审创建时，提交人 / 审核员看到待审内容预览，其他人照常 404 |

**AI 代理的提交不经审核**（`EditContext.source() == ai` 直接放行）：迁移前 core 只在网页 / API 路径检查审核，
AI 提案由用户自己核对后保存；队列里历史遗留的 `aiAssisted` 队列项在通过后仍会保留 AI 水印。

## 权限语义

- **审核人**：「核查员」「管理员」——查看队列、通过 / 拒绝；
- **豁免组**（提交直接生效）：「免审查用户」「页面管理员」「小部件管理员」「小部件编辑者」；
- 组名常量定义在 `ModerationSupport`（与 core 的用户组名一致；用户组本身仍由 core 的
  `特殊:用户权限` / 权限配置维护，core 侧 `PermissionService` 的常量未改动）；
- 审核人自己也要审核（与迁移前一致）；匿名用户不排队（登录要求由 core 的访问控制负责）。

## 开关与数据

- **开关**：`features.moderation`（**默认关闭**；`/init`、`特殊:权限配置 → 站点设置`、`LocalSettings.yaml` 都可改，
  core 只保留开关与说明）；开启时才注入前端样式 / 脚本，关闭时队列页显示「未启用」提示、接口拒绝审核动作、
  编辑 / 上传 / 删除直接生效；
- **数据**：表 `wiki_pending_change` 与实体 `WikiPendingChange` **属于插件自己**（实体在插件内，
  core 按 `ModerationPlugin.schemaEntities()` 自动建表 / 补列，插件经 `common.service.PluginDatabase`
  裸 SQL 读写，见 `service/ModerationStoreJdbc`）；待审上传的文件内容暂存在插件数据目录下的
  `pending/`（`service/PendingFileStoreImpl`，即 `data/plugins/moderation/pending/`）；
- **写入**：通过待审编辑走 `common.service.PageWriter`（可带乐观锁 / AI 水印），通过待审上传 / 删除走
  `common.service.FileWriter`（core 的 `FileService` 仍是唯一写入方，插件不直接碰磁盘存储）；
  首次上传的说明页（`文件:X`）由插件经 `PageWriter` 创建；
- **日志**：通过 / 拒绝写通用日志（类型 `moderation` / `moderationreject`，由插件的 `LogType` 注册，
  `特殊:日志` 过滤器与最近更改照常显示）；详情 `变更类型|审核意见` 的展示文案由
  `LogType.detailText` 在插件里本地化（键名 `log.moderation.change.*` / `log.moderation.comment` 不变）；
- **插件未加载时**：普通用户的编辑 / 上传 / 删除**立即生效**（没有队列）、`特殊:审核` 与 `/api/moderation/*`
  不存在、页面没有待审提示条、编辑页没有「进入审核队列」提示。

## 与 MediaWiki Moderation 的差异

- **待审文件暂存目录变了**：从 core 的 `{run-path}/pending/` 改为插件的
  `data/plugins/moderation/pending/`（core 不再认识「审核暂存」）；升级前遗留在旧目录的
  待审上传内容不会自动搬过来，队列里的历史待审上传需要重新提交一次；
- **没有「待审移动」**：页面移动（`move`）不进队列、也没有 move 审核（迁移前的 core 同样不审核移动）；
- **没有通知**：通过 / 拒绝不会给提交人发通知（MediaWiki 版会在用户讨论页/通知里提示）；
- **没有「合并到已有页面」**：MediaWiki Moderation 允许审核员把待审创建合并进同名页面；
  这里只有「通过 / 拒绝」两种结果；
- **没有「待审编辑也要过过滤器」的二次评估**：待审内容在**提交时**经过 `EditGuard` 链（滥用过滤器等），
  通过时不再重新评估（移动端审核时页面可能已被别人改动，此时以 `PageWriter` 直接写入；
  迁移前的 core 行为相同——审核通过不复查过滤器 / 版本）；
- **队列不显示 ip / user-agent**：迁移前的 core 队列页就没有这两列，这里沿用；
- **豁免组名单不同**：MediaWiki Moderation 的 `automoderated` / `moderator` / `automoderated` 组在此对应
  「免审查用户」；此外本 wiki 的「页面管理员」「小部件管理员」「小部件编辑者」也直接豁免；
- **队列页差异**：状态页签是「全部 / 待审 / 已通过 / 已拒绝」，`?type=`、`?title=` 过滤保留；
  上传预览只有审核员能取（`/api/moderation/pending-file/{id}` 校验权限）；
- **通过后的版本**：以原提交人身份生成（与 MediaWiki 版一致），`special:log` 里记的是审核人的动作
  （类型「审核」）；
- **依赖的 core 差异**：`ActionGuard` 新增了 `HANDLED`（钩子自行处理，core 当作成功返回）；
  插件**未加载**时 core 的行为与迁移前完全一致（编辑 / 上传 / 删除立即生效，没有队列）。

## 测试

`plugin/moderation/src/test`（不启动 Spring）：

- `ModerationStoreJdbcTest`（H2 内存库）：插件自有表 `wiki_pending_change` 的真实 SQL 回归——
  入队 / 查回 / 状态流转、只更新非 null 字段、状态 / 类型 / 标题过滤与 limit、计数、上传的
  文件字段与布尔标记；
- `PendingFileStoreImplTest`：待审文件暂存的落盘行为——暂存 / 读取 / 删除、扩展名白名单、
  路径穿越与空名拒绝（目录用临时目录，生产是 `data/plugins/moderation/pending/`）；
- `ModerationServiceTest`（11）：审核人 / 豁免组判断、编辑入队与通过（原提交人身份 + 小编辑 / AI 标记保留）、
  拒绝、提交人缺失时退回审核人、应用失败时保持待审、上传暂存 / 通过（含说明页）/ 拒绝 / 暂存丢失、
  文件删除通过、队列过滤与计数、标题与字段兜底、开关关闭时不拦截；
- `ModerationHookTest`（10）：编辑入队（`HANDLED` + 跳转）、新建页没有 pageId、豁免组 / 匿名 / 开关关闭放行、
  AI 提交放行 + AI 标记入队、上传暂存（`pending` / `moderated`）、无载荷放行、删除入队、其它动作忽略、
  编辑页提示、提示条可见性、待审创建页；
- `ModerationPluginTest`（9）：扩展点注册、开关日志、特殊页权限 / 未启用 / 过滤与页签计数 / 行模型、
  前端资源注入、文案键名、日志类型详情文案。

> **功能开关**：`features.moderation`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
