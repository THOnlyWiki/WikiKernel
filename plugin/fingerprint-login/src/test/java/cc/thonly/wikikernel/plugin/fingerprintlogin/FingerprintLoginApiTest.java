package cc.thonly.wikikernel.plugin.fingerprintlogin;

import cc.thonly.wikikernel.plugin.fingerprintlogin.data.WikiFingerprint;
import cc.thonly.wikikernel.common.plugin.support.MapPluginConfig;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintLoginService;
import cc.thonly.wikikernel.plugin.fingerprintlogin.web.FingerprintLoginApiController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void loginSucceedsAndTouchesStoredFingerprint() {
        Fixture fixture = new Fixture(true);
        WikiFingerprint row = fixture.store.seed(7L, "我的", HASH_A);
        FingerprintLoginApiController controller = fixture.controller(0L);

        Map<String, Object> body = fixture.body(controller.login(HASH_A, "1", secureRequest()));

        assertEquals(200, body.get("code"));
        assertEquals(List.of("7:true"), fixture.session.established);
        assertTrue(fixture.store.calls.contains("touch:" + row.getId()));
    }

    @Test
    void loginMissReturnsNeutralUnauthorizedAndNeverLeaksOwnership() {
        Fixture fixture = new Fixture(true);
        fixture.store.seed(7L, "我的", HASH_A);
        FingerprintLoginApiController controller = fixture.controller(0L);

        Map<String, Object> body = fixture.body(controller.login(HASH_B, "0", secureRequest()));

        assertEquals(401, body.get("code"));
        assertEquals("fingerprint.error.badCredentials", body.get("message"),
                "中性提示：不透露哪个用户有这个指纹");
        assertTrue(fixture.session.established.isEmpty());
    }

    @Test
    void loginIsRateLimitedPerIpAndFingerprint() {
        Fixture fixture = new Fixture(true);
        fixture.store.seed(7L, "我的", HASH_A);
        fixture.rateLimit.maxAttempts = 2;
        FingerprintLoginApiController controller = fixture.controller(0L);

        // 同一指纹连续失败 → 「IP + 指纹」这一层锁定
        controller.login(HASH_B, "0", secureRequest());
        controller.login(HASH_B, "0", secureRequest());
        Map<String, Object> blocked = fixture.body(controller.login(HASH_B, "0", secureRequest()));

        assertEquals(401, blocked.get("code"));
        assertTrue(String.valueOf(blocked.get("message")).startsWith("fingerprint.error.rateLimited"),
                String.valueOf(blocked.get("message")));
        assertTrue(fixture.session.established.isEmpty(), "锁定期间不建立会话");

        // 换指纹也不能绕过：「IP」这一层同样计数
        assertEquals(401, fixture.body(controller.login(HASH_A, "0", secureRequest())).get("code"));

        // 换 IP 不受影响（限流键按 IP 分类）
        MockHttpServletRequest other = secureRequest();
        other.setRemoteAddr("198.51.100.9");
        assertEquals(200, fixture.body(controller.login(HASH_A, "0", other)).get("code"));
    }

    @Test
    void everyEndpointRefusesWhenFeatureIsDisabled() {
        Fixture fixture = new Fixture(false);
        fixture.store.seed(7L, "我的", HASH_A);
        FingerprintLoginApiController controller = fixture.controller(7L);

        Map<String, Object> login = fixture.body(controller.login(HASH_A, "0", secureRequest()));
        assertEquals(401, login.get("code"));
        assertEquals("fingerprint.error.disabled", login.get("message"));

        Map<String, Object> list = fixture.body(controller.list());
        assertEquals(403, list.get("code"));
        assertEquals("fingerprint.error.disabled", list.get("message"));

        Map<String, Object> add = fixture.body(controller.add(HASH_B, "名称", secureRequest()));
        assertEquals(403, add.get("code"));
        assertEquals("fingerprint.error.disabled", add.get("message"));

        Map<String, Object> remove = fixture.body(controller.remove("1"));
        assertEquals(403, remove.get("code"));
        assertEquals("fingerprint.error.disabled", remove.get("message"));

        assertTrue(fixture.store.rows.stream().noneMatch(row -> HASH_B.equals(row.getFingerprintHash())),
                "关闭时不写入任何指纹");
    }

    @Test
    void managementEndpointsRequireLogin() {
        Fixture fixture = new Fixture(true);
        FingerprintLoginApiController controller = fixture.controller(0L);

        assertEquals(401, fixture.body(controller.list()).get("code"));
        assertEquals(401, fixture.body(controller.add(HASH_A, "名称", secureRequest())).get("code"));
        assertEquals(401, fixture.body(controller.remove("1")).get("code"));
        assertTrue(fixture.store.rows.stream().noneMatch(row -> HASH_A.equals(row.getFingerprintHash())));
    }

    @Test
    void addStoresFingerprintOnlyForTheSessionUser() {
        Fixture fixture = new Fixture(true);
        fixture.store.seed(8L, "别人的", HASH_B);
        FingerprintLoginApiController controller = fixture.controller(7L);

        Map<String, Object> body = fixture.body(controller.add(HASH_A, "我的笔记本", secureRequest()));

        assertEquals(200, body.get("code"));
        WikiFingerprint stored = fixture.store.byHash(HASH_A).orElseThrow();
        assertEquals(7L, stored.getUserId(), "归属永远取自会话，不接受请求里的 userId");
        assertEquals("我的笔记本", stored.getLabel());

        // 抢注别人的指纹 → 中性提示 + 409
        Map<String, Object> conflict = fixture.body(controller.add(HASH_B, "抢注", secureRequest()));
        assertEquals(409, conflict.get("code"));
        assertEquals("fingerprint.error.forbidden", conflict.get("message"));
        assertEquals(8L, fixture.store.byHash(HASH_B).orElseThrow().getUserId(), "归属不变");
    }

    @Test
    void addReportsLimitAndValidationErrors() {
        Fixture fixture = new Fixture(true);
        FingerprintLoginApiController controller = fixture.controller(7L);

        assertEquals(400, fixture.body(controller.add(HASH_A, "  ", secureRequest())).get("code"));
        assertEquals(400, fixture.body(controller.add(HASH_A, "x".repeat(101), secureRequest())).get("code"));
        assertEquals(400, fixture.body(controller.add("", "名称", secureRequest())).get("code"));
        assertEquals(400, fixture.body(controller.add("不是摘要", "名称", secureRequest())).get("code"));

        for (int index = 0; index < 10; index++) {
            fixture.store.seed(7L, "设备" + index, String.format("%064x", index));
        }
        Map<String, Object> full = fixture.body(controller.add(HASH_A, "第十一台", secureRequest()));
        assertEquals(409, full.get("code"));
        assertEquals("fingerprint.maxReached", full.get("message"));
    }

    @Test
    void removeOnlyTouchesOwnRows() {
        Fixture fixture = new Fixture(true);
        WikiFingerprint mine = fixture.store.seed(7L, "我的", HASH_A);
        WikiFingerprint theirs = fixture.store.seed(8L, "别人的", HASH_B);
        FingerprintLoginApiController controller = fixture.controller(7L);

        Map<String, Object> denied = fixture.body(controller.remove(String.valueOf(theirs.getId())));
        assertEquals(404, denied.get("code"), "别人的指纹当作不存在（不泄露归属）");
        assertTrue(fixture.store.rows.contains(theirs));

        Map<String, Object> removed = fixture.body(controller.remove(String.valueOf(mine.getId())));
        assertEquals(200, removed.get("code"));
        assertFalse(fixture.store.rows.contains(mine));

        assertEquals(404, fixture.body(controller.remove("abc")).get("code"), "非法 id 当作不存在");
    }

    @Test
    void listReturnsOwnRowsWithoutFingerprintHash() {
        Fixture fixture = new Fixture(true);
        fixture.store.seed(7L, "我的", HASH_A);
        fixture.store.seed(8L, "别人的", HASH_B);
        FingerprintLoginApiController controller = fixture.controller(7L);

        Map<String, Object> body = fixture.body(controller.list());
        assertEquals(200, body.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("fingerprints");
        assertEquals(1, rows.size());
        assertEquals("我的", rows.get(0).get("label"));
        assertEquals(10, data.get("max"));
        assertFalse(String.valueOf(rows.get(0)).contains(HASH_A), "摘要不回显");
    }

    @Test
    void httpsOnlyConfigurationRejectsPlainHttp() {
        Fixture fixture = new Fixture(true, true);
        FingerprintLoginApiController controller = fixture.controller(7L);
        fixture.store.seed(7L, "我的", HASH_A);

        MockHttpServletRequest plain = new MockHttpServletRequest();
        plain.setServerName("wiki.example.org");
        plain.setRemoteAddr("203.0.113.7");

        Map<String, Object> login = fixture.body(controller.login(HASH_A, "0", plain));
        assertEquals(401, login.get("code"));
        assertEquals("fingerprint.httpsOnly", login.get("message"));
        assertTrue(fixture.session.established.isEmpty());

        Map<String, Object> add = fixture.body(controller.add(HASH_B, "名称", plain));
        assertEquals(403, add.get("code"));
        assertEquals("fingerprint.httpsOnly", add.get("message"));

        // localhost 视为安全上下文；HTTPS 请求也可以
        MockHttpServletRequest localhost = new MockHttpServletRequest();
        localhost.setServerName("localhost");
        localhost.setRemoteAddr("127.0.0.1");
        assertEquals(200, fixture.body(controller.login(HASH_A, "0", localhost)).get("code"));

        MockHttpServletRequest https = new MockHttpServletRequest();
        https.setServerName("wiki.example.org");
        https.setSecure(true);
        assertEquals(200, fixture.body(controller.login(HASH_A, "0", https)).get("code"));
    }

    @Test
    void missingBridgesFailClosedInsteadOfThrowing() {
        // 桥未装配（只会在插件被独立运行时出现）：接口拒绝服务，不抛 NPE
        FingerprintLoginSupport support = new FingerprintLoginSupport(FingerprintLoginFakes.featureConfig(true),
                FingerprintLoginFakes.messageSource(), () -> null);
        FingerprintLoginService service = new FingerprintLoginService(null, null, null, support);
        assertFalse(service.bridgesReady());

        FingerprintLoginApiController controller = new FingerprintLoginApiController(service, support,
                FingerprintLoginFakes.currentUser(7L, "甲"));
        assertEquals(401, fixtureBody(controller.login(HASH_A, "0", secureRequest())).get("code"));
        assertEquals(200, fixtureBody(controller.list()).get("code"), "列表退化成空");
        assertEquals(500, fixtureBody(controller.add(HASH_A, "名称", secureRequest())).get("code"),
                "桥缺失时登记拒绝服务");
        assertEquals(404, fixtureBody(controller.remove("1")).get("code"));
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
            this(enabled, false);
        }

        Fixture(boolean enabled, boolean httpsOnly) {
            Map<String, String> settings = new LinkedHashMap<>();
            settings.put(FingerprintLoginSupport.HTTPS_ONLY_KEY, Boolean.toString(httpsOnly));
            this.support = new FingerprintLoginSupport(FingerprintLoginFakes.featureConfig(enabled),
                    FingerprintLoginFakes.messageSource(), () -> new MapPluginConfig(settings));
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
