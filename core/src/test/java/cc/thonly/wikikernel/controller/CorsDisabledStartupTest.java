package cc.thonly.wikikernel.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 真实 servlet 容器（随机端口）下、CORS 默认关闭时的启动与响应：
 * 回归「FilterRegistrationBean 已禁用但 filter 为 null → Tomcat 启动失败」这个问题
 * （MockMvc 不启动容器，覆盖不到）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsDisabledStartupTest {

    @LocalServerPort
    private int port;

    @Test
    void startsAndServesWithoutCorsHeaders() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/version"))
                .header("Origin", "https://example.com")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                "默认关闭时不应有跨域响应头");
    }
}
