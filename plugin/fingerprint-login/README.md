# 浏览器快捷登录

启用站点设置中的浏览器快捷登录后，用户可登录账号并在「参数设置 → 浏览器快捷登录」登记常用浏览器。此功能默认关闭，对应的配置键为 features.fingerprint-login。

登记后可在登录页选择「用已登记的浏览器登录」。用户可以随时删除登记。连续 30 天未使用的凭据会过期。只在本人控制的浏览器里登记。

升级前登记的浏览器指纹无法用于登录。用户可用密码或其他方式登录后重新登记。旧登记保留在管理列表，便于用户清理。旧的 https-only 配置由强制 HTTPS 取代，本机回环开发环境仍可使用 HTTP。

| 接口 | 功能 |
| --- | --- |
| POST /api/fingerprint-login | 用浏览器 Cookie 登录，成功后更换凭据 |
| POST /api/fingerprint-login/add | 已登录用户登记当前浏览器，表单参数为 label |
| GET /api/fingerprint-login/list | 查看自己的浏览器登记 |
| DELETE /api/fingerprint-login/remove | 按 id 删除自己的浏览器登记 |

生产 Cookie 设置 Secure、HttpOnly 和 SameSite=Strict。数据库沿用 wiki_fingerprint 表，保存带版本前缀的凭据摘要。core 负责登录会话及按客户端 IP 和凭据限流。
