package cc.thonly.wikikernel.http;

/**
 * 当前 HTTP 请求的客户端信息（IP / User-Agent），由 {@link ClientInfoFilter} 写入、请求结束后清除。
 * <p>
 * 日志服务（{@code LogService}）写日志时从这里取，保证每一条日志都带 IP / UA（无请求上下文时为 null）。
 */
public final class RequestMetadata {

    private record Meta(String ip, String userAgent) {
    }

    private static final ThreadLocal<Meta> CURRENT = new ThreadLocal<>();

    private RequestMetadata() {
    }

    public static void set(String ip, String userAgent) {
        CURRENT.set(new Meta(ip, userAgent));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String ip() {
        Meta meta = CURRENT.get();
        return meta == null ? null : meta.ip();
    }

    public static String userAgent() {
        Meta meta = CURRENT.get();
        return meta == null ? null : meta.userAgent();
    }
}
