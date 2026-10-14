package cc.thonly.wikikernel.http;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.HttpProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 解析真实客户端 IP：
 * <ul>
 *     <li>{@code wikikernel.http.trust-proxy-headers=false}（默认）：直接用 {@code RemoteAddr}；</li>
 *     <li>true（部署在 CDN / 反向代理后面）：依次读取
 *     {@code CF-Connecting-IP}（Cloudflare）/ {@code True-Client-IP} / {@code X-Real-IP} /
 *     {@code X-Forwarded-For}（取第一个地址）。</li>
 * </ul>
 * 日志（{@code wiki_log.ip}）与用户核查都使用这里解析出的地址。
 */
@Component
public class ClientIpResolver {

    /** 代理 / CDN 转发头，按优先级排列。 */
    private static final List<String> PROXY_HEADERS = List.of(
            "CF-Connecting-IP", "True-Client-IP", "X-Real-IP", "X-Forwarded-For");

    /** 只接受「像 IP」的内容，防止把任意头文本写进日志。 */
    private static final Pattern IP_LIKE = Pattern.compile("[0-9a-fA-F:.]{3,64}");

    private final HttpProperties properties;

    public ClientIpResolver(HttpProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        if (properties.isTrustProxyHeaders()) {
            for (String header : PROXY_HEADERS) {
                String value = request.getHeader(header);
                if (Strings.isBlank(value)) {
                    continue;
                }
                String candidate = value.split(",")[0].trim();
                if (IP_LIKE.matcher(candidate).matches()) {
                    return candidate;
                }
            }
        }
        return Strings.trimToEmpty(request.getRemoteAddr());
    }
}
