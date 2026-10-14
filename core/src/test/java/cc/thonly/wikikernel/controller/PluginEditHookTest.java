package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditFormContext;
import cc.thonly.wikikernel.common.extension.EditFormField;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.RegistrationContext;
import cc.thonly.wikikernel.common.extension.RegistrationFormField;
import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.EditHookService;
import cc.thonly.wikikernel.service.RegistrationHookService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 插件「编辑 / 注册钩子」平台能力的 core 侧接线（{@code EditGuard} / {@code EditFormField} /
 * {@code RegistrationGuard} / {@code RegistrationFormField}）：
 * <ul>
 *     <li>没有任何插件时编辑页与注册页与迁移前完全一致（没有额外字段、保存 / 注册不额外校验）；</li>
 *     <li>表单字段与提示按 order 插入编辑页 / 注册页；</li>
 *     <li>保存前钩子的四种结论：{@code ALLOW} 放行、{@code BLOCK} 回到编辑表单（保留已填内容与摘要）、
 *     {@code WARN} 保存后提示一次、{@code HANDLED} 不保存并按钩子给的跳转；</li>
 *     <li>钩子按 order 升序执行、{@code BLOCK} / {@code HANDLED} 立即短路；</li>
 *     <li>插件抛异常时只记警告并跳过（页面不 500）；</li>
 *     <li>来源区分：只拦网页（{@code source=web}）的钩子不影响 {@code api.php?action=edit}；</li>
 *     <li>注册前钩子 {@code BLOCK} → 400 + 钩子给的说明。</li>
 * </ul>
 * 用假的 {@link PluginManager} 提供扩展点实现（core 测试不加载任何插件 jar）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PluginEditHookTest {

    private static final String FAKE_FIELD_HTML = "<div class=\"fake-hook-field\">插件编辑页字段</div>";
    private static final String FAKE_NOTICE_HTML = "<span class=\"fake-hook-notice\">插件提示</span>";
    private static final String FAKE_REGISTER_FIELD_HTML =
            "<div class=\"fake-register-field\"><input type=\"text\" name=\"quizAnswer\"/></div>";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private UserService userService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FakeExtensions extensions;

    @AfterEach
    void clearFakes() {
        extensions.clear();
    }

    // ==================== 没有插件时：行为与迁移前一致 ====================

    @Test
    void withoutPluginsEditPageAndSaveAreUnchanged() throws Exception {
        String title = "钩子空实现页" + System.nanoTime();
        wikiPageService.editPage(null, title, "第一版");
        MockHttpSession session = sessionOf(insertUser("钩子空实现用户" + System.nanoTime()));

        String html = mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(html.contains("fake-hook-field"), "没有插件时编辑页没有额外字段");
        assertFalse(html.contains("edit-hook-error"), "没有插件时没有钩子错误提示");

        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("content", "没有插件的第二版"))
                .andExpect(status().is3xxRedirection());
        assertEquals("没有插件的第二版", latest(title), "没有插件时保存不额外校验");
    }

    @Test
    void withoutPluginsRegistrationHasNoExtraFieldAndNoGate() throws Exception {
        String html = mockMvc.perform(get("/wiki/{title}", "特殊:注册"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(html.contains("fake-register-field"), html);
        assertFalse(html.contains("name=\"registerAnswer\""), "没有插件时注册页没有问题字段");

        mockMvc.perform(post("/api/user/register")
                        .param("username", "钩子注册用户" + System.nanoTime())
                        .param("password", "secret123"))
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 表单字段与提示 ====================

    @Test
    void formFieldsAndNoticesAreRenderedOnEditPage() throws Exception {
        extensions.fields.add(field(0, FAKE_FIELD_HTML, FAKE_NOTICE_HTML));
        String title = "钩子字段页" + System.nanoTime();
        wikiPageService.editPage(null, title, "第一版");
        MockHttpSession session = sessionOf(insertUser("钩子字段用户" + System.nanoTime()));

        String html = mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("fake-hook-field"), "编辑页应插入插件字段");
        assertTrue(html.contains("fake-hook-notice"), "编辑页应显示插件提示");
    }

    // ==================== 保存前钩子的四种结论 ====================

    @Test
    void blockKeepsSubmittedContentAndSummary() throws Exception {
        extensions.fields.add(field(0, FAKE_FIELD_HTML, null));
        extensions.guards.add(guard(0, context -> EditGuard.Outcome.block("插件拒绝了这次保存")));
        String title = "钩子拒绝页" + System.nanoTime();
        wikiPageService.editPage(null, title, "第一版");
        MockHttpSession session = sessionOf(insertUser("钩子拒绝用户" + System.nanoTime()));

        String html = mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("content", "被拒绝的第二版")
                        .param("comment", "我的摘要"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("插件拒绝了这次保存"), "拒绝说明应显示在编辑页");
        assertTrue(html.contains("被拒绝的第二版"), "已填内容应保留");
        assertTrue(html.contains("我的摘要"), "已填摘要应保留");
        assertTrue(html.contains("fake-hook-field"), "拒绝后应重新渲染插件字段");
        assertEquals("第一版", latest(title), "被拒绝时不能保存");
    }

    @Test
    void warnSavesAndShowsTheWarningOnceOnTheTargetPage() throws Exception {
        extensions.guards.add(guard(0, context -> EditGuard.Outcome.warn("本站启用了审核提示")));
        String title = "钩子警告页" + System.nanoTime();
        wikiPageService.editPage(null, title, "第一版");
        MockHttpSession session = sessionOf(insertUser("钩子警告用户" + System.nanoTime()));

        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("content", "带警告的第二版"))
                .andExpect(status().is3xxRedirection());
        assertEquals("带警告的第二版", latest(title), "WARN 应放行并保存");

        mockMvc.perform(get("/wiki/{title}", title).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("edit-hook-warning")))
                .andExpect(content().string(containsString("本站启用了审核提示")));
        // 只提示一次：再次浏览不再显示
        mockMvc.perform(get("/wiki/{title}", title).session(session))
                .andExpect(content().string(not(containsString("本站启用了审核提示"))));
    }

    @Test
    void handledHookSkipsSavingAndFollowsItsRedirect() throws Exception {
        extensions.guards.add(guard(0, context -> EditGuard.Outcome.handled("/wiki/钩子跳转目标")));
        String title = "钩子自处理页" + System.nanoTime();
        wikiPageService.editPage(null, title, "第一版");
        MockHttpSession session = sessionOf(insertUser("钩子自处理用户" + System.nanoTime()));

        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("content", "不应该被保存的版本"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/wiki/钩子跳转目标"));
        assertEquals("第一版", latest(title), "HANDLED 时 core 不再保存");
    }

    @Test
    void guardsRunInOrderAndBlockingStopsTheChain() throws Exception {
        List<String> calls = new ArrayList<>();
        extensions.guards.add(guard(20, context -> {
            calls.add("第二");
            return EditGuard.Outcome.allow();
        }));
        extensions.guards.add(guard(10, context -> {
            calls.add("第一");
            return EditGuard.Outcome.block("先执行的钩子拦截");
        }));
        extensions.guards.add(guard(30, context -> {
            calls.add("第三");
            return EditGuard.Outcome.allow();
        }));
        String title = "钩子顺序页" + System.nanoTime();
        wikiPageService.editPage(null, title, "第一版");
        MockHttpSession session = sessionOf(insertUser("钩子顺序用户" + System.nanoTime()));

        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("content", "顺序测试"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("先执行的钩子拦截")));
        assertEquals(List.of("第一"), calls, "按 order 升序执行，BLOCK 后不再问后面的钩子");
        assertEquals("第一版", latest(title));
    }

    // ==================== 容错 ====================

    @Test
    void pluginExceptionsAreSkippedInsteadOfFailingTheRequest() throws Exception {
        extensions.fields.add(new EditFormField() {
            @Override
            public String html(EditFormContext context) {
                throw new IllegalStateException("坏插件字段");
            }
        });
        extensions.fields.add(field(0, FAKE_FIELD_HTML, null));
        extensions.guards.add(new EditGuard() {
            @Override
            public Outcome beforeSave(EditContext context) {
                throw new IllegalStateException("坏插件钩子");
            }
        });
        extensions.guards.add(guard(0, context -> EditGuard.Outcome.warn("异常之后仍然执行")));

        String title = "钩子容错页" + System.nanoTime();
        wikiPageService.editPage(null, title, "第一版");
        MockHttpSession session = sessionOf(insertUser("钩子容错用户" + System.nanoTime()));

        // 坏字段被跳过，好字段照常渲染
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("fake-hook-field")));

        // 坏钩子被跳过，后面的钩子仍然生效（保存成功 + 警告）
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("content", "容错后保存的版本"))
                .andExpect(status().is3xxRedirection());
        assertEquals("容错后保存的版本", latest(title));
        mockMvc.perform(get("/wiki/{title}", title).session(session))
                .andExpect(content().string(containsString("异常之后仍然执行")));
    }

    // ==================== 来源区分：web / api ====================

    @Test
    void webOnlyGuardBlocksTheFormButNotTheActionApi() throws Exception {
        extensions.guards.add(new EditGuard() {
            @Override
            public Outcome beforeSave(EditContext context) {
                return context.web() ? Outcome.block("只拦网页保存") : Outcome.allow();
            }
        });
        String title = "钩子来源页" + System.nanoTime();
        wikiPageService.editPage(null, title, "第一版");
        MockHttpSession webSession = sessionOf(insertUser("钩子来源用户" + System.nanoTime()));

        // 网页编辑表单（source=web）：被拦
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(webSession)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("content", "网页版本"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("只拦网页保存")));
        assertEquals("第一版", latest(title));

        // api.php?action=edit（source=api）：不受影响
        String username = "钩子API用户" + System.nanoTime();
        userService.createUser(username, "secret123", "", "");
        MockHttpSession apiSession = apiLogin(username, "secret123");
        String csrf = csrfToken(apiSession);
        mockMvc.perform(post("/api.php").session(apiSession)
                        .param("action", "edit").param("title", title)
                        .param("text", "API 版本").param("token", csrf))
                .andExpect(jsonPath("$.edit.result").value("Success"));
        assertEquals("API 版本", latest(title), "只拦网页的钩子不应影响 Action API");
    }

    // ==================== 注册钩子 ====================

    @Test
    void registrationFormFieldIsRenderedAndGuardBlocksWithMessage() throws Exception {
        extensions.registrationFields.add(context -> FAKE_REGISTER_FIELD_HTML);
        extensions.registrationGuards.add(new RegistrationGuard() {
            @Override
            public Outcome beforeRegister(RegistrationContext context) {
                return "答案".equals(context.parameters().get("quizAnswer"))
                        ? Outcome.allow() : Outcome.block("注册被插件拦下了");
            }
        });

        mockMvc.perform(get("/wiki/{title}", "特殊:注册"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("fake-register-field")));

        String name = "钩子问答用户" + System.nanoTime();
        mockMvc.perform(post("/api/user/register")
                        .param("username", name)
                        .param("password", "secret123")
                        .param("quizAnswer", "错的"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("注册被插件拦下了"));
        assertFalse(userService.isExisted(name), "被拦下时不应创建账号");

        mockMvc.perform(post("/api/user/register")
                        .param("username", name)
                        .param("password", "secret123")
                        .param("quizAnswer", "答案"))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(userService.isExisted(name));
    }

    // ==================== 工具与测试替身 ====================

    private String latest(String title) {
        return wikiPageService.getByTitle(title)
                .flatMap(page -> wikiPageService.getLatestContent(page.getId()))
                .orElse("");
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }

    private static EditFormField field(int order, String html, String notice) {
        return new EditFormField() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public String html(EditFormContext context) {
                return html;
            }

            @Override
            public String notice(EditFormContext context) {
                return notice;
            }
        };
    }

    private static EditGuard guard(int order, Function<EditContext, EditGuard.Outcome> body) {
        return new EditGuard() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public Outcome beforeSave(EditContext context) {
                return body.apply(context);
            }
        };
    }

    private MockHttpSession apiLogin(String username, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String loginToken = json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "login"))
                .andReturn()).path("query").path("tokens").path("logintoken").asString();
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "login").param("lgname", username)
                        .param("lgpassword", password).param("lgtoken", loginToken))
                .andExpect(jsonPath("$.login.result").value("Success"));
        return session;
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        return json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "csrf"))
                .andReturn()).path("query").path("tokens").path("csrftoken").asString();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 假的扩展点注册表 + 用 {@link Primary} 覆盖 core 的两个钩子调度 Bean：
     * core 测试不加载任何插件 jar，这里手动提供插件实现来验证接线。
     */
    @TestConfiguration
    static class FakeHooks {

        @Bean
        FakeExtensions fakeExtensions() {
            return new FakeExtensions();
        }

        @Bean
        @Primary
        EditHookService editHookService(FakeExtensions extensions) {
            return new EditHookService(extensions.manager());
        }

        @Bean
        @Primary
        RegistrationHookService registrationHookService(FakeExtensions extensions) {
            return new RegistrationHookService(extensions.manager());
        }
    }

    /** 可变的假扩展点集合（每个测试自己添加）。 */
    static class FakeExtensions {

        final List<EditGuard> guards = new CopyOnWriteArrayList<>();
        final List<EditFormField> fields = new CopyOnWriteArrayList<>();
        final List<RegistrationGuard> registrationGuards = new CopyOnWriteArrayList<>();
        final List<RegistrationFormField> registrationFields = new CopyOnWriteArrayList<>();

        void clear() {
            guards.clear();
            fields.clear();
            registrationGuards.clear();
            registrationFields.clear();
        }

        /** 只提供上述四类扩展点的 {@link PluginManager} 替身（不启动任何插件容器）。 */
        PluginManager manager() {
            return new PluginManager(null, null, null, null) {
                @Override
                public <T extends Extension> List<T> extensions(Class<T> extensionPoint) {
                    List<? extends Extension> source;
                    if (extensionPoint == EditGuard.class) {
                        source = guards;
                    } else if (extensionPoint == EditFormField.class) {
                        source = fields;
                    } else if (extensionPoint == RegistrationGuard.class) {
                        source = registrationGuards;
                    } else if (extensionPoint == RegistrationFormField.class) {
                        source = registrationFields;
                    } else {
                        return List.of();
                    }
                    List<T> result = new ArrayList<>(source.size());
                    for (Extension extension : source) {
                        result.add(extensionPoint.cast(extension));
                    }
                    return List.copyOf(result);
                }
            };
        }
    }
}
