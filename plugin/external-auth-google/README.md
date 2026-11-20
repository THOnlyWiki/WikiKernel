# Google 第三方登录

使用 Google 授权登录 WikiKernel，或在「特殊:参数设置 → 第三方授权」绑定和解绑 Google 账号。

## 配置

在 Google Auth Platform 创建 Web application 类型的 OAuth 客户端，配置品牌、受众与授权回调。

```text
https://wiki.example.com/auth/google/callback
```

在「特殊:权限配置 → 第三方登录」填写 Client ID 和 Client Secret，再启用 Google。站点在反向代理后时，把第三方登录的站点根地址设置为公网 HTTPS 地址。 [Google OAuth 官方文档](https://developers.google.com/identity/protocols/oauth2/web-server)

## 实现

使用 Google UserInfo 的 `sub` 绑定账号，请求 `openid profile email`。仅同步 Google 确认的 Gmail 地址或带托管域声明的 Workspace 地址。 授权使用服务端授权码交换和 S256 PKCE，访问令牌仅在本次请求中使用。

自动建号沿用 WikiKernel 的注册开关、封禁和注册钩子。重复邮箱需要用户先登录已有账号后主动绑定。

[站点配置](../../docs/content/administration/third-party-login.md) · [插件开发](../../docs/content/development/plugin-development.md)
