# 反向代理（Reverse proxy）

管理员动态配置 URL 前缀，把指定站点代理到 Wiki 的固定命名空间：

```text
/proxy_google_doc/...  →  https://docs.google.com/...
```

- 管理入口：`特殊:反向代理` / `Special:ReverseProxy`
- 管理权限：Wiki 的 `protect` 动作（默认管理员 / 行政员，可在 `特殊:权限配置` 调整）
- 路由 URL 固定为 `/proxy_<路由名>/`，例如路由名 `google_doc` 对应 `/proxy_google_doc/`
- 路由配置保存在站点设置 `reverse-proxy.routes`（JSON），不需要额外的数据库表
- 总开关与出站代理：`特殊:权限配置 → 站点设置 → 反向代理`
- 裸根部署时，core 的 `WikiUrlRewriteFilter` 会把 `/proxy_*` 视为保留命名空间，不再当成 Wiki 页面标题；需要同名 Wiki 页面时使用 `/wiki/proxy_...` 旧路径访问

## 设计取舍（安全优先）

1. **不是开放代理**：目标 host 只来自管理员配置，请求 URL 的路径后缀只能改变上游路径，不能改变主机。
2. **默认阻断内网目标**：回环、私网、链路本地、CGNAT、保留地址都会拒绝；只有显式勾选“允许代理到本机 / 内网地址”才放行。
3. **Cookie / 凭据隔离**：Wiki 的 `Cookie`、`Authorization`、`Origin`、`Referer`、`X-Forwarded-*` 不转发给上游；上游 `Set-Cookie` 也不写回浏览器。这样上游页面不能直接拿到 Wiki 登录态。
4. **CSRF**：管理写接口除了同源检查，还要求 HttpSession 内的随机令牌和 `X-Reverse-Proxy-CSRF` 请求头。跨站表单无法伪造。
5. **CSP sandbox**：默认给 HTML 响应加 `sandbox`（不包含 `allow-same-origin`），让上游脚本以不透明源执行；必要时可以按路由关闭以换取兼容性。
6. **重定向**：不自动跟随上游 3xx。同源 `Location` 会改写回 `/proxy_<路由名>/...`，外部 Location 保持原样，避免代理被利用做 SSRF 跳转。
7. **HTML / CSS 改写**：可选，只改写与上游同源的 URL。适合普通静态站点；复杂 SPA / Google Docs 可能依赖绝对 API、跨域、登录 Cookie 或 WebSocket，无法保证完整可用。

## 使用

1. 打开 `特殊:反向代理`。
2. 填写：
   - 路由名：如 `google_doc`
   - 上游地址：如 `https://docs.google.com/`
   - 访问级别：公开 / 登录用户 / 管理员（默认管理员）
   - 允许方法：建议 `GET,HEAD`；需要提交表单才加入 `POST`
   - 按需打开 HTML / CSS URL 改写、CSP sandbox
3. 保存后访问 `/proxy_google_doc/`。

如果服务器本身不能直连上游（例如 Google），在 `特殊:权限配置 → 站点设置 → 反向代理` 里把“出站代理”设为：

```text
socks5h://127.0.0.1:10808
```

也支持 `http://host:port`。出站代理只用于本插件的上游请求；SOCKS5 会通过只监听回环地址的本机 CONNECT 桥转接，JDK HttpClient 不直接识别 `socks5://`。

例如想访问 Google 文档某篇文档：

```text
/proxy_google_doc/document/d/<document-id>/edit
```

## 已知限制

- 不代理 WebSocket（Google Docs 的实时协作、部分在线编辑器需要 WebSocket）。
- 不会把上游的登录 Cookie 保存在服务端，因此需要登录的上游站点无法“记住登录”；这是有意的安全边界。
- WikiKernel 核心默认发送 `X-Frame-Options: DENY`，代理页适合直接打开；若要在 Wiki 内部用 iframe 嵌入，需要另行调整 core 的安全响应头。
- HTML 改写只处理 `href/src/action/poster/data`、`srcset`、内联 CSS、`<style>`、meta refresh；JavaScript 动态拼接的 URL 不会处理。
- 同源代理意味着上游 HTML 如果关闭 CSP sandbox，就能以 Wiki 同源身份运行脚本。只应对可信上游关闭 sandbox。
- 单实例部署；多实例部署时每个实例各自执行代理，配置从共享站点设置读取。
- 插件不自带请求限流；公开路由建议配合站点级限流或把访问级别设为“登录用户 / 管理员”，避免被当作免费带宽出口。

## 构建 / 部署

```text
gradlew.bat :plugin:reverse-proxy:jar
copy plugin\reverse-proxy\build\libs\reverse-proxy-*.jar plugins\
```

然后重启 WikiKernel（`run.bat`），或使用插件动态装卸：
`POST /api/plugins/reload`（仅管理员）。
