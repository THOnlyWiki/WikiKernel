# social-profile（用户交互移植：头像上传 + 用户间私信）

原 core 内置能力的插件化：**用户页头像上传**（仿 MediaWiki SocialProfile 的资料卡）与
**用户间私信**（`特殊:私信` / `Special:Messages`）。开关沿用 `features.social-profile`（默认开启）。

## 能力

| 能力 | 位置 | 说明 |
| --- | --- | --- |
| 头像上传 API | `POST /api/user/avatar`（multipart，`file`） | 仅本人（登录后）；功能关闭 403 / 未登录 401 / 被封禁 403 / 账号不存在 404 / 校验失败 400 |
| 头像文件服务 | `GET /avatars/<文件名>` | 只读 `{run-path}/avatars/`（`AvatarStore.resolve` 带文件名白名单与路径穿越防护），按扩展名给 Content-Type，缓存 1 天 |
| 上传头像页 | `特殊:上传头像` / `Special:UploadAvatar` | 插件 `SpecialPage` + 插件模板 `templates/special/upload-avatar.html`；匿名提示登录，功能关闭显示「站点已关闭」提示 |
| 私信页 | `特殊:私信` / `Special:Messages` | `?box=inbox\|sent\|conversations`、`?with=用户名`、`?write=1&to=用户名`、`?id=123`（自动标已读） |
| 私信 API | `POST /api/messages/send`、`POST /api/messages/delete` | 发送需要登录、收件人存在、不能发给自己；删除只删自己一侧（两侧都删则物理删除） |
| 资料卡 | `UserProfileHook` | 头像（上传过就是 `/avatars/…`）+ 用户名下方「贡献 \| 私信 \| 上传头像」（上传头像仅本人可见） |
| 前端资源 | `/plugins/social-profile/…` | `avatar.js`、`messages.js`、`social-profile-base.css`（原在 core 的 `/js/` 与 `base.css`），由 `HtmlHeadProvider`（`siteWide`）注入 |

## 规则（与迁移前 core 完全一致）

- 头像只接受 PNG / JPEG / GIF / WebP，大小上限 2 MB（且不超过 `wikikernel.max-file-size-mb`）；
  文件名 `u{userId}-{时间戳}.{ext}`，替换时删除旧文件；校验失败抛出的消息键
  （`avatar.empty` / `avatar.notImage` / `avatar.tooLarge` / `avatar.uploadFailed`）由插件本地化后返回；
- 私信：收件人不存在 → `messages.userNotFound`；发给自己 → `messages.selfNotAllowed`；
  阅读（`?id=`）与打开会话（`?with=`）自动把对方发来的未读标已读；
  删除是自己一侧的软删除（`fromDeleted` / `toDeleted`），两侧都删才真的删记录；
- `features.social-profile=false` 时：资料卡整体隐藏、顶栏没有私信入口、两个特殊页显示
  「站点已关闭『用户交互』」提示、两个接口 403（脚本 / 样式也不再注入）。

## 扩展点

| 扩展点 | 用途 |
| --- | --- |
| `SpecialPage` | `特殊:上传头像`、`特殊:私信`（插件自带模板与文案） |
| `UserProfileHook` | 用户页资料卡的头像地址与「贡献 \| …」额外链接 |
| `HtmlHeadProvider`（`siteWide()`） | 注入插件 CSS / JS、`WIKI_CONFIG.userInteraction = true` 与 `WIKI_MESSAGES` 文案 |
| `MessageBundle` | `avatar.*` / `messages.*` / `special.messages` / `special.uploadAvatar` / `tools.sendMessage`（键名与迁移前 core 一致） |
| `@RestController` / `@Controller` | 头像上传、私信 API、`/avatars/<文件名>` 文件服务 |
| `common.service.AvatarStore` | 头像校验 / 落盘 / 更新 `wiki_user.avatar` / 删除旧文件（core 提供的数据桥） |
| `common.service.MessageStore` | `wiki_message` 表读写（`received` / `sent` / `all` / `conversation` / `unreadCount`） |
| `common.service.UserStore` | 按 ID / 用户名查用户、批量取用户名与头像 |
| `common.service.AccessCheck` | 封禁提示（`blockNotice`）与访问规则判断 |
| `common.service.CurrentUser` | 当前请求用户（上传 / 发信 / 顶栏计数） |
| `common.service.WikiText` | 私信正文按 wikitext 渲染（与页面正文同一套渲染器） |
| `common.service.SiteConfig` | 读 `features.social-profile`（与 core 的站点设置同一个键） |

## 与 MediaWiki 原版的差异

- **没有 SocialProfile 的社交功能**：只有用户页资料卡的头像与快捷链接，
  没有留言板（UserBoard）、好友（Friends）、礼物（Gifts）、用户等级 / 积分（UserLevels / UserStats）、
  用户页 DIY 版式（UserPage 皮肤）等；资料卡也不是独立页面，而是用户页顶部的一块（core 的
  `fragments/user-profile.html` 渲染，插件只填头像与链接）；
- **私信不是邮件**：是站内 `wiki_message` 表里的私信，不发送任何电子邮件，也没有
  MediaWiki 的 `EmailUser` / `Echo`（通知）集成——没有通知徽章、没有「新消息」提示（Echo）、
  没有 `Special:Notifications`；「已读」是收件人打开即标记（没有 MediaWiki 的
  「发送邮件副本」选项）；
- **头像尺寸**：只限制最大尺寸（资料卡 180×180、上传页 240×240 显示），按原图比例缩放，
  不做 MediaWiki `$wgUploadAvatar` 的裁剪 / 缩略图多尺寸（没有生成 65×65 等头像副本）；
  头像文件不做版本历史（替换即删除旧文件），也没有头像审核 / 删除日志；
- **未读徽标**：私信未读数只在「特殊:私信」页面上显示（顶栏旧的「私信（N）」入口已随 Echo 移植移除，
  通知统一走 Echo 插件的「提醒 / 一般通知」铃铛）；没有 MediaWiki 的跨设备未读同步；
- **时间显示**：私信时间按 `format.dateTime` 消息键格式化（服务器时区）；
  core 的显示时区换算（`time.WikiTime` + 用户时区设置）只在 core 内部生效，
  插件拿不到这个 ThreadLocal，所以私信时间不受「参数设置 → 时区」影响（其余页面照常）；
- **头像上传页**：`特殊:上传头像` 只是本插件的简化上传页（选择图片即上传），
  没有 MediaWiki 上传页的版权协议选择、文件名冲突处理、`$wgUploadNavigationUrl` 跳转；
  也没有 `Special:UploadAvatar` 之外的 `Special:RemoveAvatar`（删除头像要重新上传覆盖）。

## 相关

- 迁移前的 core 实现：`AvatarService(+Impl)`、`MessageService(+Impl)`、`AvatarController`、
  `UserAvatarApiController`、`MessageApiController`、`UploadAvatarSpecialPage`、`MessagesSpecialPage`
  （已全部删除）；
- 数据仍在 `wiki_user.avatar` 与 `wiki_message` 表，无需迁移数据；
- 文案键名不变，`LocalSettings.yaml` / `/init` / 站点设置里的 `features.social-profile` 语义不变。

> **功能开关**：`features.social-profile`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
