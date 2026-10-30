# external-auth-qq

QQ 第三方插件：**QQ 邮箱验证绑定** + 可选的 QQ 互联 OAuth 登录 / 注册。

## QQ 邮箱验证绑定（无需 OAuth App）

用户在 `特殊:参数设置 → QQ 邮箱绑定` 填写**数字 QQ 号**，插件向 `<QQ号>@qq.com` 发送确认邮件；
点击邮件里的链接（30 分钟内有效）后才会写入绑定。

- 只接受 5–12 位数字、首位非 0 的 QQ 号；不填写邮箱地址。
- 即使 QQ 邮箱设置了英文别名，也始终使用数字 QQ 号对应的 `@qq.com` 地址。
- 一个账号最多绑定一个 QQ；同一个 QQ 不能同时绑定多个账号。
- 确认 token 只保存 SHA-256，不保存明文；确认成功后立即失效。
- 绑定记录在插件自有表 `wiki_qq_email_binding`；邮件类型为 `qq-email-bind`，可在
  `wikikernel.email.types.qq-email-bind` 单独关闭。
- 邮件发送依赖 core 的 `wikikernel.email.*` SMTP 配置；发送失败会返回提示，不会写入绑定。

## 可选 QQ OAuth 登录

原有 OAuth 登录仍保留，需要在 `特殊:权限配置 → 第三方登录` 配置：

- 站点设置键：`auth.qq.enabled`、`auth.qq.app-id`、`auth.qq.app-key`
- 回调地址：`/auth/qq/callback`

不配置 OAuth 也能使用上面的 QQ 邮箱验证绑定。QQ OAuth 通常不返回邮箱；首次登录自动创建本地账号，
之后可在参数设置绑定其他账号。

