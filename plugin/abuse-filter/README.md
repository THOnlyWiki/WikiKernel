# abuse-filter（MediaWiki AbuseFilter 扩展移植）

滥用过滤器：用**条件表达式**匹配编辑 / 移动 / 注册 / 上传等动作的变量（页面标题、正文、用户、IP、
站点统计…），命中后执行「禁止（disallow）」「警告（warn）」「标记（tag）」「封禁（block）」
「移除自动确认（blockautopromote）」等动作；命中写**滥用日志**（含变量快照），
管理员在 `特殊:滥用过滤器` 里维护过滤器（含历史恢复与测试），在 `特殊:滥用日志` 里查看命中记录。

| 入口 | 说明 |
| --- | --- |
| `特殊:滥用过滤器`（`Special:AbuseFilter`） | 过滤器列表 / 新建 / 编辑（CodeMirror 条件编辑器）/ 历史与恢复 / **测试**（用表达式匹配最近编辑）；只有管理员 / 行政员 |
| `特殊:滥用日志`（`Special:AbuseLog`） | 命中日志：时间 / 过滤器 / 用户 / 页面 / 结果；详情含变量快照；隐藏条目只对管理员 / 行政员可见 |
| `/api/abuse-filter/*` | 保存 / 恢复 / 测试 / 隐藏日志的接口（插件自己的 `@RestController`） |
| `/plugins/abuse-filter/abuse-filter.js`、`abuse-filter.css` | 管理界面脚本与样式（站点级注入，由 `HtmlHeadProvider` 提供） |

## 拦截入口（全部走平台钩子）

| 动作 | 钩子 | 说明 |
| --- | --- | --- |
| 编辑 / 创建页面 | `EditGuard` | 网页编辑表单与 `api.php?action=edit` 都评估（与迁移前一致）；命中禁止 → 拒绝保存并显示原因，命中警告 → 保存后提示 |
| 移动页面 | `ActionGuard`（`action=move`） | `/api/pages/move` |
| 上传 / 删除文件 | `ActionGuard`（`action=upload` / `delete`） | 文件接口 |
| 封禁 | `ActionGuard`（`action=block`） | 封禁接口 |
| 注册账号 | `RegistrationGuard` | 创建账号前评估（`action=createaccount`） |

## 开关与数据

- **开关**：`features.abuse-filter`（**默认关闭**；`/init`、`特殊:权限配置 → 站点设置`、`LocalSettings.yaml` 都可改）；
  开启时插件用 `common.service.UserWriter` 确保存在系统账号「滥用过滤器」（「机器人」组，用于过滤器封禁）；
- **数据**：`wiki_abuse_filter`（过滤器）/ `wiki_abuse_filter_revision`（修订）/ `wiki_abuse_log`（命中日志）
  三张表**属于插件自己**：实体在 `plugin/abuse-filter/.../data/`（`@TableName` + common 的 `@DbColumn`），
  由插件在 `WikiPlugin.schemaEntities()` 里声明，core 在 `onStart` 前按与 core 相同的 Auto-DDL 规则建表 / 补列
  （SQLite / MySQL / H2，只增不减；已有库的表结构完全兼容）；
  数据访问走 `common.service.PluginDatabase`（JDBC 桥，见 `service/AbuseFilterStoreJdbc`），
  「测试过滤器」的最近编辑 / 编辑者概况来自通用的 `common.service.RecentChangesStore`——**common 里不再有
  滥用过滤器专属的实体或桥接口**；
- **插件未加载时**：不评估任何过滤器、不写滥用日志（编辑 / 移动 / 注册照常），两个特殊页与
  `/api/abuse-filter/*` 都不存在；core 只保留站点设置开关与文档。

## 与 MediaWiki AbuseFilter 的差异

- 条件语法实现了常用子集（变量、字符串 / 数字 / 布尔运算、比较、`in` / `contains` / `matches`（正则）、
  `+ - * / %`、括号、`!` / `&` / `|` / `^`、`if … then … else … end`、以及
  `变量 := 表达式;` 赋值与分号分隔的多条语句等），**没有** 100% 覆盖原版所有函数与关键字；
- 动作实现了 `disallow` / `warn` / `tag` / `block` / `blockautopromote` / `throttle` 里项目实际支持的几种
  （以插件代码为准），没有 `degroup`、`rangeblock` 等；
- 条件求值没有原版的「运行时 / 语法 / 复杂度」三级限制与 `af_conditions_limit`，
  但求值器是自写的单遍扫描、不会回溯爆炸；如需硬性上限请提 issue；
- 没有原版的「过滤器组（group）」「全局过滤器」「导出 / 导入 JSON」与 `Special:AbuseFilter/examine`；
- 管理界面的 CodeMirror 语法高亮沿用 core 内置的 CodeMirror（不走 CDN）。

> **功能开关**：`features.abuse-filter`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
