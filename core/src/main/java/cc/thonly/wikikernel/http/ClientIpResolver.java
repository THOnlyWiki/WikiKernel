package cc.thonly.wikikernel.http;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.HttpProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 解析真实客户端 IP：
 * <ul>
 *     <li>{@code wikikernel.http.trust-proxy-headers=false}（默认）：直接用 {@code RemoteAddr}；</li>
 *     <li>true（部署在 Docker 反向代理 / CDN 后面）：依次读取
 *     {@code CF-Connecting-IP} / {@code CF-Connection-IP}（Cloudflare 及兼容代理）/
 *     {@code True-Client-IP} / {@code X-Real-IP} / {@code X-Forwarded-For}（取第一个地址）。</li>
 * </ul>
 * 日志（{@code wiki_log.ip}）、用户核查、封禁与限流都使用这里解析出的地址。
 * 开启信任头后，务必让反向代理覆盖而不是透传客户端自带的同名头，否则客户端可以伪造 IP。
 */
@Component
public class ClientIpResolver {

    /** 代理 / CDN 转发头，按优先级排列。 */
    private static final List<String> PROXY_HEADERS = List.of(
            "CF-Connecting-IP", "CF-Connection-IP", "True-Client-IP", "X-Real-IP", "X-Forwarded-For");

    /** 只接受「像 IP」的内容，防止把任意头文本写进日志。 */
    private static final Pattern IP_LIKE = Pattern.compile("[0-9a-fA-F:.]{3,64}");

    private final HttpProperties properties;

    public ClientIpResolver(HttpProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        if (properties.isTrustProxyHeaders()) {
            for (String header : PROXY_HEADERS) {
                Enumeration<String> values = request.getHeaders(header);
                if (values == null) {
                    continue;
                }
                while (values.hasMoreElements()) {
                    String value = values.nextElement();
                    if (Strings.isBlank(value)) {
                        continue;
                    }
                    // 一个头里可能有多个地址（X-Forwarded-For 常见）；取第一个合法地址。
                    for (String part : value.split(",")) {
                        String candidate = part.trim();
                        if (IP_LIKE.matcher(candidate).matches()) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return Strings.trimToEmpty(request.getRemoteAddr());
    }
}
