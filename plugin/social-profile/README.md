# social-profile（社交资料、用户关系与互动）

原 core 内置能力的插件化：**用户页头像上传**（仿 MediaWiki SocialProfile 的资料卡）与
**用户间私信**（`特殊:私信` / `Special:Messages`）。开关沿用 `features.social-profile`（默认开启）。

## 能力

| 能力 | 位置 | 说明 |
| --- | --- | --- |
| 头像上传 API | `POST /api/user/avatar`（multipart，`file`） | 仅本人（登录后）；功能关闭 403 / 未登录 401 / 被封禁 403 / 账号不存在 404 / 校验失败 400 |
| 头像文件服务 | `GET /avatars/<文件名>` | 只读 `{run-path}/avatars/`（`AvatarStore.resolve` 带文件名白名单与路径穿越防护），按扩展名给 Content-Type，缓存 1 天 |
| 上传头像页 | `特殊:上传头像` / `Special:UploadAvatar` | 插件 `SpecialPage` + 插件模板 `templates/special/upload-avatar.html`；匿名提示登录，功能关闭显示「站点已关闭」提示 |
| 私信页 | `特殊:私信` / `Special:Messages` | `?box=inbox\|sent\|conversations`、`?with=用户名`、`?write=1&to=用户名`、`?id=123`（自动标已读） |
| 礼物管理 | `特殊:GiftManager` / `Special:GiftManager` | 登录用户可创建自定义礼物；创建者 / 管理员可停用或重新启用；内置礼物不可改 |
| 礼物页 | `特殊:礼物` / `特殊:送礼物` | 内置 + 自定义礼物目录，送礼物 / 收礼 / 删除；自定义礼物存 `wiki_gift` |
| 私信 API | `POST /api/messages/send`、`POST /api/messages/delete` | 发送需要登录、收件人存在、不能发给自己；删除只删自己一侧（两侧都删则物理删除） |
| 资料卡 | `UserProfileHook` | 头像（上传过就是 `/avatars/…`）+ 用户名下方「贡献 \| 私信 \| 上传头像」（上传头像仅本人可见） |
| 前端资源 | `/plugins/social-profile/…` | `avatar.js`、`messages.js`、`social-profile-base.css`（原在 core 的 `/js/` 与 `base.css`），由 `HtmlHeadProvider`（`siteWide`）注入 |

## 规则（与迁移前 core 完全一致）

- 头像只接受 PNG / JPEG / GIF / WebP，大小上限 2 MB（且不超过 `wikikernel.max-file-size-mb`）；
  文件名 `u{userId}-{时间戳}.{ext}`，替换时删除旧文件；未上传时使用 core 的共享默认头像
  `site.default-avatar`（Timeless 的「默认头像」写同一个键，本插件不依赖 Timeless）；校验失败抛出的消息键
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
| `ExternalAuthAvatarBridge` | 第三方（GitHub）首次注册后的头像导入；`features.social-profile=false` 时不落盘 |
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

## 原版兼容与本次补齐

头像解析函数、最新 / 随机用户头像列表、删除头像入口及资料页布局的对照结果，见 [UPSTREAM-COMPATIBILITY.md](UPSTREAM-COMPATIBILITY.md)。

- `{{#avatar:用户名|m}}`（s/m/ml/l 或像素大小）；
- `<newusers count="10" row="5"/>`、`<randomuserswithavatars count="10" row="4" size="ml"/>`，也支持相应 `#` 函数写法；
- `特殊:删除头像` / `Special:RemoveAvatar`，`POST /api/user/avatar/remove`（可选 `user` 参数）；本人可删除自己的头像，删除他人头像需要该特殊页的 protect 权限；
- 桌面沿用原版资料页两栏，窄正文变为纵排；头像、留言区修正 box-sizing 和长文本换行。

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
