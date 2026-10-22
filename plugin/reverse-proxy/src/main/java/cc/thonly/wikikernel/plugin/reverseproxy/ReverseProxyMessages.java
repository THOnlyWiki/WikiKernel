package cc.thonly.wikikernel.plugin.reverseproxy;

import cc.thonly.wikikernel.common.extension.MessageBundle;

import java.util.LinkedHashMap;
import java.util.Map;

/** 插件界面文案（由 core 合并到 MessageSource，模板与脚本可用）。 */
public class ReverseProxyMessages implements MessageBundle {

    @Override
    public Map<String, Map<String, String>> messages() {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        all.put("zh", zh());
        all.put("en", en());
        return all;
    }

    private static Map<String, String> zh() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("plugin.reverse-proxy.name", "反向代理");
        m.put("plugin.reverse-proxy.description", "按 /proxy_<路由名>/ 前缀反向代理到管理员指定的网站，支持动态配置与安全的 Cookie 隔离。");
        m.put("special.reverseProxy", "反向代理");
        m.put("special.reverseProxy.description", "管理员在这里配置 URL 前缀到上游网站的映射。普通读者只会看到“需要管理员权限”。");
        m.put("settings.reverseProxy", "反向代理");
        m.put("settings.reverseProxyHint", "全局出站网络设置；每个代理路由的目标仍在 特殊:反向代理 中配置。");
        m.put("settings.reverseProxyEnabled", "启用反向代理功能");
        m.put("settings.reverseProxyOutboundProxy", "出站代理（可空，例如 socks5h://127.0.0.1:10808）");
        m.put("settings.reverseProxyOutboundProxyHint", "支持 http:// 与 socks5(h)://；留空 = 直连。仅用于访问上游网站，不会影响 Wiki 自身请求。");
        m.put("reverseProxy.disabled", "反向代理功能已在站点设置中关闭。");
        m.put("reverseProxy.loginRequired", "请先登录后再配置反向代理。");
        m.put("reverseProxy.forbidden", "只有管理员（或拥有“保护”权限的用户组）可以配置反向代理。");
        m.put("reverseProxy.safetyTitle", "安全边界（请先读）");
        m.put("reverseProxy.safety", "代理内容会从 Wiki 同源地址提供。默认只允许访问公开网络，且不会把 Wiki 的 Cookie / Authorization 转发给上游；上游 Set-Cookie 也不会写回浏览器。若上游是不可信站点，请保持 CSP sandbox 开启。Google Docs 这类复杂应用可能需要关闭改写 / sandbox，且仍可能因登录、跨域或 WebSocket 而无法完整工作。");
        m.put("reverseProxy.intro", "路由 URL 固定为 /proxy_<路由名>/，例如路由名 google_doc 对应 /proxy_google_doc/。上游地址由管理员固定，不能由请求参数改变。");
        m.put("reverseProxy.empty", "还没有配置任何代理路由。");
        m.put("reverseProxy.table.route", "路由");
        m.put("reverseProxy.table.upstream", "上游");
        m.put("reverseProxy.table.access", "访问级别");
        m.put("reverseProxy.table.methods", "允许方法");
        m.put("reverseProxy.table.status", "状态");
        m.put("reverseProxy.table.actions", "操作");
        m.put("reverseProxy.access.public", "公开");
        m.put("reverseProxy.access.login", "登录用户");
        m.put("reverseProxy.access.admin", "管理员");
        m.put("reverseProxy.enabled", "已启用");
        m.put("reverseProxy.disabledRoute", "已停用");
        m.put("reverseProxy.open", "打开");
        m.put("reverseProxy.edit", "编辑");
        m.put("reverseProxy.delete", "删除");
        m.put("reverseProxy.deleteConfirm", "确定删除这条代理路由吗？已有链接会立即失效。");
        m.put("reverseProxy.form.title", "新增 / 编辑路由");
        m.put("reverseProxy.form.slug", "路由名");
        m.put("reverseProxy.form.slugHint", "只允许字母、数字、下划线、连字符；例如 google_doc");
        m.put("reverseProxy.form.upstream", "上游地址");
        m.put("reverseProxy.form.upstreamHint", "例如 https://docs.google.com/；请求 /proxy_google_doc/foo 会转发到 https://docs.google.com/foo");
        m.put("reverseProxy.form.outboundHint", "如果服务器无法直连上游，可在 特殊:权限配置 → 站点设置 → 反向代理 配置出站代理（支持 socks5h://）。");
        m.put("reverseProxy.form.label", "显示名（可选）");
        m.put("reverseProxy.form.access", "访问级别");
        m.put("reverseProxy.form.methods", "允许的方法");
        m.put("reverseProxy.form.methodsHint", "逗号分隔，建议 GET,HEAD；需要提交表单时才加入 POST");
        m.put("reverseProxy.form.timeout", "上游超时（秒）");
        m.put("reverseProxy.form.rewriteHtml", "改写 HTML / CSS 中的上游 URL");
        m.put("reverseProxy.form.rewriteHtmlHint", "适合静态站点；复杂 SPA / Google Docs 可能不需要或可能被破坏");
        m.put("reverseProxy.form.sandbox", "为 HTML 启用 CSP sandbox");
        m.put("reverseProxy.form.sandboxHint", "阻止上游脚本以 Wiki 同源身份执行；兼容性差时再关闭");
        m.put("reverseProxy.form.allowPrivateTargets", "允许代理到本机 / 内网地址");
        m.put("reverseProxy.form.allowPrivateTargetsHint", "仅在你明确要代理内网服务时开启；会扩大 SSRF 风险");
        m.put("reverseProxy.form.enabled", "启用此路由");
        m.put("reverseProxy.form.save", "保存路由");
        m.put("reverseProxy.form.reset", "清空 / 新增");
        m.put("reverseProxy.form.required", "请填写路由名和上游地址。");
        m.put("reverseProxy.saved", "路由已保存。");
        m.put("reverseProxy.deleted", "路由已删除。");
        m.put("reverseProxy.notFound", "路由不存在。");
        m.put("reverseProxy.badRequest", "路由配置不合法。");
        m.put("reverseProxy.csrfFailed", "安全校验失败，请刷新页面后重试。");
        m.put("reverseProxy.networkError", "网络请求失败。");
        m.put("reverseProxy.operationFailed", "操作失败。");
        m.put("reverseProxy.upstreamError", "上游服务返回错误。");
        m.put("reverseProxy.routeDisabled", "该代理路由已停用。");
        m.put("reverseProxy.routeForbidden", "没有权限访问该代理路由。");
        m.put("reverseProxy.methodNotAllowed", "该代理路由不允许此请求方法。");
        return m;
    }

    private static Map<String, String> en() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("plugin.reverse-proxy.name", "Reverse proxy");
        m.put("plugin.reverse-proxy.description", "Dynamic /proxy_<route>/ reverse-proxy routes with cookie isolation and safe URL rewriting.");
        m.put("special.reverseProxy", "Reverse proxy");
        m.put("special.reverseProxy.description", "Configure dynamic URL-prefix to upstream mappings. Regular readers only see an admin-required message.");
        m.put("settings.reverseProxy", "Reverse proxy");
        m.put("settings.reverseProxyHint", "Global outbound-network setting; individual routes are configured in Special:ReverseProxy.");
        m.put("settings.reverseProxyEnabled", "Enable the reverse proxy feature");
        m.put("settings.reverseProxyOutboundProxy", "Outbound proxy (empty, or e.g. socks5h://127.0.0.1:10808)");
        m.put("settings.reverseProxyOutboundProxyHint", "Supports http:// and socks5(h)://; empty = direct. Only affects upstream fetches, not wiki requests.");
        m.put("reverseProxy.disabled", "Reverse proxy is disabled in site settings.");
        m.put("reverseProxy.loginRequired", "Please sign in before configuring reverse-proxy routes.");
        m.put("reverseProxy.forbidden", "Only administrators (or groups with the protect right) can configure reverse-proxy routes.");
        m.put("reverseProxy.safetyTitle", "Security boundary (read first)");
        m.put("reverseProxy.safety", "Proxied content is served from the wiki origin. Private-network targets are blocked by default. Wiki cookies and Authorization headers are never forwarded, and upstream Set-Cookie headers are not exposed. Keep CSP sandbox enabled for untrusted sites. Complex apps such as Google Docs may require disabling rewriting/sandbox and may still fail because of login, cross-origin, or WebSocket requirements.");
        m.put("reverseProxy.intro", "Route URLs are fixed as /proxy_<route>/, e.g. google_doc → /proxy_google_doc/. The upstream is fixed by the administrator and cannot be changed by request parameters.");
        m.put("reverseProxy.empty", "No proxy routes configured yet.");
        m.put("reverseProxy.table.route", "Route");
        m.put("reverseProxy.table.upstream", "Upstream");
        m.put("reverseProxy.table.access", "Access");
        m.put("reverseProxy.table.methods", "Methods");
        m.put("reverseProxy.table.status", "Status");
        m.put("reverseProxy.table.actions", "Actions");
        m.put("reverseProxy.access.public", "Public");
        m.put("reverseProxy.access.login", "Signed-in users");
        m.put("reverseProxy.access.admin", "Administrators");
        m.put("reverseProxy.enabled", "Enabled");
        m.put("reverseProxy.disabledRoute", "Disabled");
        m.put("reverseProxy.open", "Open");
        m.put("reverseProxy.edit", "Edit");
        m.put("reverseProxy.delete", "Delete");
        m.put("reverseProxy.deleteConfirm", "Delete this proxy route? Existing links will stop working immediately.");
        m.put("reverseProxy.form.title", "Add / edit route");
        m.put("reverseProxy.form.slug", "Route name");
        m.put("reverseProxy.form.slugHint", "Letters, digits, underscores and hyphens; e.g. google_doc");
        m.put("reverseProxy.form.upstream", "Upstream URL");
        m.put("reverseProxy.form.upstreamHint", "e.g. https://docs.google.com/; /proxy_google_doc/foo forwards to https://docs.google.com/foo");
        m.put("reverseProxy.form.outboundHint", "If the server cannot reach the upstream directly, configure an outbound proxy in Special:Preferences/Site settings → Reverse proxy (socks5h:// supported).");
        m.put("reverseProxy.form.label", "Display label (optional)");
        m.put("reverseProxy.form.access", "Access level");
        m.put("reverseProxy.form.methods", "Allowed methods");
        m.put("reverseProxy.form.methodsHint", "Comma separated, GET,HEAD recommended; add POST only when forms are needed");
        m.put("reverseProxy.form.timeout", "Upstream timeout (seconds)");
        m.put("reverseProxy.form.rewriteHtml", "Rewrite upstream URLs in HTML / CSS");
        m.put("reverseProxy.form.rewriteHtmlHint", "Useful for static sites; complex SPAs / Google Docs may not need it or may be broken");
        m.put("reverseProxy.form.sandbox", "Enable CSP sandbox for HTML");
        m.put("reverseProxy.form.sandboxHint", "Prevents upstream scripts from running as wiki same-origin; disable only when compatibility requires it");
        m.put("reverseProxy.form.allowPrivateTargets", "Allow loopback / private-network targets");
        m.put("reverseProxy.form.allowPrivateTargetsHint", "Only enable when you explicitly need an internal service; this increases SSRF risk");
        m.put("reverseProxy.form.enabled", "Enable this route");
        m.put("reverseProxy.form.save", "Save route");
        m.put("reverseProxy.form.reset", "Clear / add new");
        m.put("reverseProxy.form.required", "Route name and upstream URL are required.");
        m.put("reverseProxy.saved", "Route saved.");
        m.put("reverseProxy.deleted", "Route deleted.");
        m.put("reverseProxy.notFound", "Route not found.");
        m.put("reverseProxy.badRequest", "Invalid route configuration.");
        m.put("reverseProxy.csrfFailed", "Security check failed; refresh and try again.");
        m.put("reverseProxy.networkError", "Network request failed.");
        m.put("reverseProxy.operationFailed", "Operation failed.");
        m.put("reverseProxy.upstreamError", "The upstream service returned an error.");
        m.put("reverseProxy.routeDisabled", "This proxy route is disabled.");
        m.put("reverseProxy.routeForbidden", "You do not have access to this proxy route.");
        m.put("reverseProxy.methodNotAllowed", "This request method is not allowed for the route.");
        return m;
    }
}
