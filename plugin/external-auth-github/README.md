# external-auth-github

GitHub OAuth App 第三方登录 / 注册 provider 插件。

- 扩展点：`cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider`
- 站点设置键：`auth.github.enabled`、`auth.github.client-id`、`auth.github.client-secret`
- 回调地址：`/auth/github/callback`
- 首次登录自动注册由 core 的 `auth.external.auto-register` 控制，并受注册开关 / 封禁 / 注册钩子约束。

管理员在 `特殊:权限配置 → 第三方登录` 配置；用户可在 `特殊:参数设置 → 第三方授权` 绑定 / 解绑。

