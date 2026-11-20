package cc.thonly.wikikernel.ai;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * OpenAI 兼容端点的出站策略。
 *
 * <p>管理员的 AI 配置最终会被服务器用来发起请求，因此不能把这里做成“用户传 URL 就代请求”
 * 的通用代理。这个类只接受固定格式的 http(s) 端点，并在每次请求前解析主机：</p>
 * <ul>
 *     <li>默认只允许 HTTPS + 公网地址；</li>
 *     <li>只有管理员显式打开“允许本机 / 内网端点”时，才允许 HTTP 和私网地址，
 *         方便本地 Ollama / vLLM；</li>
 *     <li>链路本地 / 云 metadata / 组播地址永远拒绝。</li>
 * </ul>
 *
 * <p>说明：DNS 在每次发请求前解析，仍不能完全消除 DNS rebinding；同时 AI HTTP
 * 客户端禁止自动跟随重定向，避免公网 endpoint 把请求引向内网。</p>
 */
public final class AiEndpointPolicy {

    public static boolean isPublicHttpUrl(String url) {
        try { validate(url, false); return true; }
        catch (IllegalArgumentException exception) { return false; }
    }

    private static final int MAX_URL_LENGTH = 2048;

    private AiEndpointPolicy() {
    }

    /** 校验失败抛出 {@link IllegalArgumentException}（消息可直接给管理员看）。 */
    public static void validate(String raw, boolean allowPrivate) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("AI 接口地址不能为空。");
        }
        if (value.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("AI 接口地址过长。");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("AI 接口地址格式不正确。");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("AI 接口只支持 http / https。");
        }
        if (!allowPrivate && !"https".equals(scheme)) {
            throw new IllegalArgumentException("公网 AI 接口必须使用 HTTPS；如确实使用本机 / 内网服务，请显式打开“允许内网端点”。");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("AI 接口地址缺少主机名。");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("AI 接口地址不能包含用户名 / 密码。");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("AI 接口地址不能包含 # 片段。");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) {
                throw new IllegalArgumentException("AI 接口主机无法解析。");
            }
            for (InetAddress address : addresses) {
                if (isAlwaysBlocked(address)) {
                    throw new IllegalArgumentException("AI 接口不能指向链路本地 / 云 metadata / 组播地址。");
                }
                if (!allowPrivate && !isPublic(address)) {
                    throw new IllegalArgumentException("AI 接口指向本机 / 内网地址；请打开“允许内网端点”或换用公网 HTTPS 地址。");
                }
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("AI 接口主机无法解析：" + uri.getHost());
        }
    }

    /** 生成给 OpenAI 兼容服务调用的最终地址（base URL 可带或不带 /v1）。 */
    public static String chatCompletionsUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.toLowerCase(Locale.ROOT).endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private static boolean isAlwaysBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            // 云厂商 metadata（常见 169.254.169.254）以及 IPv4 链路本地
            if (a == 169 && b == 254) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
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
