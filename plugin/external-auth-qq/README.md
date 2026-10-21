# external-auth-qq

QQ 互联 OAuth 第三方登录 / 注册 provider 插件。

- 扩展点：`cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider`
- 站点设置键：`auth.qq.enabled`、`auth.qq.app-id`、`auth.qq.app-key`
- 回调地址：`/auth/qq/callback`
- QQ 通常不返回邮箱；首次登录自动创建本地账号，之后可在参数设置绑定其他账号。

管理员在 `特殊:权限配置 → 第三方登录` 配置；用户可在 `特殊:参数设置 → 第三方授权` 绑定 / 解绑。

