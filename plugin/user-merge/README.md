# user-merge（MediaWiki UserMerge 扩展移植）

**核查员专用**：把源账号的数据（编辑历史 / 日志 / 权限 / 文件）归入目标账号，可选删除源账号；
写入 `usermerge` 类型日志。用户页（`用户:源`）**不会被自动移动**。

| 入口 | 说明 |
| --- | --- |
| `特殊:用户合并`（`Special:UserMerge`） | 合并表单（源用户 / 目标用户 / 是否删除源账号；`?oldUser=` / `?newUser=` 可预填） |
| `POST /api/user-merge` | 执行合并（`oldUser` / `newUser` / `deleteOld` 可选），只有「核查员」组可用 |
| `/plugins/user-merge/user-merge.js` | 表单交互（提交 + 结果显示在页面消息框，站点级注入） |

## 权限语义

- **「核查员」组**（`GROUP_CHECKUSER`）才能合并；匿名 / 无权限用户 401 / 403；
- 用户页「工具」侧栏的「用户合并」入口（`tools.userMerge`）仍由 core 提供。

## 分工与数据

- **core**：只保留「核查员」用户组、`common.service.UserMergeStore` 桥与
  `UserMergeServiceImpl`（事务内跨表把编辑历史 / 日志 / 权限 / 文件从源账号归入目标账号、
  可选删除源账号、作废源账号登录 / 邮箱令牌、写 `usermerge` 日志）；
- **插件**：特殊页、API 控制器、前端脚本与 `userMerge.*` / `special.userMerge` /
  `log.typeName.usermerge` / `log.entry.usermerge` / `log.usermerge.*` 文案；
- **日志**：`usermerge` 类型由插件用 `common.extension.LogType` 注册，详情
  `merge|<源名>|<deleted|kept>` 由 `LogType.detailText` 本地化（`特殊:日志` 过滤器与最近更改照常显示）；
- **插件未加载时**：`特殊:用户合并` 与 `/api/user-merge` 不存在（404）、core 不写 `usermerge` 日志。

## 与 MediaWiki UserMerge 的差异

- **用户页不自动移动**（与 MediaWiki UserMerge 一致，迁移前后都不移）；
- **源账号可选删除**：`deleteOld` 勾选才删除；不勾选保留空账号（源账号的登录 / 邮箱令牌仍会作废）；
- **没有「重命名到新账号」模式**：MediaWiki UserMerge 允许把合并目标设为一个尚不存在的新账号并顺带改名；
  这里目标必须是已存在账号（与迁移前 core 行为一致）；
- **日志只记源账号名 + 是否删除**（详情键 `log.usermerge.source` / `log.usermerge.deleted` / `log.usermerge.kept`），
  与迁移前 core 一致。

## 测试

`plugin/user-merge/src/test`（8 个用例，不启动 Spring）：扩展点注册、消息键存在性
（含 `plugin.user-merge.name/.description`）、接口权限（401 / 403）与结果映射（成功 / 找不到源 /
找不到目标 / 相同用户）与参数传递、特殊页模型、日志类型详情文案与前端资源注入。
注意：插件测试**不真合并用户**——桥替身只记录请求并返回可配置结果。

core 侧「插件未加载」语义由 `core/src/test/.../UserMergeTest`（2 个用例）覆盖。