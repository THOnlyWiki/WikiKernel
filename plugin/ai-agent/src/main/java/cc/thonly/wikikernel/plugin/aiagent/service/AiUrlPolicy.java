package cc.thonly.wikikernel.plugin.aiagent.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 用户可配置的 AI 端点地址安全策略。
 *
 * <p>用户级 {@code baseUrl} 会由服务器发起请求；如果允许任意地址，就会变成 SSRF
 * （内网服务 / 云 metadata / 回环端口扫描）。这里只允许公网 http(s) 地址。
 * 管理员在 LocalSettings / 站点级配置的地址仍可使用内网服务（例如本机 Ollama），
 * 但用户级地址不享受这个信任级别。</p>
 *
 * <p>说明：DNS 在每次解析时校验，不能完全消除 DNS rebinding；对普通用户来说已经
 * 比“任意 URL 直连”安全得多。AI HTTP 客户端同时禁止自动跟随重定向。</p>
 */
public final class AiUrlPolicy {

    private AiUrlPolicy() {
    }

    /** 只接受公网 http(s) URL；解析失败 / 私网 / 回环 / 保留地址返回 false。 */
    public static boolean isPublicHttpUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return false;
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
            return false;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            return !(a == 0 || a == 10 || a == 127
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 192 && b == 0 && c == 0)
                    || (a == 192 && b == 0 && c == 2)
                    || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100)
                    || (a == 203 && b == 0 && c == 113)
                    || a >= 240);
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return (first & 0xfe) != 0xfc; // fc00::/7
        }
        return false;
    }
}
