# fingerprint-login（浏览器数字指纹登录）

用**浏览器指纹**免密码登录：用户可以在多个浏览器上登记指纹（每用户最多 10 个），
登录页出现「用浏览器指纹登录」按钮，点一下就能登录；登记的指纹可以随时在
`特殊:参数设置 → 指纹登录` 或 `特殊:指纹登录` 里查看与删除。

| 部分 | 说明 |
| --- | --- |
| 开关 | `features.fingerprint-login`（**默认关闭**；`特殊:权限配置 → 站点设置` 或 `LocalSettings.yaml` 的 `wikikernel.features` 可开）。core 维护这个键，插件经 `SiteConfig` 读它 |
| 插件配置 | `LocalSettings.yaml → wikikernel.plugins.settings.fingerprint-login.https-only = true`：只允许安全上下文（HTTPS / localhost）使用指纹登录 |
| 数据表 | `wiki_fingerprint`（`id` / `user_id` / `label` / `fingerprint_hash` / `user_agent` / `created_at` / `last_used_at`；实体在插件内，core 按 `FingerprintLoginPlugin.schemaEntities()` 自动建表 / 补列）。`fingerprint_hash` 全局唯一 |
| 数据访问 | 表 / 实体属于插件自己：`service/FingerprintStoreJdbc` 经 `common.service.PluginDatabase` 裸 SQL 读写；会话建立是 `service/LoginSessionImpl`（走 core 的只读用户桥 `UserAdminStore.byId`）；限流仍走 `common.service.RateLimit` |
| 接口 | `POST /api/fingerprint-login`（登录）、`GET /api/fingerprint-login/list`、`POST /api/fingerprint-login/add`、`DELETE /api/fingerprint-login/remove` |
| 前端脚本 | `/plugins/fingerprint-login/fingerprint-login.js`（站点级注入；脚本自己判断当前页是登录页还是管理界面） |
| 管理入口 | 参数设置分区 `PreferenceSection(id = fingerprint-login)` + 特殊页 `特殊:指纹登录`（别名 `Special:FingerprintLogin`），两者共用同一批接口 |
| 文案 | `i18n/messages.properties`（中文）/ `i18n/messages_en.properties`（英文）；漏翻的键直接显示键名 |
| 测试 | `plugin/fingerprint-login/src/test/...`（业务 + 接口 + 装配，以及 H2 上的 `FingerprintStoreJdbcTest` 与不启动 Spring 的 `LoginSessionImplTest`） |

## 安全说明（重要）

**指纹登录是「便利凭据」，比密码弱。** 它把「能打开这台浏览器」当成了身份证明：

- **共享浏览器 = 共享账号**：同一台电脑 / 同一个浏览器配置文件上的任何人都能直接点按钮登录，
  不需要知道密码（和「记住我」的持久登录是同一类风险，但连一次密码输入都不需要）；
- **指纹可以被复制**：UA、屏幕、时区、canvas、WebGL 这些分量都能被脚本伪造或重放；
  攻击者只要拿到一份相同的分量串就能算出同一个摘要（这也是设计上「同一台设备稳定」的代价）；
- **浏览器升级会失配**：换显卡驱动、升级浏览器、改缩放 / 时区都可能改变指纹 → 需要重新登记；
- **不要把它当唯一凭据**：需要强保证的站点请用密码 + 双因素；指纹适合「家里 / 自己的设备上少打一次密码」。

### 已实现的基本防护

| 防护 | 说明 |
| --- | --- |
| 限流 | core 的滑动窗口限流：按「客户端 IP + 指纹摘要」（`wikikernel.rate-limit.fingerprint-login`，默认 5 次失败 / 5 分钟 → 锁 5 分钟）与只按「客户端 IP」（`wikikernel.rate-limit.fingerprint-login-ip`，默认 10 次 / 5 分钟 → 锁 5 分钟，防止换指纹绕过）两级计数；命中成功即清除 |
| 中性错误 | 登录失败一律给同一句提示（`fingerprint.error.badCredentials`），不透露「某个指纹属于哪个用户」，也不区分「没登记」与「登记了但用户已删除」 |
| 隐私 | 管理员**不能**查看别人的指纹列表（没有按用户查询的入口）；指纹摘要从不回显（列表只给名称 / 时间 / UA 摘要） |
| 归属固定 | 指纹摘要全局唯一：已登记在别的账号下的摘要会被拒绝（避免抢注 / 覆盖别人的指纹） |
| 只能删自己的 | 增删都只作用于会话里的 `userId`，接口不接受 `userId` 参数 |
| 可选仅 HTTPS | 插件配置 `fingerprint-login.https-only = true` 时，非安全上下文（http + 非 localhost）直接拒绝；浏览器端在非安全上下文本来就拿不到 `crypto.subtle`，会隐藏按钮并提示 |
| 可随时撤销 | 用户可自助删除指纹（删除后那台浏览器立即失去登录能力）；表里没有长期令牌，不需要「退出所有设备」这类操作 |
| 上限 | 每用户最多 10 个指纹，避免无限堆积 |

## 指纹采集与 hash 方案

**只在浏览器端计算**（服务端不重算、也不保存原始特征），`crypto.subtle.digest('SHA-256')`：

```
v=1
ua=<navigator.userAgent>
lang=<navigator.language>
langs=<navigator.languages 逗号连接>
platform=<navigator.platform>
cores=<navigator.hardwareConcurrency>
memory=<navigator.deviceMemory>
screen=<screen.width>x<screen.height>
colorDepth=<screen.colorDepth>
avail=<screen.availWidth>x<screen.availHeight>
tz=<Intl.DateTimeFormat().resolvedOptions().timeZone>
canvas=<canvas 绘制（渐变 + 文本 + 曲线 + 圆）后的 toDataURL()>
webgl=<WEBGL_debug_renderer_info 的 UNMASKED_VENDOR_WEBGL / UNMASKED_RENDERER_WEBGL（取不到就 '-'）>
```

- 拼成一个规范字符串（顺序固定、每项一行、缺失用 `-` 占位）→ `SHA-256` → 64 位小写十六进制；
- **顺序 / 命名改变等于所有已登记指纹失配**：要改就当作一次「让用户重新登记」；
- 服务端只做格式校验（`[0-9a-f]{16,128}`）与查表，**不做二次哈希**（摘要本身就是不可逆摘要，直接存
  `wiki_fingerprint.fingerprint_hash`）；
- `crypto.subtle` 在非安全上下文不可用（http + 非 localhost）→ 脚本隐藏登录 / 添加按钮并给出提示，
  服务端在开了「仅 HTTPS 允许」时也会独立拒绝（前端隐藏不是安全边界）。

## 登录桥（会话内容与密码登录一致）

core 的密码登录成功分支是：

```java
UserSession.login(request, wikiUser.toTransferCopy(), "1".equals(remember));
```

插件的 `service/LoginSessionImpl`（`LoginSession` 的实现）用 `RequestContextHolder` 拿到当前请求，
经 core 的只读用户桥 `UserAdminStore.byId`（core 唯一的「按 id 取完整 WikiUser」桥）查出 `WikiUser`，
然后照搬同一套会话状态（插件只依赖 `common`，不能直接调 core 的 `UserSession`）：

```java
session.setAttribute("wikiUser", user.toTransferCopy());
session.setMaxInactiveInterval(remember ? 30 * 24 * 60 * 60 : 30 * 60);
```

因此会话属性名（core 的 `UserSession.ATTRIBUTE = "wikiUser"`）、属性内容（密码已脱敏成等长星号的
`WikiUser`）、有效期规则（记住我 30 天 / 否则 30 分钟）都与密码登录完全一致；
core 的 `CurrentUser`、模板、权限判断读到的就是同一个用户。
「记住我」的**持久登录 Cookie / `wiki_login_token`** 属于 core 密码登录流程额外签发的东西，
本桥不做（`UserSession.login` 本身也不涉及）。这条一致性由插件的 `LoginSessionImplTest` 断言
（同一个属性名、同一个脱敏口令、同一个 30 天 / 30 分钟间隔、logout 使会话失效）。

## 与其它实现方式的差异

- 不用 Cookie / localStorage 存任何「指纹令牌」：指纹摘要本身就是凭据，浏览器不需要保存状态；
- 不做设备指纹的「模糊匹配」（相似度打分）：只做精确摘要匹配，宁可让用户重新登记；
- 不记录登录 IP / UA 历史（`wiki_fingerprint` 只有 UA 摘要与两个时间戳）。

> **功能开关**：`features.fingerprint-login`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
