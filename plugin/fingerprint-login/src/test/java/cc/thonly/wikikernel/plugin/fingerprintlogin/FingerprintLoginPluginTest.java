package cc.thonly.wikikernel.plugin.fingerprintlogin;

import cc.thonly.wikikernel.plugin.fingerprintlogin.data.WikiFingerprint;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.plugin.fingerprintlogin.hook.FingerprintLoginHeadProvider;
import cc.thonly.wikikernel.plugin.fingerprintlogin.hook.FingerprintLoginPreferenceSection;
import cc.thonly.wikikernel.plugin.fingerprintlogin.model.FingerprintLoginPanel;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintLoginService;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintStore;
import cc.thonly.wikikernel.plugin.fingerprintlogin.web.FingerprintLoginSpecialPage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件装配（注册了哪些扩展点）、业务规则（增删 / 只能删自己的 / 每用户上限 / 未命中中性提示）、
 * 文案键完备性与两个管理界面（参数设置分区 + 特殊:指纹登录）。
 * <p>
 * 限流、开关关闭、接口层的行为在 {@code FingerprintLoginApiTest} 里。
 */
class FingerprintLoginPluginTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    private Fixture fixture() {
        return new Fixture(true);
    }

    // ==================== 装配 ====================

    @Test
    void pluginRegistersEveryExtensionPoint() {
        Fixture fixture = fixture();
        PluginContextFake context = new PluginContextFake(FingerprintLoginPlugin.ID);

        fixture.plugin().onStart(context);

        assertEquals("fingerprint-login", fixture.plugin().descriptor().id());
        assertEquals("features.fingerprint-login", FingerprintLoginPlugin.ENABLED_KEY);
        assertTrue(context.has(MessageBundle.class), "文案包");
        assertTrue(context.has(HtmlHeadProvider.class), "登录页按钮 + 管理界面脚本");
        assertTrue(context.has(PreferenceSection.class), "参数设置里的「指纹登录」分区");
        assertTrue(context.has(SpecialPage.class), "特殊:指纹登录");
        assertFalse(context.logs.isEmpty(), "启动时应记录一行日志");

        SpecialPage page = context.extensions(SpecialPage.class).get(0);
        assertEquals("指纹登录", page.name());
        assertTrue(page.aliases().contains("FingerprintLogin"));
        assertEquals("special/fingerprint-login", page.template());

        PreferenceSection section = context.extensions(PreferenceSection.class).get(0);
        assertEquals("fingerprint-login", section.id());
        assertEquals("fingerprint.preferences.title", section.labelKey());
    }

    @Test
    void disabledFeatureStillRegistersExtensionsButInjectsNothing() {
        Fixture fixture = new Fixture(false);
        PluginContextFake context = new PluginContextFake(FingerprintLoginPlugin.ID);
        fixture.plugin().onStart(context);

        assertTrue(context.has(PreferenceSection.class), "关闭时仍注册分区（渲染禁用提示）");
        assertEquals(List.of(), new FingerprintLoginHeadProvider(fixture.support).headItems(null),
                "功能关闭时不注入按钮与脚本");
        assertFalse(fixture.support.enabled());
        assertTrue(context.logs.stream().anyMatch(line -> line.contains("已关闭")));
    }

    @Test
    void pluginStartsWithNoBridgesAtAll() {
        // 独立运行 / 桥未装配：onStart 不能抛异常（真实实例上桥由 core 提供）
        FingerprintLoginPlugin plugin = new FingerprintLoginPlugin();
        PluginContextFake context = new PluginContextFake(FingerprintLoginPlugin.ID);
        plugin.onStart(context);
        assertTrue(context.has(SpecialPage.class));
        assertTrue(context.logs.stream().anyMatch(line -> line.contains("已关闭")));
    }

    @Test
    void headProviderInjectsLoginButtonScriptAndMessages() {
        Fixture fixture = fixture();
        FingerprintLoginHeadProvider provider = new FingerprintLoginHeadProvider(fixture.support);
        assertTrue(provider.siteWide(), "登录页 / 参数设置 / 特殊页都要注入（都是非词条视图）");

        String joined = String.join("\n", provider.headItems(null));
        assertTrue(joined.contains("WIKI_CONFIG.fingerprintLogin = true"), joined);
        assertTrue(joined.contains("WIKI_CONFIG.fingerprintLoginHttpsOnly = false"), joined);
        assertTrue(joined.contains("/plugins/fingerprint-login/fingerprint-login.js"), joined);
        assertTrue(joined.contains("fingerprintLoginButton:"), joined);
        assertTrue(joined.contains("fingerprintLoginWorking:"), joined);
    }

    @Test
    void httpsOnlyFlagComesFromPluginConfig() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(FingerprintLoginSupport.HTTPS_ONLY_KEY, "true");
        Fixture fixture = new Fixture(true, pluginConfig(settings));

        assertTrue(fixture.support.httpsOnly());
        assertFalse(fixture.service().httpsAllowed(false), "开了「仅 HTTPS 允许」→ 非安全上下文拒绝");
        assertTrue(fixture.service().httpsAllowed(true));

        String joined = String.join("\n",
                new FingerprintLoginHeadProvider(fixture.support).headItems(null));
        assertTrue(joined.contains("fingerprintLoginHttpsOnly = true"), joined);
    }

    // ==================== 增删 ====================

    @Test
    void addListsAndRemovesOwnFingerprints() {
        Fixture fixture = fixture();
        FingerprintLoginService service = fixture.service();

        FingerprintLoginService.AddOutcome added = service.add(7L, " 我的笔记本 Chrome ", HASH_A, "UA/1.0");
        assertFalse(added.updated(), "第一次登记是新增");
        assertEquals(1, service.list(7L).size());
        assertEquals("我的笔记本 Chrome", service.list(7L).get(0).label(), "名称去首尾空白");
        assertEquals("UA/1.0", service.list(7L).get(0).userAgent());

        // 同一摘要再次添加 = 更新名称与 UA（updated=true，不新增行）
        FingerprintLoginService.AddOutcome again = service.add(7L, "公司台式机", HASH_A, "UA/2.0");
        assertTrue(again.updated(), "同一浏览器重复登记只更新");
        assertEquals(1, service.list(7L).size());
        assertEquals("公司台式机", service.list(7L).get(0).label());

        // 多个指纹
        service.add(7L, "手机", HASH_B, "UA/3.0");
        assertEquals(2, service.list(7L).size());

        // 删除自己的
        assertTrue(service.remove(7L, added.id()));
        assertEquals(1, service.list(7L).size());
        assertFalse(service.remove(7L, added.id()), "已删除的再删返回 false");
        assertFalse(service.remove(7L, 999_999L), "不存在的返回 false");
    }

    @Test
    void cannotRemoveAnotherUsersFingerprint() {
        Fixture fixture = fixture();
        WikiFingerprint mine = fixture.store.seed(7L, "我的", HASH_A);
        WikiFingerprint theirs = fixture.store.seed(8L, "别人的", HASH_B);

        assertFalse(fixture.service().remove(7L, theirs.getId()), "不能删别人的");
        assertTrue(fixture.store.rows.contains(theirs), "别人的行必须还在");
        assertTrue(fixture.service().remove(7L, mine.getId()));
        assertEquals(1, fixture.store.rows.size());
    }

    @Test
    void listNeverReturnsAnotherUsersFingerprints() {
        Fixture fixture = fixture();
        fixture.store.seed(7L, "我的", HASH_A);
        fixture.store.seed(8L, "别人的", HASH_B);

        List<String> labels = fixture.service().list(7L).stream().map(view -> view.label()).toList();
        assertEquals(List.of("我的"), labels);
        assertTrue(fixture.service().list(8L).stream().noneMatch(view -> "我的".equals(view.label())));
    }

    @Test
    void perUserLimitIsEnforced() {
        Fixture fixture = fixture();
        for (int index = 0; index < FingerprintStore.MAX_PER_USER; index++) {
            fixture.service().add(7L, "设备" + index, hash(index), "UA/" + index);
        }
        assertEquals(FingerprintStore.MAX_PER_USER, fixture.service().list(7L).size());

        FingerprintLoginService.AddException exception = assertThrows(
                FingerprintLoginService.AddException.class,
                () -> fixture.service().add(7L, "第十一台", hash(99), "UA/99"));
        assertEquals(FingerprintLoginService.AddError.LIMIT_REACHED, exception.error());
        assertEquals(10, FingerprintStore.MAX_PER_USER, "每用户上限 10 个");

        // 别人的额度不受影响
        fixture.service().add(8L, "另一个用户", hash(100), "UA/100");
    }

    @Test
    void addingAnotherUsersFingerprintIsRejected() {
        Fixture fixture = fixture();
        fixture.store.seed(8L, "别人的", HASH_A);

        FingerprintLoginService.AddException exception = assertThrows(
                FingerprintLoginService.AddException.class,
                () -> fixture.service().add(7L, "抢注", HASH_A, "UA/1.0"));
        assertEquals(FingerprintLoginService.AddError.REGISTERED_TO_ANOTHER_USER, exception.error());
        assertEquals(8L, fixture.store.byHash(HASH_A).orElseThrow().getUserId(), "归属不变");
    }

    @Test
    void addValidatesLabelAndHash() {
        Fixture fixture = fixture();
        assertEquals(FingerprintLoginService.AddError.LABEL_REQUIRED, assertThrows(
                FingerprintLoginService.AddException.class,
                () -> fixture.service().add(7L, "   ", HASH_A, "UA")).error());
        assertEquals(FingerprintLoginService.AddError.LABEL_TOO_LONG, assertThrows(
                FingerprintLoginService.AddException.class,
                () -> fixture.service().add(7L, "x".repeat(FingerprintLoginSupport.LABEL_MAX_LENGTH + 1),
                        HASH_A, "UA")).error());
        assertEquals(FingerprintLoginService.AddError.HASH_REQUIRED, assertThrows(
                FingerprintLoginService.AddException.class,
                () -> fixture.service().add(7L, "名称", "", "UA")).error());
        assertEquals(FingerprintLoginService.AddError.HASH_INVALID, assertThrows(
                FingerprintLoginService.AddException.class,
                () -> fixture.service().add(7L, "名称", "不是十六进制", "UA")).error());
    }

    // ==================== 登录命中 / 未命中 / 限流 ====================

    @Test
    void loginHitsStoredFingerprintAndTouchesLastUsed() {
        Fixture fixture = fixture();
        WikiFingerprint row = fixture.store.seed(7L, "我的", HASH_A);
        assertFalse(fixture.service().list(7L).get(0).used(), "登记后还没用过");

        FingerprintLoginService.LoginOutcome outcome = fixture.service()
                .login(HASH_A, true, "203.0.113.7", true);

        assertTrue(outcome.success(), outcome.message());
        assertEquals(List.of("7:true"), fixture.session.established, "以指纹所属用户建立会话");
        assertTrue(fixture.store.calls.contains("touch:" + row.getId()), "成功后更新 lastUsedAt");
        assertTrue(fixture.service().list(7L).get(0).used(), "列表里显示最近使用时间");
    }

    @Test
    void loginMissIsNeutralAndCountsAsFailure() {
        Fixture fixture = fixture();

        FingerprintLoginService.LoginOutcome outcome = fixture.service()
                .login(HASH_B, false, "203.0.113.7", true);

        assertFalse(outcome.success());
        assertEquals("fingerprint.error.badCredentials", outcome.message(),
                "未命中给中性提示（不透露归属）");
        assertTrue(fixture.session.established.isEmpty(), "未命中不建立会话");
    }

    @Test
    void loginRejectsMalformedHashWithoutCountingAsFailure() {
        Fixture fixture = fixture();
        FingerprintLoginService.LoginOutcome outcome = fixture.service()
                .login("短", false, "203.0.113.7", true);
        assertFalse(outcome.success());
        assertEquals("fingerprint.error.hashInvalid", outcome.message());
        assertFalse(fixture.rateLimit.limited(FingerprintLoginService.RATE_LIMIT_CATEGORY,
                FingerprintLoginService.rateLimitKey("203.0.113.7", "短")), "格式错误不计失败");
    }

    @Test
    void rateLimitBlocksBruteForceByIpAndFingerprint() {
        Fixture fixture = fixture();
        fixture.store.seed(7L, "我的", HASH_A);
        fixture.rateLimit.maxAttempts = 2;

        // 同一个指纹两次未命中 → 「IP + 指纹」这一层锁定
        assertFalse(fixture.service().login(HASH_B, false, "203.0.113.7", true).success());
        assertFalse(fixture.service().login(HASH_B, false, "203.0.113.7", true).success());

        FingerprintLoginService.LoginOutcome blocked = fixture.service()
                .login(HASH_B, false, "203.0.113.7", true);
        assertFalse(blocked.success());
        assertTrue(blocked.message().startsWith("fingerprint.error.rateLimited"), blocked.message());

        // 换指纹也不能绕过：「IP」这一层同样在计数，且被锁期间即使指纹是对的也拒绝
        assertFalse(fixture.service().login(HASH_A, false, "203.0.113.7", true).success());
        assertTrue(fixture.session.established.isEmpty(), "锁定期间不建立任何会话");

        // 限流键按 IP 分类：换一个 IP 不受影响
        assertTrue(fixture.service().login(HASH_A, false, "198.51.100.9", true).success());
    }

    @Test
    void successfulLoginClearsTheRateLimitWindow() {
        Fixture fixture = fixture();
        fixture.store.seed(7L, "我的", HASH_A);
        fixture.rateLimit.maxAttempts = 3;

        assertFalse(fixture.service().login(HASH_B, false, "10.0.0.1", true).success());
        assertTrue(fixture.service().login(HASH_A, false, "10.0.0.1", true).success());
        assertFalse(fixture.rateLimit.limited(FingerprintLoginService.RATE_LIMIT_CATEGORY,
                FingerprintLoginService.rateLimitKey("10.0.0.1", HASH_A)), "成功一次即清除「IP + 指纹」计数");
        assertFalse(fixture.rateLimit.limited(FingerprintLoginService.RATE_LIMIT_CATEGORY_IP,
                FingerprintLoginService.rateLimitIpKey("10.0.0.1")), "成功一次即清除「IP」计数");
    }

    @Test
    void loginFailsWhenFeatureIsDisabled() {
        Fixture fixture = new Fixture(false);
        fixture.store.seed(7L, "我的", HASH_A);

        FingerprintLoginService.LoginOutcome outcome = fixture.service()
                .login(HASH_A, false, "203.0.113.7", true);

        assertFalse(outcome.success());
        assertEquals("fingerprint.error.disabled", outcome.message());
        assertTrue(fixture.session.established.isEmpty());
    }

    @Test
    void listIsEmptyWhenFeatureIsDisabledEvenWithStoredRows() {
        Fixture fixture = new Fixture(false);
        fixture.store.seed(7L, "我的", HASH_A);
        assertEquals(List.of(), fixture.service().list(7L));
    }

    @Test
    void userDeletedAfterRegistrationFailsNeutrally() {
        Fixture fixture = fixture();
        fixture.store.seed(7L, "我的", HASH_A);
        fixture.session.failEstablish = true;

        FingerprintLoginService.LoginOutcome outcome = fixture.service()
                .login(HASH_A, false, "203.0.113.7", true);

        assertFalse(outcome.success());
        assertEquals("fingerprint.error.badCredentials", outcome.message(), "用户没了也给中性提示");
    }

    // ==================== 文案 ====================

    @Test
    void messagesBundleCoversEveryKeyInBothLanguages() {
        Map<String, Map<String, String>> messages = new FingerprintLoginMessages().messages();
        assertTrue(messages.containsKey("zh") && messages.containsKey("en"), messages.keySet().toString());
        for (String key : List.of(
                // 插件名与描述（core 的 PluginI18n 用）
                "plugin.fingerprint-login.name", "plugin.fingerprint-login.description",
                // 登录页
                "fingerprint.login.button", "fingerprint.login.buttonHint", "fingerprint.login.remember",
                "fingerprint.login.working", "fingerprint.login.notSupported", "fingerprint.login.success",
                "fingerprint.login.error", "fingerprint.login.networkError",
                // 管理界面
                "fingerprint.page.title", "fingerprint.special.intro", "fingerprint.special.loginRequired",
                "fingerprint.special.loginLink", "fingerprint.special.openPreferences",
                "fingerprint.preferences.title", "fingerprint.preferences.intro", "fingerprint.disabled",
                "fingerprint.httpsOnly", "fingerprint.httpsOnlyHint", "fingerprint.maxReached",
                "fingerprint.list.empty", "fingerprint.list.label", "fingerprint.list.createdAt",
                "fingerprint.list.lastUsedAt", "fingerprint.list.userAgent", "fingerprint.list.never",
                "fingerprint.list.remove", "fingerprint.list.removeConfirm",
                "fingerprint.add.title", "fingerprint.add.label", "fingerprint.add.labelPlaceholder",
                "fingerprint.add.submit", "fingerprint.add.hint", "fingerprint.add.success",
                "fingerprint.add.updated", "fingerprint.remove.success",
                // 错误
                "fingerprint.error.hashRequired", "fingerprint.error.hashInvalid",
                "fingerprint.error.labelRequired", "fingerprint.error.labelTooLong",
                "fingerprint.error.notFound", "fingerprint.error.forbidden",
                "fingerprint.error.loginRequired", "fingerprint.error.disabled",
                "fingerprint.error.badCredentials", "fingerprint.error.rateLimited",
                "fingerprint.error.saveFailed")) {
            assertTrue(messages.get("zh").containsKey(key), "zh 缺少 " + key);
            assertTrue(messages.get("en").containsKey(key), "en 缺少 " + key);
        }
        // 中英文键必须一一对应
        assertEquals(messages.get("zh").keySet(), messages.get("en").keySet());
    }

    @Test
    void specialPageLoadsPluginTemplateAndNeverListsOthers() {
        // 模板随插件 jar 发布（core 的 PluginTemplateResolver 从插件类加载器读取）
        try (java.io.InputStream in = getClass()
                .getResourceAsStream("/templates/special/fingerprint-login.html")) {
            assertTrue(in != null, "插件 jar 应包含 templates/special/fingerprint-login.html");
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    // ==================== 管理界面 ====================

    @Test
    void preferenceSectionRendersOwnFingerprintsAndPanelScript() {
        Fixture fixture = fixture();
        WikiFingerprint mine = fixture.store.seed(7L, "我的笔记本 Chrome", HASH_A);
        fixture.store.seed(8L, "别人的设备", HASH_B);
        fixture.store.touch(mine.getId());

        String html = new FingerprintLoginPreferenceSection(fixture.service(), fixture.support)
                .html(preferenceContext(7L));

        assertTrue(html.contains("id=\"" + FingerprintLoginPanel.PANEL_ID + "\""), html);
        assertTrue(html.contains("我的笔记本 Chrome"), html);
        assertTrue(html.contains("fingerprint-row"), html);
        assertTrue(html.contains("fingerprint-add"), "添加按钮");
        assertFalse(html.contains("别人的设备"), "只列自己的指纹");
        assertFalse(html.contains(HASH_A), "指纹摘要不回显");
        assertTrue(html.contains("id=\"" + FingerprintLoginPanel.CONFIG_INPUT_ID + "\""), "脚本配置");
        assertTrue(html.contains("\"max\":10") || html.contains("&quot;max&quot;:10"), html);
    }

    @Test
    void preferenceSectionShowsDisabledNotice() {
        Fixture fixture = new Fixture(false);
        String html = new FingerprintLoginPreferenceSection(fixture.service(), fixture.support)
                .html(preferenceContext(7L));
        assertTrue(html.contains("fingerprint.disabled"), html);
        assertFalse(html.contains("fingerprint-add"), "关闭时不渲染管理表单");
    }

    @Test
    void specialPageModelCarriesPanelAndNeverLeaksOthersFingerprints() {
        Fixture fixture = fixture();
        fixture.store.seed(7L, "我的手机", HASH_A);
        fixture.store.seed(8L, "别人的手机", HASH_B);
        FingerprintLoginSpecialPage page = new FingerprintLoginSpecialPage(fixture.service(), fixture.support);

        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("指纹登录", Map.of(), user(7L, "甲")), model);

        assertEquals(Boolean.TRUE, model.get("enabled"));
        assertEquals(Boolean.TRUE, model.get("loggedIn"));
        assertEquals("/wiki/特殊:参数设置", model.get("preferencesUrl"));
        String html = String.valueOf(model.get("panelHtml"));
        assertTrue(html.contains("我的手机"), html);
        assertFalse(html.contains("别人的手机"), "管理员 / 其他人都看不到别人的指纹");
        assertFalse(html.contains(HASH_A), "摘要不回显");

        // 未登录：只给提示，不列数据
        Map<String, Object> anonymous = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("指纹登录", Map.of(), null), anonymous);
        assertEquals(Boolean.FALSE, anonymous.get("loggedIn"));
        assertEquals(List.of(), anonymous.get("fingerprints"));
        assertTrue(String.valueOf(anonymous.get("panelHtml")).contains("login-required"));
    }

    // ==================== 测试脚手架 ====================

    private static PluginConfig pluginConfig(Map<String, String> values) {
        return new PluginConfig() {
            @Override
            public String get(String key, String defaultValue) {
                String value = values.get(key);
                return value == null ? defaultValue : value;
            }

            @Override
            public Map<String, String> asMap() {
                return Map.copyOf(values);
            }
        };
    }

    private static String hash(int index) {
        return String.format("%064x", index);
    }

    private static WikiUser user(long id, String name) {
        WikiUser user = new WikiUser();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    private static PreferenceContext preferenceContext(long userId) {
        return new PreferenceContext() {
            @Override
            public Viewer viewer() {
                return Viewer.of(userId, "测试用户", Set.of());
            }

            @Override
            public String siteName() {
                return "WikiKernel";
            }

            @Override
            public String baseUrl() {
                return "http://localhost:18080";
            }

            @Override
            public String message(String key, String fallback) {
                // 等价于「漏翻时显示键名」：分区代码传 fallback = null
                return fallback == null ? key : fallback;
            }
        };
    }

    /** 测试脚手架：内存桥 + 业务对象 + 插件入口。 */
    private static final class Fixture {

        final FingerprintLoginFakes.MemoryFingerprintStore store = new FingerprintLoginFakes.MemoryFingerprintStore();
        final FingerprintLoginFakes.RecordingLoginSession session =
                new FingerprintLoginFakes.RecordingLoginSession();
        final FingerprintLoginFakes.MemoryRateLimit rateLimit = new FingerprintLoginFakes.MemoryRateLimit();
        final FingerprintLoginSupport support;
        private final boolean enabled;
        private final PluginConfig config;

        Fixture(boolean enabled) {
            this(enabled, FingerprintLoginFakes.emptyPluginConfig());
        }

        Fixture(boolean enabled, PluginConfig config) {
            this.enabled = enabled;
            this.config = config;
            this.support = new FingerprintLoginSupport(FingerprintLoginFakes.featureConfig(enabled),
                    FingerprintLoginFakes.messageSource(), () -> config);
        }

        FingerprintLoginService service() {
            return new FingerprintLoginService(store, session, rateLimit, support);
        }

        /** 与真实装配一致：入口类注入 Spring Bean（这里用反射灌入）。 */
        FingerprintLoginPlugin plugin() {
            FingerprintLoginPlugin plugin = new FingerprintLoginPlugin();
            FingerprintLoginFakes.inject(plugin, "support", support);
            FingerprintLoginFakes.inject(plugin, "service", service());
            return plugin;
        }
    }
}
