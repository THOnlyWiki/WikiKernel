package cc.thonly.wikikernel.startup;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.config.SiteProperties;
import cc.thonly.wikikernel.config.SkinProperties;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.util.Locale;

/**
 * 启动等待门禁：应用完全就绪（插件加载完成）之前，不允许页面渲染或 API 请求。
 * <ul>
 *     <li>普通网页请求 → 内联样式的「正在启动」等待页（不依赖任何静态资源）；</li>
 *     <li>{@code /api/**}、{@code /api.php}、{@code /rest.php} → 503 JSON；</li>
 *     <li>{@code /api/ready} → 轻量就绪状态，供等待页轮询。</li>
 * </ul>
 * 过滤器使用最高优先级，排在 SetupFilter / Spring Security 之前；就绪后完全放行。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StartupGateFilter extends OncePerRequestFilter {

    private final StartupState state;
    private final SiteProperties siteProperties;
    private final SkinProperties skinProperties;

    public StartupGateFilter(StartupState state, SiteProperties siteProperties,
                             SkinProperties skinProperties) {
        this.state = state;
        this.siteProperties = siteProperties;
        this.skinProperties = skinProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (state.ready()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if ("/api/ready".equals(path)) {
            writeReady(response);
            return;
        }
        if (isApi(path)) {
            writeApiStarting(response);
            return;
        }
        if ("/favicon.ico".equals(path)) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        writeWaitingPage(response, request);
    }

    private static boolean isApi(String path) {
        return path.startsWith("/api/") || path.equals("/api.php") || path.startsWith("/rest.php");
    }

    private void writeReady(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Retry-After", "1");
        response.getWriter().write("{\"ready\":false,\"startedAt\":\""
                + state.startedAt() + "\",\"uptimeMillis\":" + state.uptimeMillis() + "}");
    }

    private void writeApiStarting(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Retry-After", "2");
        response.getWriter().write("{\"code\":503,\"ready\":false,"
                + "\"message\":\"WikiKernel is starting, please retry shortly.\"}");
    }

    private void writeWaitingPage(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Retry-After", "2");
        String skin = requestedSkin(request);
        String theme = theme(skin);
        String siteName = siteProperties.getName() == null || siteProperties.getName().isBlank()
                ? "WikiKernel" : siteProperties.getName().trim();
        String title = HtmlUtils.htmlEscape(siteName);
        String lang = locale(request);
        response.getWriter().write(page(title, HtmlUtils.htmlEscape(skin), theme, lang));
    }

    private String requestedSkin(HttpServletRequest request) {
        String requested = request.getParameter("useskin");
        if (requested == null || requested.isBlank()) {
            WikiUser user = UserSession.current(request);
            if (user != null) {
                requested = user.getSkin();
            }
        }
        if (requested == null || requested.isBlank()) {
            requested = skinProperties.getDefaultSkin();
        }
        if (requested == null || requested.isBlank()) {
            return "fallback";
        }
        return requested.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private static String theme(String skin) {
        if (skin.contains("kawaii") || skin.contains("timeless")) {
            return "kawaii";
        }
        if (skin.contains("vector")) {
            return "vector";
        }
        if (skin.contains("plain") || skin.contains("fallback")) {
            return "plain";
        }
        return "generic";
    }

    private static String locale(HttpServletRequest request) {
        String language = request.getHeader("Accept-Language");
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("en") ? "en" : "zh";
    }

    private static String page(String siteName, String skin, String theme, String lang) {
        boolean en = "en".equals(lang);
        String heading = en ? "Starting WikiKernel" : "正在启动 WikiKernel";
        String message = en
                ? "The service is finishing startup. This page will refresh automatically."
                : "服务正在完成启动与插件初始化，页面会自动刷新。";
        String hint = en
                ? "Please wait a moment. API requests return 503 until startup is complete."
                : "请稍候；启动完成前 API 请求会返回 503。";
        String wait = en ? "Starting…" : "正在启动…";
        return """
                <!DOCTYPE html>
                <html lang="%s">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="color-scheme" content="light dark">
                <title>%s - %s</title>
                <style>
                :root { --accent:#36c; --bg:#f6f7f9; --card:#fff; --text:#202122; --muted:#54595d; --border:#c8ccd1; --header:linear-gradient(180deg,#fff 0%%,#eaf3ff 100%%); }
                * { box-sizing:border-box; }
                html,body { margin:0; min-height:100%%; }
                body { background:var(--bg); color:var(--text); font-family:"Noto Sans SC","Noto Sans CJK SC","Microsoft YaHei",system-ui,sans-serif; }
                .waiting-header { min-height:64px; display:flex; align-items:center; padding:0 24px; background:var(--header); border-bottom:1px solid var(--border); }
                .waiting-brand { color:var(--accent); font-size:1.15rem; font-weight:700; letter-spacing:.02em; }
                .waiting-wrap { min-height:calc(100vh - 64px); display:grid; place-items:center; padding:32px 18px; }
                .waiting-card { width:min(540px,100%%); padding:32px 28px; border:1px solid var(--border); border-radius:8px; background:var(--card); box-shadow:0 8px 28px rgba(0,0,0,.08); text-align:center; }
                .waiting-spinner { width:44px; height:44px; margin:0 auto 18px; border:4px solid color-mix(in srgb,var(--accent) 22%%,transparent); border-top-color:var(--accent); border-radius:50%%; animation:waiting-spin .9s linear infinite; }
                h1 { margin:0 0 .55em; font-size:1.45rem; line-height:1.3; }
                p { margin:.45em 0; color:var(--muted); line-height:1.65; }
                .waiting-hint { font-size:.9rem; }
                .waiting-progress { height:3px; margin:1.35em 0 1em; overflow:hidden; border-radius:3px; background:color-mix(in srgb,var(--accent) 15%%,transparent); }
                .waiting-progress::after { content:""; display:block; width:35%%; height:100%%; border-radius:inherit; background:var(--accent); animation:waiting-bar 1.4s ease-in-out infinite; }
                @keyframes waiting-spin { to { transform:rotate(360deg); } }
                @keyframes waiting-bar { 0%% { transform:translateX(-120%%); } 100%% { transform:translateX(390%%); } }
                body.waiting-kawaii { --accent:#36c; --bg:#f6f7f9; --header:linear-gradient(180deg,#fff 0%%,#f8f9fa 100%%); --border:#c8ccd1; }
                body.waiting-kawaii .waiting-card { border-radius:8px; }
                body.waiting-vector { --accent:#36c; --bg:#f6f6f6; --header:linear-gradient(180deg,#fff 0%%,#eaf3ff 100%%); }
                body.waiting-plain { --accent:#54595d; --bg:#f7f7f7; --header:#fff; }
                body.waiting-generic { --accent:#36c; }
                @media (prefers-color-scheme:dark) {
                    :root { --bg:#1e1e1e; --card:#27292d; --text:#eaecf0; --muted:#a2a9b1; --border:#404244; --header:#232529; }
                    body.waiting-kawaii { --bg:#1e1e1e; --card:#27292d; --header:#232529; --border:#404244; }
                }
                @media (prefers-reduced-motion:reduce) { .waiting-spinner,.waiting-progress::after { animation:none; } }
                </style>
                </head>
                <body class="waiting-page waiting-%s" data-skin="%s">
                <header class="waiting-header"><span class="waiting-brand">%s</span></header>
                <main class="waiting-wrap">
                    <section class="waiting-card" role="status" aria-live="polite">
                        <div class="waiting-spinner" aria-hidden="true"></div>
                        <h1>%s</h1>
                        <p>%s</p>
                        <div class="waiting-progress" aria-hidden="true"></div>
                        <p class="waiting-hint">%s</p>
                    </section>
                </main>
                <script>
                (function () {
                    var attempts = 0;
                    function poll() {
                        attempts++;
                        fetch('/api/ready?t=' + Date.now(), {cache: 'no-store', headers: {'Accept': 'application/json'}})
                            .then(function (response) { return response.json(); })
                            .then(function (data) {
                                if (data && data.ready) { window.location.reload(); return; }
                                setTimeout(poll, 1100);
                            })
                            .catch(function () { setTimeout(poll, 1600); });
                    }
                    setTimeout(poll, 700);
                })();
                </script>
                </body>
                </html>
                """.formatted(lang, HtmlUtils.htmlEscape(heading), siteName, theme, skin, siteName,
                heading, message, hint);
    }
}
