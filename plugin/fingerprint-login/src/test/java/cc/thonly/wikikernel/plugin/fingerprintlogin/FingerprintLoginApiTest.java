package cc.thonly.wikikernel.plugin.fingerprintlogin;

import cc.thonly.wikikernel.plugin.fingerprintlogin.data.WikiFingerprint;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintLoginService;
import cc.thonly.wikikernel.plugin.fingerprintlogin.web.FingerprintLoginApiController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口层：{@code POST /api/fingerprint-login}（命中 / 未命中 / 限流 / 开关关闭）、
 * {@code GET /list}、{@code POST /add}、{@code DELETE /remove} 的鉴权与错误码，
 * 以及「构造器依赖都是 Spring Bean」这条硬要求（真实实例上组件扫描创建控制器）。
 */
class FingerprintLoginApiTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void controllerEndpointsAndDependenciesAreAnnotated() {
        assertNotNull(FingerprintLoginApiController.class.getAnnotation(RestController.class),
                "@RestController（core 的 PluginWebRegistrar 只注册带 @Controller 的 Bean）");
        RequestMapping root = FingerprintLoginApiController.class.getAnnotation(RequestMapping.class);
        assertEquals(List.of("/api/fingerprint-login"), List.of(root.value()),
                "与登录页脚本 / 管理界面约定的路径一致");

        assertMapping("login", PostMapping.class);
        assertMapping("list", GetMapping.class, "/list");
        assertMapping("add", PostMapping.class, "/add");
        assertMapping("remove", DeleteMapping.class, "/remove");

        // 构造器依赖必须是 Spring Bean（core 的 PluginRuntimeLoadTest 覆盖真实装配）
        var constructor = FingerprintLoginApiController.class.getConstructors()[0];
        assertEquals(List.of(FingerprintLoginService.class, FingerprintLoginSupport.class, CurrentUser.class),
                List.of(constructor.getParameterTypes()));
    }

    @Test
    void secureBrowserRegistrationIssuesAndRotatesPrivateCredential() {
        Fixture fixture = new Fixture(true);
        var enrollHeaders = new MockHttpServletResponse();
        var enrolled = fixture.controller(7L).add("我的笔记本", secureRequest(), enrollHeaders);
        assertEquals(200, fixture.body(enrolled).get("code"));
        Cookie cookie = credential(enrollHeaders);
        String header = enrollHeaders.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(header.contains("; Secure") && header.contains("; HttpOnly") && header.contains("SameSite=Strict"));
        assertTrue(header.startsWith("__Host-WikiKernelDeviceLogin=") && header.contains("Path=/"));
        assertFalse(String.valueOf(enrolled.getBody()).contains(cookie.getValue()));
        assertFalse(fixture.store.rows.getFirst().getFingerprintHash().contains(cookie.getValue()));
        assertEquals(7L, fixture.store.rows.getFirst().getUserId());

        var loginHeaders = new MockHttpServletResponse();
        var authenticated = fixture.controller(0L).login("1", requestWith(cookie), loginHeaders);
        assertEquals(200, fixture.body(authenticated).get("code"));
        assertEquals(List.of("7:true"), fixture.session.established);
        Cookie next = credential(loginHeaders);
        assertNotEquals(cookie.getValue(), next.getValue());

        assertEquals(401, fixture.body(fixture.controller(0L).login("0", requestWith(cookie), new MockHttpServletResponse())).get("code"));
        assertEquals(200, fixture.body(fixture.controller(0L).login("0", requestWith(next), new MockHttpServletResponse())).get("code"));
        assertEquals(List.of("7:true", "7:false"), fixture.session.established);
    }

    @Test
    void legacyEntriesStayInManagementButCannotAuthenticate() {
        Fixture fixture = new Fixture(true);
        fixture.store.seed(7L, "旧浏览器", HASH_A);
        var request = secureRequest();
        request.setParameter("fingerprint", HASH_A);
        assertEquals(401, fixture.body(fixture.controller(0L).login("0", request, new MockHttpServletResponse())).get("code"));
        assertTrue(fixture.session.established.isEmpty());
        assertTrue(String.valueOf(fixture.controller(7L).list().getBody()).contains("旧浏览器"));
        assertFalse(String.valueOf(fixture.controller(7L).list().getBody()).contains(HASH_A));
    }

    @Test
    void expiredAndRemovedCredentialsCannotEstablishSession() {
        Fixture fixture = new Fixture(true);
        var expiredHeaders = new MockHttpServletResponse();
        fixture.controller(7L).add("已过期", secureRequest(), expiredHeaders);
        var row = fixture.store.rows.getFirst();
        row.setCreatedAt(java.time.LocalDateTime.now().minusDays(31));
        assertEquals(401, fixture.body(fixture.controller(0L).login("0", requestWith(credential(expiredHeaders)), new MockHttpServletResponse())).get("code"));

        var activeHeaders = new MockHttpServletResponse();
        fixture.controller(7L).add("待撤销", secureRequest(), activeHeaders);
        long id = fixture.store.rows.getLast().getId();
        assertEquals(404, fixture.body(fixture.controller(8L).remove(Long.toString(id))).get("code"));
        assertEquals(200, fixture.body(fixture.controller(7L).remove(Long.toString(id))).get("code"));
        assertEquals(401, fixture.body(fixture.controller(0L).login("0", requestWith(credential(activeHeaders)), new MockHttpServletResponse())).get("code"));
        assertTrue(fixture.session.established.isEmpty());
    }

    @Test
    void publicHttpCannotIssueOrConsumeCredentials() {
        Fixture fixture = new Fixture(true);
        var request = secureRequest();
        request.setSecure(false);
        assertEquals(403, fixture.body(fixture.controller(7L).add("我的", request, new MockHttpServletResponse())).get("code"));
        assertEquals(401, fixture.body(fixture.controller(0L).login("0", request, new MockHttpServletResponse())).get("code"));
        request.setServerName("localhost");
        assertEquals(403, fixture.body(fixture.controller(7L).add("我的", request, new MockHttpServletResponse())).get("code"));

        request.setRemoteAddr("127.0.0.1");
        var localHeaders = new MockHttpServletResponse();
        var result = fixture.controller(7L).add("本机测试", request, localHeaders);
        assertEquals(200, fixture.body(result).get("code"));
        request.setCookies(credential(localHeaders));
        assertEquals(200, fixture.body(fixture.controller(0L).login("0", request, new MockHttpServletResponse())).get("code"));
    }

    @Test
    void disabledFeatureAndAnonymousManagementFailClosed() {
        var disabled = new Fixture(false);
        assertEquals(401, disabled.body(disabled.controller(0L).login("0", secureRequest(), new MockHttpServletResponse())).get("code"));
        assertEquals(403, disabled.body(disabled.controller(7L).add("设备", secureRequest(), new MockHttpServletResponse())).get("code"));
        assertEquals(403, disabled.body(disabled.controller(7L).list()).get("code"));
        assertEquals(403, disabled.body(disabled.controller(7L).remove("1")).get("code"));

        var enabled = new Fixture(true);
        assertEquals(401, enabled.body(enabled.controller(0L).add("设备", secureRequest(), new MockHttpServletResponse())).get("code"));
        assertEquals(401, enabled.body(enabled.controller(0L).list()).get("code"));
        assertEquals(401, enabled.body(enabled.controller(0L).remove("1")).get("code"));
        assertTrue(enabled.store.rows.isEmpty());
    }

    private static MockHttpServletRequest requestWith(Cookie cookie) {
        var request = secureRequest();
        request.setCookies(cookie);
        return request;
    }

    private static Cookie credential(MockHttpServletResponse response) {
        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(header);
        String[] pair = header.split(";", 2)[0].split("=", 2);
        return new Cookie(pair[0], pair[1]);
    }

    // ==================== 工具 ====================

    private static Map<String, Object> fixtureBody(ResponseEntity<Map<String, Object>> response) {
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private static void assertMapping(String methodName, Class<? extends java.lang.annotation.Annotation> annotation,
                                      String... paths) {
        Method method = Arrays.stream(FingerprintLoginApiController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到方法 " + methodName));
        var mapping = AnnotatedElementUtils.findMergedAnnotation(method, annotation);
        assertNotNull(mapping, methodName + " 缺少 " + annotation.getSimpleName());
        assertEquals(List.of(paths), List.of(pathsOf(mapping)), methodName + " 的路径");
    }

    private static String[] pathsOf(java.lang.annotation.Annotation mapping) {
        if (mapping instanceof PostMapping post) {
            return post.value();
        }
        if (mapping instanceof GetMapping get) {
            return get.value();
        }
        if (mapping instanceof DeleteMapping delete) {
            return delete.value();
        }
        return new String[0];
    }

    private static MockHttpServletRequest secureRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setServerName("wiki.example.org");
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    /** 测试脚手架：内存桥 + 业务对象 + 控制器。 */
    private static final class Fixture {

        final FingerprintLoginFakes.MemoryFingerprintStore store = new FingerprintLoginFakes.MemoryFingerprintStore();
        final FingerprintLoginFakes.RecordingLoginSession session =
                new FingerprintLoginFakes.RecordingLoginSession();
        final FingerprintLoginFakes.MemoryRateLimit rateLimit = new FingerprintLoginFakes.MemoryRateLimit();
        final FingerprintLoginSupport support;

        Fixture(boolean enabled) {
            this.support = new FingerprintLoginSupport(FingerprintLoginFakes.featureConfig(enabled),
                    FingerprintLoginFakes.messageSource());
        }

        FingerprintLoginService service() {
            return new FingerprintLoginService(store, session, rateLimit, support);
        }

        FingerprintLoginApiController controller(long viewerId) {
            return new FingerprintLoginApiController(service(), support,
                    FingerprintLoginFakes.currentUser(viewerId, "甲"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
            assertNotNull(response.getBody());
            return response.getBody();
        }
    }
}
