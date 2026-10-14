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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匿名（无会话）访问带 {@code th:action} 表单的页面：真实容器下必须一次性完整渲染。
 * <p>
 * 回归：页面渲染到表单时响应已因超过默认缓冲（8KB）而提交，Spring Security 创建会话
 * 写 CSRF token 抛 {@code Cannot create a session after the response has been committed}，
 * 导致错误页被追加到页面尾部（出现第二个 {@code <html>}，文案「出错 200」）。
 * MockMvc 不模拟缓冲提交，覆盖不到，所以这里用随机端口的真实 Tomcat。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnonymousFormPageTest {

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertRendered(String path, String formId) throws Exception {
        HttpResponse<String> response = get(path);
        String html = response.body();
        assertEquals(200, response.statusCode(), path + " 应返回 200");
        assertTrue(html.contains(formId), path + " 应包含表单 " + formId);
        assertFalse(html.contains("服务器返回了错误"), path + " 不应出现错误页（响应提交后创建会话失败）");
        assertEquals(1, html.split("<html", -1).length - 1, path + " 只应有一个 <html> 文档");
    }

    @Test
    void anonymousLoginPageRendersCompletely() throws Exception {
        assertRendered("/wiki/%E7%89%B9%E6%AE%8A:%E7%99%BB%E5%BD%95", "wiki-login-form");
    }

    @Test
    void anonymousRegisterPageRendersCompletely() throws Exception {
        assertRendered("/wiki/%E7%89%B9%E6%AE%8A:%E6%B3%A8%E5%86%8C", "wiki-register-form");
    }

    @Test
    void anonymousPasswordResetPageRendersCompletely() throws Exception {
        assertRendered("/wiki/%E7%89%B9%E6%AE%8A:%E9%87%8D%E7%BD%AE%E5%AF%86%E7%A0%81", "wiki-password-reset-request");
    }
}
