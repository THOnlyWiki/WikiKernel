package cc.thonly.wikikernel.plugin.aiagent.service;

import cc.thonly.wikikernel.plugin.aiagent.model.AiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 JSON HTTP 客户端（JDK HttpClient + Jackson 3），AI 的三家协议共用。
 * 失败统一抛出 {@link AiException}（带 HTTP 状态码，便于界面提示）。
 */
final class AiHttp {

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Duration timeout;

    AiHttp(ObjectMapper mapper, int timeoutSeconds) {
        this(mapper, timeoutSeconds, "");
    }

    /**
     * @param proxy 可选代理：{@code socks5://host:port} / {@code socks5h://host:port} / {@code http://host:port}
     */
    AiHttp(ObjectMapper mapper, int timeoutSeconds, String proxy) {
        this.mapper = mapper;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        configureProxy(builder, proxy);
        this.client = builder.build();
    }

    /** 支持 SOCKS5 / HTTP 代理（本机网络下 Gemini、Anthropic 等常需要）。 */
    private static void configureProxy(HttpClient.Builder builder, String proxy) {
        String value = proxy == null ? "" : proxy.trim();
        if (value.isEmpty()) {
            return;
        }
        String scheme = "http";
        String rest = value;
        int schemeEnd = value.indexOf("://");
        if (schemeEnd > 0) {
            scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            rest = value.substring(schemeEnd + 3);
        }
        int colon = rest.lastIndexOf(':');
        String host = colon > 0 ? rest.substring(0, colon) : rest;
        int port;
        try {
            port = colon > 0 ? Integer.parseInt(rest.substring(colon + 1)) : 1080;
        } catch (NumberFormatException exception) {
            throw new AiException("代理端口不合法：" + value);
        }
        // JDK 的 HttpClient 不支持 SOCKS：本机开一个 CONNECT 桥，桥再用 SOCKS5 出去
        if (scheme.startsWith("socks")) {
            try {
                Socks5Bridge bridge = Socks5Bridge.forProxy(host, port, scheme.endsWith("h"));
                builder.proxy(ProxySelector.of(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), bridge.port())));
                return;
            } catch (IOException exception) {
                throw new AiException("无法建立 SOCKS5 代理桥：" + exception.getMessage(), exception);
            }
        }
        builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
    }

    JsonNode postJson(String url, Map<String, String> headers, Object body) {
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (RuntimeException exception) {
            throw new AiException("请求体序列化失败：" + exception.getMessage(), exception);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        headers.forEach(builder::header);

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new AiException("无法连接 AI 服务（" + sanitize(url) + "）：" + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiException("AI 请求被中断");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AiException(response.statusCode(),
                    "AI 服务返回 HTTP " + response.statusCode() + "：" + brief(response.body()));
        }
        try {
            return mapper.readTree(response.body());
        } catch (RuntimeException exception) {
            throw new AiException("AI 服务返回的不是合法 JSON：" + brief(response.body()));
        }
    }

    /** 错误信息里的 URL 不能带出密钥（Gemini 等把 key 放在查询串里）。 */
    private static String sanitize(String url) {
        return url.replaceAll("(?i)([?&](?:key|api[_-]?key|access[_-]?token)=)[^&]*", "$1***");
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "…";
    }
}
