# rename-user（MediaWiki Renameuser 扩展移植）

重命名用户账号（`wiki_user.username`），可**同时移动** `用户:旧名` / `用户讨论:旧名` 及其子页面
（不留重定向）；写入 `renameuser` 类型日志。**需要「重命名用户」权限组**（默认无人拥有，管理员可在
`特殊:用户权限` 授予；仿 Renameuser 扩展的 `renameuser` 权限）。

| 入口 | 说明 |
| --- | --- |
| `特殊:重命名用户`（`Special:RenameUser`） | 改名表单（原用户名 / 新用户名 / 原因 / 是否移动用户页）+「最近的重命名」列表（`renameuser` 日志） |
| `POST /api/user/rename` | 执行改名（`oldName` / `newName` / `reason` 可空 / `movePages` 可选），只有「重命名用户」组可用 |
| `/plugins/rename-user/rename-user.js` | 表单交互（提交前按模板文案确认、成功后跳到新用户页，站点级注入） |

## 权限语义

- **「重命名用户」组**（`GROUP_RENAME_USER`）才能改名；匿名 / 无权限用户 401 / 403；
- 管理员**不会自动获得**该组（需要显式授予，与 MediaWiki 的 `renameuser` 权限一致）；
- 用户名规则与注册一致（禁止 `# < > [ ] | { } /`）；重名 / 同名 / 不存在都有本地化提示。

## 分工与数据

- **core**：只保留「重命名用户」用户组与 `common.service.UserAdminStore` 桥（改名 / 用户名规则校验 /
  可选移动用户页 / 写 `renameuser` 日志，内部调 `UserService.rename`）；
- **插件**：特殊页、API 控制器、前端脚本与 `renameUser.*` / `special.renameUser` /
  `log.typeName.renameuser` / `log.entry.renameuser` / `log.renameuser.*` 文案；
- **日志**：`renameuser` 类型由插件用 `common.extension.LogType` 注册，详情 `rename|<新名>|<原因>`
  由 `LogType.detailText` 本地化（`特殊:日志` 过滤器与最近更改照常显示）；
- **插件未加载时**：`特殊:重命名用户` 与 `/api/user/rename` 不存在（404）、core 不写 `renameuser` 日志。

## 与 MediaWiki Renameuser 的差异

- **动作句目标显示旧用户名**：账号已改名（`wiki_user.username` 已是新名），日志动作句里的目标用
  日志保存的旧用户名（`LogFormatter` 对 `renameuser` 类型特判），与迁移前 core 行为一致；
- **没有日志页专属筛选器**：`特殊:日志?type=renameuser` 照常可用（类型由插件注册），
  与 MediaWiki 的 `Special:Log/rename` 对应；
- **移动用户页不留重定向**（与迁移前 core 一致；MediaWiki 的 `moveUserPages` 默认也不留）。

## 测试

`plugin/rename-user/src/test`（9 个用例，不启动 Spring）：扩展点注册、消息键存在性
（含 `plugin.rename-user.name/.description`）、接口权限（401 / 403）与结果映射（成功 / 不存在 /
已占用 / 同名 / 非法）、特殊页模型、日志类型详情文案与前端资源注入。

core 侧「插件未加载」语义由 `core/src/test/.../RenameUserTest`（2 个用例）覆盖。