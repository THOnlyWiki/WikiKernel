package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.ActionContext;
import cc.thonly.wikikernel.common.extension.ActionGuard;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.ActionHookService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.PermissionService;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通用动作钩子（{@link ActionGuard}）的 core 侧接线（滥用过滤器用它拦移动 / 上传 / 删除，审核用它排队上传 / 删除）：
 * <ul>
 *     <li>没有任何插件时移动 / 上传 / 删除行为与迁移前完全一致；</li>
 *     <li>{@code BLOCK} → 动作被拒绝（API 返回 4xx + message），副作用不产生；</li>
 *     <li>{@code WARN} → 动作照常执行，message 回给调用方；</li>
 *     <li>{@code HANDLED} → core 不执行动作，当作成功返回（网页 {@code data} / API 动作对象里带上钩子给的附加数据）；</li>
 *     <li>上传动作的 {@link ActionContext#payload()} 是文件内容（钩子可以自行暂存）；</li>
 *     <li>钩子按 {@code order()} 升序、抛异常只记警告并跳过（动作照常执行）；</li>
 *     <li>上下文带动作名 / 目标 / 来源 / 提交者与动作相关数据。</li>
 * </ul>
 * 用假的 {@link PluginManager} 提供扩展点实现（core 测试不加载任何插件 jar）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PluginActionHookTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private FileService fileService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FakeExtensions extensions;

    @AfterEach
    void clearFakes() {
        extensions.clear();
    }

    @Test
    void withoutPluginsActionsAreUnchanged() throws Exception {
        WikiUser user = insertUser("动作钩子空实现用户" + System.nanoTime());
        String from = "动作钩子源页" + System.nanoTime();
        wikiPageService.editPage(user, from, "内容");

        mockMvc.perform(post("/api/pages/move").session(sessionOf(user))
                        .param("from", from).param("to", from + "新"))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(wikiPageService.getByTitle(from + "新").isPresent(), "没有插件时移动照常执行");
    }

    @Test
    void blockStopsTheActionAndReportsMessage() throws Exception {
        WikiUser user = insertUser("动作钩子拦截用户" + System.nanoTime());
        String from = "动作钩子拦截源" + System.nanoTime();
        wikiPageService.editPage(user, from, "内容");
        List<ActionContext> seen = new CopyOnWriteArrayList<>();
        extensions.guards.add(new ActionGuard() {
            @Override
            public Outcome beforeAction(ActionContext context) {
                seen.add(context);
                return ActionGuard.Outcome.block("动作被钩子拦截");
            }
        });

        mockMvc.perform(post("/api/pages/move").session(sessionOf(user))
                        .param("from", from).param("to", from + "新"))
                .andExpect(jsonPath("$.message").value(containsString("动作被钩子拦截")));
        assertTrue(wikiPageService.getByTitle(from).isPresent(), "被拦截的移动不应产生副作用");
        assertTrue(wikiPageService.getByTitle(from + "新").isEmpty(), "目标页面不应被创建");

        ActionContext context = seen.get(0);
        assertEquals("move", context.action());
        assertEquals(user.getUsername(), context.viewer().name());
        assertEquals(from, context.data().get("from"));
    }

    @Test
    void warnLetsTheActionThrough() throws Exception {
        WikiUser user = insertUser("动作钩子警告用户" + System.nanoTime());
        String from = "动作钩子警告源" + System.nanoTime();
        wikiPageService.editPage(user, from, "内容");
        extensions.guards.add(new ActionGuard() {
            @Override
            public Outcome beforeAction(ActionContext context) {
                return ActionGuard.Outcome.warn("留意这个动作");
            }
        });

        mockMvc.perform(post("/api/pages/move").session(sessionOf(user))
                        .param("from", from).param("to", from + "新"))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(wikiPageService.getByTitle(from + "新").isPresent(), "WARN 只提示，不拦动作");
    }

    @Test
    void uploadAndDeleteAreGuardedToo() throws Exception {
        WikiUser user = insertUser("动作钩子上传用户" + System.nanoTime());
        List<String> actions = new CopyOnWriteArrayList<>();
        extensions.guards.add(new ActionGuard() {
            @Override
            public Outcome beforeAction(ActionContext context) {
                actions.add(context.action());
                return context.action().equals("upload")
                        ? ActionGuard.Outcome.block("不许上传") : ActionGuard.Outcome.allow();
            }
        });

        mockMvc.perform(multipart("/api/files/upload").session(sessionOf(user))
                        .file(new MockMultipartFile("file", "钩子图" + System.nanoTime() + ".png",
                                "image/png", PNG)))
                .andExpect(jsonPath("$.message").value(containsString("不许上传")));
        assertTrue(actions.contains("upload"), actions.toString());
    }

    @Test
    void handledUploadStagesInsteadOfStoring() throws Exception {
        WikiUser user = insertUser("动作钩子已处理用户" + System.nanoTime());
        String name = "钩子待审图" + System.nanoTime() + ".png";
        List<ActionContext> seen = new CopyOnWriteArrayList<>();
        extensions.guards.add(new ActionGuard() {
            @Override
            public Outcome beforeAction(ActionContext context) {
                seen.add(context);
                // 审核插件就是这样：把文件自行暂存起来，并告诉 core「已处理」+ 前端要的数据
                return ActionGuard.Outcome.handled("文件已提交，等待审核。",
                        Map.of("pending", true, "name", context.target()));
            }
        });

        mockMvc.perform(multipart("/api/files/upload").session(sessionOf(user))
                        .file(new MockMultipartFile("file", name, "image/png", PNG))
                        .param("name", name))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(containsString("等待审核")))
                .andExpect(jsonPath("$.data.pending").value(true));
        assertTrue(fileService.findByName(name).isEmpty(), "HANDLED 时 core 不应写入正式存储");

        // 载荷：上传的字节原样交给钩子
        assertArrayEquals(PNG, seen.get(0).payload());
        assertEquals("upload", seen.get(0).action());
        assertEquals("文件:" + name, seen.get(0).target());
    }

    @Test
    void handledDeleteKeepsTheFile() throws Exception {
        WikiUser user = admin();
        String name = "钩子待删图" + System.nanoTime() + ".png";
        WikiFile file = fileService.store(user, name, "", PNG);
        extensions.guards.add(new ActionGuard() {
            @Override
            public Outcome beforeAction(ActionContext context) {
                return context.action().equals("delete")
                        ? ActionGuard.Outcome.handled("删除请求已提交，等待审核。") : ActionGuard.Outcome.allow();
            }
        });

        mockMvc.perform(post("/api/files/delete").session(sessionOf(user))
                        .param("id", String.valueOf(file.getId())))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(containsString("等待审核")));
        assertTrue(fileService.findByName(name).isPresent(), "HANDLED 时 core 不应执行删除");
    }

    @Test
    void apiUploadIsGuardedAndHandled() throws Exception {
        WikiUser user = insertUser("动作钩子API上传用户" + System.nanoTime(), "secret123");
        MockHttpSession session = apiLogin(user.getUsername(), "secret123");
        String csrf = csrfToken(session);
        String name = "钩子API图" + System.nanoTime() + ".png";
        List<ActionContext> seen = new CopyOnWriteArrayList<>();
        extensions.guards.add(new ActionGuard() {
            @Override
            public Outcome beforeAction(ActionContext context) {
                seen.add(context);
                return ActionGuard.Outcome.handled("文件已提交，等待审核。", Map.of("moderated", true));
            }
        });

        mockMvc.perform(multipart("/api.php").file(new MockMultipartFile("file", name, "image/png", PNG))
                        .session(session).param("action", "upload").param("filename", name)
                        .param("token", csrf))
                .andExpect(jsonPath("$.upload.result").value("Success"))
                .andExpect(jsonPath("$.upload.moderated").value(true));
        assertTrue(fileService.findByName(name).isEmpty(), "API 上传被 HANDLED 时不写正式存储");
        assertEquals("api", seen.get(0).source());
        assertArrayEquals(PNG, seen.get(0).payload());
    }

    @Test
    void guardOrderAndFailuresAreTolerated() throws Exception {
        WikiUser user = insertUser("动作钩子顺序用户" + System.nanoTime());
        String from = "动作钩子顺序源" + System.nanoTime();
        wikiPageService.editPage(user, from, "内容");
        List<String> called = new CopyOnWriteArrayList<>();
        extensions.guards.add(new ActionGuard() {
            @Override
            public int order() {
                return 5;
            }

            @Override
            public Outcome beforeAction(ActionContext context) {
                called.add("first");
                throw new IllegalStateException("插件坏了");
            }
        });
        extensions.guards.add(new ActionGuard() {
            @Override
            public int order() {
                return 10;
            }

            @Override
            public Outcome beforeAction(ActionContext context) {
                called.add("second");
                return ActionGuard.Outcome.allow();
            }
        });

        mockMvc.perform(post("/api/pages/move").session(sessionOf(user))
                        .param("from", from).param("to", from + "新"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(List.of("first", "second"), called, "按 order 升序执行，坏插件被跳过");
    }

    @Test
    void adminPagesStillRenderWithoutPlugins() throws Exception {
        // 钩子只影响动作，不影响页面渲染
        mockMvc.perform(get("/wiki/{title}", "特殊:权限配置").session(sessionOf(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"site-settings-form\"")));
    }

    // ==================== 工具与测试替身 ====================

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MockHttpSession apiLogin(String username, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String loginToken = json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "login"))
                .andReturn()).path("query").path("tokens").path("logintoken").asString();
        JsonNode login = json(mockMvc.perform(post("/api.php").session(session)
                        .param("action", "login").param("lgname", username).param("lgpassword", password)
                        .param("lgtoken", loginToken))
                .andReturn());
        assertEquals("Success", login.path("login").path("result").asString());
        return session;
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        return json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "csrf"))
                .andReturn()).path("query").path("tokens").path("csrftoken").asString();
    }

    private WikiUser admin() {
        WikiUser admin = insertUser("动作钩子管理员" + System.nanoTime(), "x");
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        return admin;
    }

    private WikiUser insertUser(String username) {
        return insertUser(username, "x");
    }

    private WikiUser insertUser(String username, String password) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword(cc.thonly.wikikernel.user.MediaWikiPassword.hash(password));
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }

    @TestConfiguration
    static class FakeConfig {

        @Bean
        FakeExtensions fakeExtensions() {
            return new FakeExtensions();
        }

        @Bean
        @Primary
        ActionHookService actionHookService(FakeExtensions extensions) {
            return new ActionHookService(extensions.manager());
        }
    }

    /** 可变的假扩展点集合（每个测试自己添加）。 */
    static class FakeExtensions {

        final List<ActionGuard> guards = new CopyOnWriteArrayList<>();

        void clear() {
            guards.clear();
        }

        PluginManager manager() {
            return new PluginManager(null, null, null, null) {
                @Override
                public <T extends Extension> List<T> extensions(Class<T> extensionPoint) {
                    List<? extends Extension> source;
                    if (extensionPoint == ActionGuard.class) {
                        source = guards;
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
