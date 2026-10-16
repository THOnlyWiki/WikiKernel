# Echo（通知）插件

仿 MediaWiki **Echo** 扩展的通知中心：**顶栏铃铛（未读红点 / 下拉面板）+ 特殊:通知 + 通知偏好**，
通知触发覆盖「用户讨论页被编辑 / @提及 / 编辑被回退 / 新用户欢迎」，**kawaii-timeless 与 plain / vector / fallback 皮肤通用**。

## 功能一览

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| 铃铛 + 红点 + 下拉面板 | 顶栏个人工具区（`PersonalToolbarHook`） | `#personal-extra`（kawaii）/ `.plain-personal`（plain）/ `#p-personal`（vector / fallback）都是同一个条目；面板显示最近 15 条，可「全部标为已读」 |
| 通知列表页 | `特殊:通知` / `Special:Notifications`（别名 `Notification` / `Echo`） | 按类型过滤、翻页（每页 50）、单条已读、全部已读 |
| 通知偏好 | `特殊:参数设置 → 通知`（`PreferenceSection`） | 每类开关（`echo.pref.enable.<type>`）+ **邮件通知开关**（`echo.pref.email`，默认关 = opt-in），都存在 `UserSettings` |
| 站点开关 | `特殊:权限配置 → 站点设置 → Echo 通知`（`SiteSettingsSection`）+ 功能总开关 `features.echo` | 讨论页 / 提及 / 回退 / 欢迎 通知可分别禁用；邮件另受 `wikikernel.email.*`（总开关 + 类型 `echo-notification`）控制 |
| API | `GET /api/echo/unread-count`、`GET /api/echo/list`、`POST /api/echo/mark-read`、`POST /api/echo/mark-all-read`、`POST /api/echo/preferences` | 需要登录；未登录 401、被封禁 403 |

### 通知类型

| 类型 | 触发 | 收件人 |
| --- | --- | --- |
| `edit-user-talk` | 有人编辑**你的用户讨论页**（含子页面判定：`用户讨论:你/子页` 也归你） | 讨论页主人（自己编辑自己不通知） |
| `mention` | 正文里 `[[用户:你]]` / `[[User:你]]` / `@你` | 被提及的用户（自己提及自己不通知；同一保存内去重） |
| `revert` | 你的编辑被**回退**（`api.php?action=rollback`） | 被回退的编辑者（连续编辑一并算） |
| `welcome` | 新用户注册（注册 2 天内首次访问时补发一次） | 新用户 |

## 触发方式（core 没有「编辑完成」事件）

- **讨论页 / 提及**：扩展点 `EditGuard`（保存前钩子，网页 / API / AI 提交都走）。core 的插件 AOP 桥
  会跳过 `@Transactional` 服务（`WikiPageService` 正是），因此这里**不能**用 AOP；`EditGuard` 在事务之外调用，
  通知写库不会与编辑事务抢 SQLite 写锁。代价：保存被后续钩子 / 乐观锁拦下时可能留一条「幽灵通知」，
  已用「同类同页 60 秒去重」压到最小。
- **回退**：回退**不走** `EditGuard`（core 直接调 `WikiPageService.editPage`），使用 `CoreAopHook`
  切 `ActionApiService.handle`（非事务 Bean，桥接代理可命中）：调用前按参数查「该用户对本页的连续编辑者」，
  调用成功后给这些人发通知。注意：通知写库放在事务**提交之后**（`afterCommit`），
  否则 SQLite 下会与编辑事务的写锁互相等待（挂起）。
- **欢迎**：惰性发放——新用户首次打开铃铛（`/unread-count`）或通知页时补发；只给注册 2 天内的用户，
  老账号不会被补一发。
- **邮件**：由上面三条路径在通知入库后异步触发（见「邮件通知」一节）。

## 踩坑记录（改这个插件前先看）

1. **插件 AOP 桥跳过 `@Transactional` Bean**（`PluginAopBridge#eligible` 里 `AopUtils.isAopProxy`）——
   `WikiPageService` 的实现类正是事务代理，所以「AOP 切 editPage」不会生效（切了也不报错，只是永远不触发）。
   编辑路径必须用 `EditGuard`；只有像 `ActionApiService`（非事务）这样的 Bean 才切得到。
2. **不要在数据库事务里写通知**：`PluginDatabase` 用独立连接，SQLite 下会等外层写事务的锁 →
   请求挂起（表现为「保存转圈不出结果」）。要么用 `EditGuard`（事务之外），要么 `afterCommit`。
3. **SMTP 发送不能在请求线程里同步做**（超时上限 15 秒）：插件用单线程 daemon 执行器异步发。
4. **kawaii-timeless 皮肤会清空 `#personal-extra li a` 里的 `span`、并给「两个 span 的链接」加红点**：
   下拉面板若留在铃铛的 `<li>` 里，内容会被皮肤规则隐藏 / 装饰——面板在 JS 里挂到 `<body>` 下，
   用 `position: fixed` 自己定位。
5. **裸根部署（`urls.page-prefix: ""`）要保留 core 的顶层路由**：`/email/confirm`（邮箱确认）、
   `/password/reset…`（重置密码）、`/init`、`/version`、`/render` 否则会被当成页面标题（404）——
   已修进 `WikiUrlRewriteFilter#RESERVED_SEGMENTS`（core 测试 `WikiUrlRewriteFilterTest` 覆盖）。

## 数据

表 `wiki_notification` 属于插件自己（`EchoPlugin.schemaEntities()` 声明，core 的 Auto-DDL 建表）：

| 列 | 说明 |
| --- | --- |
| `id` | 自增 |
| `recipient_user_id` | 收件人 |
| `type` | `edit-user-talk` / `mention` / `revert` / `welcome` |
| `actor_user_id` | 触发者（欢迎 / 系统通知为 null） |
| `page_title` / `revision_id` | 关联页面（可空） |
| `comment` | 摘要 / 回退摘要 |
| `read` / `read_at` | 已读标记与时间 |
| `create_at` | 创建时间 |
| `need_render` / `context_text` | 提及上下文（保留字段） |

数据访问走 `common.service.PluginDatabase`（JDBC 桥）；用户 / 页面信息走 `common` 的桥
（`UserStore` / `WikiPageStore` / `CurrentUser` / `AccessCheck` / `UserSettings`），不碰 core 的 Mapper。

## 邮件通知

通知入库后**异步**发一封纯文本邮件（单线程 daemon 执行器；插件停止时 `onStop` 关闭，不阻塞保存请求、
不占数据库事务）：

| 条件 | 说明 |
| --- | --- |
| 站点邮件可用 | `wikikernel.email.enabled=true` + SMTP 配置完整，且类型 `echo-notification` 没被 `wikikernel.email.types.*` 关掉（`MailService` 语义） |
| 用户开关 | `echo.pref.email=true`（默认关，opt-in，与 MediaWiki「通过电子邮件通知我」一致） |
| 邮箱要求 | 收件人**邮箱已确认**（未确认不发，与 MediaWiki 一致） |
| 节流 | 同一收件人 + 同一通知类型 **30 分钟**内最多一封（水位存 `echo.pref.email.last.<type>`） |

实现走 **`common.service.Mailer` 桥**（core 的 `MailerImpl` 转调 `MailService`，异常收成 `false`）——
插件不直接依赖 core 的邮件类。邮件里的站点链接用当前请求的 scheme+host（反代 / 多域名场景可用站点设置
`echo.email.baseUrl` 覆盖）。

## 前端资源

`static/plugins/echo/echo.css` + `echo.js`，由 `EchoHeadProvider`（`HtmlHeadProvider`，siteWide）注入，
**不依赖任何皮肤的私有 CSS / JS**：

- 铃铛图标：kawaii 皮肤自带 Material Icons（`#personal-extra .echo-entry .echo-bell::before` 用 `notifications` 连字），
  其余皮肤按普通个人工具链接显示，未读数用数字徽标；
- 下拉面板由 JS **挂到 `<body>` 下**（`position: fixed` + 按铃铛位置计算坐标）：kawaii 皮肤对
  `#personal-extra li a span` 有「隐藏文字 + 未读数红点」规则，面板留在 `li` 里会被清空内容；
- 未读轮询：页面可见时每 60 秒 `GET /api/echo/unread-count`。

## 测试

- `plugin/echo/src/test/java/.../EchoNotifierMentionTest.java`：提及提取（`[[用户:名]]` / `@名` / 去重 / 非用户链接）、
  讨论页标题解析、转义与站内路径。
- 端到端（本机）：注册探针账号 → 讨论页 / 提及编辑 → 回退 → 铃铛红点、面板、通知页、过滤、已读、
  plain 皮肤铃铛（脚本见会话记录，不入库）。
