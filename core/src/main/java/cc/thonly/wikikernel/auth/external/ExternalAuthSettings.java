package cc.thonly.wikikernel.auth.external;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.web.RequestHeadContext;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.util.Locale;

/** 第三方登录的站点设置键与通用 URL 规则。 */
public final class ExternalAuthSettings {

    /** 反向代理时对外站点根地址，如 https://wiki.example.com；留空 = 按当前请求推导。 */
    public static final String BASE_URL_KEY = "auth.external.base-url";

    /** 首次第三方登录时是否自动创建本地账号（默认开启；仍受 features.register 开关约束）。 */
    public static final String AUTO_REGISTER_KEY = "auth.external.auto-register";

    private ExternalAuthSettings() {
    }

    /** 对外回调地址；base URL 配置优先，否则使用当前请求的 scheme + host + port。 */
    public static String callbackUrl(ExternalAuthProvider provider,
                                     SiteSettingsService settings,
                                     HttpServletRequest request) {
        String base = settings.get(BASE_URL_KEY, "");
        if (base == null || base.isBlank()) {
            base = request == null ? "" : RequestHeadContext.buildBaseUrl(request);
        }
        return normalizeBaseUrl(base) + "/auth/" + provider.id() + "/callback";
    }

    /** 只接受 http(s) 根地址；去掉末尾斜杠、查询串和片段。非法值返回空串。 */
    public static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!value.toLowerCase(Locale.ROOT).startsWith("http://")
                && !value.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "";
            }
            StringBuilder base = new StringBuilder()
                    .append(uri.getScheme())
                    .append("://")
                    .append(uri.getHost());
            if (uri.getPort() >= 0) {
                base.append(':').append(uri.getPort());
            }
            String path = uri.getPath();
            if (path != null && !path.isBlank() && !"/".equals(path)) {
                base.append(path.startsWith("/") ? path : "/" + path);
            }
            return base.toString().replaceAll("/+$", "");
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    /** 只允许站内绝对路径，防止开放重定向。 */
    public static String safeReturnTo(String value, String fallback) {
        String target = value == null ? "" : value.trim();
        if (!target.startsWith("/") || target.startsWith("//") || target.contains("\\")
                || target.contains("\r") || target.contains("\n")) {
            return fallback;
        }
        return target;
    }
}

