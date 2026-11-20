# Discord 第三方登录

使用 Discord 授权登录 WikiKernel，或在「特殊:参数设置 → 第三方授权」绑定和解绑 Discord 账号。

## 配置

在 Discord Developer Portal 创建应用，把回调地址加入 OAuth2 的 Redirects。

```text
https://wiki.example.com/auth/discord/callback
```

在「特殊:权限配置 → 第三方登录」填写 Client ID 和 Client Secret，再启用 Discord。站点在反向代理后时，把第三方登录的站点根地址设置为公网 HTTPS 地址。 [Discord OAuth 官方文档](https://docs.discord.com/developers/topics/oauth2)

## 实现

使用 Discord 用户 `id` 绑定账号，请求 `identify email`。提供方明确验证邮箱时才同步该地址。 授权使用服务端授权码交换和 S256 PKCE，访问令牌仅在本次请求中使用。

自动建号沿用 WikiKernel 的注册开关、封禁和注册钩子。重复邮箱需要用户先登录已有账号后主动绑定。

[站点配置](../../docs/content/administration/third-party-login.md) · [插件开发](../../docs/content/development/plugin-development.md)
