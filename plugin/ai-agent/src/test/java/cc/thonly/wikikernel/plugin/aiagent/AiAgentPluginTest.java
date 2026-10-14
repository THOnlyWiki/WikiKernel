package cc.thonly.wikikernel.plugin.aiagent;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.plugin.aiagent.service.AiConfig;
import cc.thonly.wikikernel.plugin.aiagent.hook.AiAgentHeadProvider;
import cc.thonly.wikikernel.plugin.aiagent.hook.AiKeysPreferenceSection;
import cc.thonly.wikikernel.plugin.aiagent.web.AiAgentSpecialPage;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.messageresolver.IMessageResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件装配与界面：
 * 注册了哪些扩展点（文案 / 特殊页 / 参数设置分区 / 前端资源）、
 * {@code 特殊:AI代理} 的模型（未启用提示、无权限提示、{@code ?keys=1} 的站点密钥面板）、
 * 参数设置里「AI 代理」分区的 HTML（表单走 {@code /api/ai-keys}，密钥不回显）、前端资源 URL。
 */
class AiAgentPluginTest {

    /** 皮肤布局替身的模板名（{@code src/test/resources/templates/test-layout.html}）。 */
    private static final String LAYOUT_NAME = "test-layout";

    private AiAgentPlugin plugin(AiAgentFakes.Fixture fixture) {
        AiAgentPlugin plugin = new AiAgentPlugin();
        PluginContextFake.inject(plugin, "agentService", fixture.service);
        PluginContextFake.inject(plugin, "providerRegistry", fixture.registry);
        PluginContextFake.inject(plugin, "support", fixture.support);
        PluginContextFake.inject(plugin, "aiConfig", fixture.aiConfig);
        PluginContextFake.inject(plugin, "userStore", fixture.users);
        return plugin;
    }

    private AiAgentSpecialPage specialPage(AiAgentFakes.Fixture fixture) {
        return new AiAgentSpecialPage(fixture.service, fixture.registry, fixture.support, fixture.aiConfig,
                fixture.users);
    }

    private static WikiUser user(long id, String name) {
        WikiUser user = new WikiUser();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    @Test
    void pluginRegistersEveryExtensionPoint() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        PluginContextFake context = new PluginContextFake(AiAgentPlugin.ID);

        plugin(fixture).onStart(context);

        assertEquals("ai-agent", plugin(fixture).descriptor().id());
        assertTrue(context.has(MessageBundle.class), "文案包");
        assertTrue(context.has(SpecialPage.class), "特殊:AI代理");
        assertTrue(context.has(PreferenceSection.class), "参数设置里的「AI 代理」分区");
        assertTrue(context.has(HtmlHeadProvider.class), "前端脚本与样式");
        // 特殊页的关键元信息
        SpecialPage page = context.extensions(SpecialPage.class).get(0);
        assertEquals("AI代理", page.name());
        assertTrue(page.aliases().contains("AiAgent"));
        assertEquals("special/ai-agent", page.template());
        assertFalse(context.logs.isEmpty(), "启动时应记录一行日志");
    }

    @Test
    void specialPageShowsDisabledHintAndNoChatWithoutTheFeature() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        fixture.enabled(false);
        Map<String, Object> model = new LinkedHashMap<>();

        specialPage(fixture).populate(new SpecialPageRequest("AI代理", Map.of(),
                user(7, "代理甲")), model);

        assertEquals(Boolean.FALSE, model.get("enabled"));
        assertEquals(Boolean.FALSE, model.get("allowed"));
        assertEquals(Boolean.FALSE, model.get("chatVisible"), "未启用时不渲染对话界面");
        assertEquals(Boolean.FALSE, model.get("keysPanel"));
        assertFalse(((List<?>) model.get("providers")).isEmpty(), "厂商列表仍会给出（内置 Mock 恒可用）");
    }

    @Test
    void specialPageExposesProvidersAndPermissionHints() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        Viewer agent = fixture.agent("代理乙");
        Map<String, Object> model = new LinkedHashMap<>();

        specialPage(fixture).populate(new SpecialPageRequest("AI代理", Map.of(),
                user(agent.id(), agent.name())), model);

        assertEquals(Boolean.TRUE, model.get("enabled"));
        assertEquals(Boolean.TRUE, model.get("allowed"));
        assertEquals(Boolean.TRUE, model.get("loggedIn"));
        assertEquals(Boolean.FALSE, model.get("canManage"));
        assertEquals(Boolean.TRUE, model.get("chatVisible"));
        assertEquals(Boolean.TRUE, model.get("hasProvider"));
        assertEquals(Boolean.TRUE, model.get("maxStepsUnlimited"));
        assertEquals(AiAgentSupport.GROUP_AI_AGENT, model.get("agentGroup"));
        assertEquals("mock", model.get("defaultProvider"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) model.get("providers");
        assertEquals("mock", providers.get(0).get("id"));
        assertEquals("内置演示", providers.get(0).get("sourceLabel"));
    }

    @Test
    void specialPageRendersSiteKeysPanelForManagersOnly() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        Viewer admin = fixture.admin("管理员甲");

        // 普通用户带 ?keys=1 也拿不到站点密钥面板
        Map<String, Object> plain = new LinkedHashMap<>();
        specialPage(fixture).populate(new SpecialPageRequest("AI代理", Map.of("keys", "1"),
                user(fixture.user("普通乙").id(), "普通乙")), plain);
        assertEquals(Boolean.FALSE, plain.get("keysPanel"));

        Map<String, Object> model = new LinkedHashMap<>();
        specialPage(fixture).populate(new SpecialPageRequest("AI代理", Map.of("keys", "1"),
                user(admin.id(), admin.name())), model);

        assertEquals(Boolean.TRUE, model.get("canManage"));
        assertEquals(Boolean.TRUE, model.get("keysPanel"));
        assertEquals(Boolean.FALSE, model.get("chatVisible"), "密钥面板下不渲染对话界面");
        assertTrue(((List<?>) model.get("siteVendors")).size() >= 5, "站点密钥面板应列出内置厂商");
        assertEquals(Boolean.FALSE, model.get("allowNormalUsers"));
        assertEquals("/wiki/" + AiAgentSupport.encodeSegment("特殊:AI代理") + "?keys=1", model.get("keysUrl"));
    }

    @Test
    void preferenceSectionRendersKeyFormWithoutEchoingSecrets() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        Viewer user = fixture.user("参数用户");
        fixture.aiConfig.userEntries.put("deepseek",
                new AiConfig.Entry("deepseek", "sk-secret-4321", "", "", ""));
        AiKeysPreferenceSection section = new AiKeysPreferenceSection(fixture.registry, fixture.support);

        String html = section.html(new PreferenceContextStub(fixture, user));

        assertEquals("ai", section.id());
        assertEquals("preferences.tab.ai", section.labelKey());
        assertTrue(html.contains("data-keys-scope=\"user\""), html);
        // 保存脚本由 AiAgentHeadProvider 站点级注入一次（分区里不再重复引，避免执行两次）
        assertFalse(html.contains("<script"), html);
        assertTrue(html.contains("class=\"ai-key-row\" data-provider=\"deepseek\""), html);
        // 掩码里的「•」被 HTML 转义成 &bull;（浏览器显示一致）
        assertTrue(html.contains("&bull;&bull;&bull;&bull;4321"), "界面显示尾号：" + html);
        assertFalse(html.contains("sk-secret-4321"), "密钥不得回显到 HTML");
        assertFalse(html.contains("value=\"sk-secret"), html);
    }

    @Test
    void preferenceSectionShowsDisabledHintButStillAllowsKeys() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        fixture.enabled(false);
        AiKeysPreferenceSection section = new AiKeysPreferenceSection(fixture.registry, fixture.support);

        String html = section.html(new PreferenceContextStub(fixture, fixture.user("参数用户2")));

        assertTrue(html.contains("AI 代理功能当前未启用"), html);
        assertTrue(html.contains("data-keys-scope=\"user\""), "关闭时也能预先配置密钥");
    }

    @Test
    void headProviderInjectsPluginAssets() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        List<String> items = new AiAgentHeadProvider().headItems(new HeadContextStub());

        assertEquals(3, items.size());
        assertTrue(items.get(0).contains("/plugins/ai-agent/ai-agent.css"), items.get(0));
        assertTrue(items.stream().anyMatch(item -> item.contains("/plugins/ai-agent/ai-agent.js")));
        assertTrue(items.stream().anyMatch(item -> item.contains("/plugins/ai-agent/ai-keys.js")));
    }

    @Test
    void messageBundleCoversEveryAiKeyInBothLanguages() {
        Map<String, Map<String, String>> messages = new AiAgentMessages().messages();

        assertEquals(2, messages.size());
        Map<String, String> zh = messages.get("zh");
        Map<String, String> en = messages.get("en");
        assertEquals(zh.keySet(), en.keySet(), "中英文键必须一一对应");
        for (String key : List.of("special.aiAgent", "preferences.tab.ai", "preferences.ai.title",
                "aiAgent.disabledHint", "aiAgent.abuseBlocked", "aiAgent.editSummary", "aiAgent.applyAllDone",
                "settings.aiVendors", "settings.aiAgentAllowNormal", "aiAgent.editSummary")) {
            assertTrue(zh.containsKey(key), "缺少文案键：" + key);
        }
        // 水印标题仍属于 core（页面元数据渲染），插件不重复定义
        assertFalse(zh.containsKey("aiAgent.watermarkTitle"));
    }

    @Test
    void specialPageTemplateOnlyUsesMessagesFromTheBundle() throws Exception {
        String template = resource("/templates/special/ai-agent.html");
        Map<String, String> zh = new AiAgentMessages().messages().get("zh");
        // 模板里的 #{键} / #{键(参数)} 都必须能在插件文案包里解析（core 的模板渲染靠它）
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("#\\{([^}()]+)").matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            // core 的通用键（编辑器 / 通用按钮等）留在 core 的 messages*.properties 里，不在插件包里
            if (!zh.containsKey(key) && !key.startsWith("skin.") && !key.startsWith("common.")
                    && !key.startsWith("format.")) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "模板引用了插件文案包里没有的键：" + missing);
    }

    // ==================== 特殊页渲染（真模板 + 真 Thymeleaf；core 的皮肤布局用测试替身片段） ====================

    @Test
    void specialPageRendersDemoProvidersAndTheLockedPromptNotice() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        Viewer agent = fixture.agent("演示锁用户");

        String html = renderSpecialPage(fixture, agent, Map.of(), Locale.SIMPLIFIED_CHINESE);

        // 「哪些厂商是演示厂商」由 AiAgentSpecialPage 从目录（type=mock）注入，前端据此锁住提示词输入
        assertTrue(html.contains("data-demo-providers=\"mock\""), html);
        // 演示提示条 + 输入框 + 发送按钮（提示条默认隐藏，选中演示厂商时由 ai-agent.js 显示）
        assertTrue(html.contains("id=\"ai-agent-demo-notice\""), html);
        assertTrue(html.contains("id=\"ai-agent-input\""), html);
        assertTrue(html.contains("id=\"ai-agent-send\""), html);
        assertTrue(html.contains("输入已锁定"), "演示锁提示来自插件文案：" + html);
        // 厂商名来自插件 i18n：新名字在，旧标签不在
        assertTrue(html.contains("演示模式（固定回复）"), html);
        assertFalse(html.contains("Mock（内置演示）"), "页面里不应再有旧标签");
        assertFalse(html.contains("??aiAgent"), "页面里不应残留未解析的文案键");
        // 只有演示厂商可用（没配任何密钥的普通用户）时，默认选中的就是它 —— 打开页面即锁定态
        int option = html.indexOf("value=\"mock\"");
        assertTrue(option >= 0, html);
        String optionTag = html.substring(html.lastIndexOf('<', option), html.indexOf('>', option));
        assertTrue(optionTag.contains("selected"), "默认选中的应是演示厂商：" + optionTag);
    }

    @Test
    void specialPageRendersEnglishDemoTextForEnglishReaders() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        Viewer agent = fixture.agent("demo lock user");

        String html = renderSpecialPage(fixture, agent, Map.of(), Locale.ENGLISH);

        assertTrue(html.contains("Demo (canned replies)"), html);
        assertTrue(html.contains("input is locked"), html);
        assertFalse(html.contains("演示模式"), "英文界面不应出现中文厂商名");
    }

    @Test
    void siteKeysPanelShowsLocalizedVendorNamesAndHints() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        Viewer admin = fixture.admin("管理员甲");

        String html = renderSpecialPage(fixture, admin, Map.of(AiAgentSpecialPage.PARAM_KEYS, "1"),
                Locale.SIMPLIFIED_CHINESE);

        assertTrue(html.contains("演示模式（固定回复）"), "站点密钥面板的厂商名走插件 i18n：" + html);
        assertTrue(html.contains("不是真实 AI"), "站点密钥面板的厂商提示走插件 i18n");
        assertFalse(html.contains("aiAgent.vendor."), "页面里不应出现厂商文案键名");
    }

    @Test
    void preferenceSectionShowsLocalizedVendorNamesAndHints() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        AiKeysPreferenceSection section = new AiKeysPreferenceSection(fixture.registry, fixture.support);
        PreferenceContextStub context = new PreferenceContextStub(fixture, fixture.user("参数用户3"));

        try {
            LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
            String chinese = section.html(context);
            assertTrue(chinese.contains("演示模式（固定回复）"), chinese);
            assertTrue(chinese.contains("不是真实 AI"), chinese);

            LocaleContextHolder.setLocale(Locale.ENGLISH);
            String english = section.html(context);
            assertTrue(english.contains("Demo (canned replies)"), english);
            assertTrue(english.contains("not a real AI"), english);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    // ==================== 渲染与资源小助手 ====================

    /** 插件模板 / 脚本（插件 jar 里的资源）。 */
    private static String resource(String path) throws Exception {
        try (InputStream stream = AiAgentPluginTest.class.getResourceAsStream(path)) {
            assertTrue(stream != null, "插件资源应随 jar 提供：" + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 用**真模板 + 真 Thymeleaf** 渲染 {@code 特殊:AI代理}（页面模型来自 {@link AiAgentSpecialPage#populate}）：
     * core 的皮肤布局（{@code skin.layout}）换成 {@code src/test/resources/templates/test-layout.html} 里的片段，
     * 于是断言的是渲染出来的 HTML，而不是模板源码。
     */
    private String renderSpecialPage(AiAgentFakes.Fixture fixture, Viewer viewer, Map<String, String> parameters,
                                     Locale locale) {
        Map<String, Object> model = new LinkedHashMap<>();
        try {
            // 与运行时一致：页面模型（厂商名 / 提示）按**请求线程**的 Locale 解析（消息源走 LocaleContextHolder）
            LocaleContextHolder.setLocale(locale);
            specialPage(fixture).populate(new SpecialPageRequest("AI代理", parameters,
                    user(viewer.id(), viewer.name())), model);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
        model.put("title", "AI代理");
        model.put("skin", Map.of("layout", LAYOUT_NAME));

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolvers(Set.of(resolver));
        engine.setMessageResolver(bundleMessages(locale));
        // 页面里的 @{...} 是站内相对链接；测试里没有 Web 上下文，这里让链接构造器直接输出路径
        engine.setLinkBuilder(new StandardLinkBuilder() {

            @Override
            protected String computeContextPath(IExpressionContext context, String base,
                                                Map<String, Object> parameters) {
                return "";
            }
        });
        return engine.process("special/ai-agent", new Context(locale, model));
    }

    /** 测试用消息解析器：直接读插件自己的文案包（运行时由 core 的 PluginMessageSource 负责同样的查找）。 */
    private static IMessageResolver bundleMessages(Locale locale) {
        Map<String, Map<String, String>> messages = new AiAgentMessages().messages();
        Map<String, String> texts = messages.getOrDefault(locale.getLanguage(),
                messages.getOrDefault("zh", Map.of()));
        return new IMessageResolver() {

            @Override
            public String getName() {
                return "testBundle";
            }

            @Override
            public Integer getOrder() {
                return 1;
            }

            @Override
            public String resolveMessage(ITemplateContext context, Class<?> origin, String key,
                                         Object[] messageParameters) {
                String text = texts.get(key);
                if (text == null) {
                    return null;
                }
                return messageParameters == null || messageParameters.length == 0
                        ? text : MessageFormat.format(text, messageParameters);
            }

            @Override
            public String createAbsentMessageRepresentation(ITemplateContext context, Class<?> origin, String key,
                                                            Object[] messageParameters) {
                return "??" + key + "??";
            }
        };
    }

    // ==================== 上下文替身 ====================

    private static final class PreferenceContextStub implements PreferenceContext {

        private final AiAgentFakes.Fixture fixture;
        private final Viewer viewer;

        private PreferenceContextStub(AiAgentFakes.Fixture fixture, Viewer viewer) {
            this.fixture = fixture;
            this.viewer = viewer;
        }

        @Override
        public Viewer viewer() {
            return viewer;
        }

        @Override
        public String siteName() {
            return fixture.support.siteName();
        }

        @Override
        public String baseUrl() {
            return "http://localhost:8080";
        }

        @Override
        public String message(String key, String fallback) {
            return fixture.support.message(key, fallback);
        }
    }

    private static final class HeadContextStub implements HeadContext {

        @Override
        public long pageId() {
            return 1;
        }

        @Override
        public String pageTitle() {
            return "首页";
        }

        @Override
        public String path() {
            return "/wiki/首页";
        }

        @Override
        public Map<String, String> query() {
            return Map.of();
        }

        @Override
        public Map<String, String> properties() {
            return Map.of();
        }

        @Override
        public String siteName() {
            return "WikiKernel";
        }

        @Override
        public String baseUrl() {
            return "http://localhost:8080";
        }

        @Override
        public String fileUrl(String fileName) {
            return "";
        }

        @Override
        public int fileWidth(String fileName) {
            return 0;
        }

        @Override
        public int fileHeight(String fileName) {
            return 0;
        }
    }
}
