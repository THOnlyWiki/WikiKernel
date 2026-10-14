package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.PersonalToolbarContext;
import cc.thonly.wikikernel.common.extension.PersonalToolbarHook;
import cc.thonly.wikikernel.common.extension.UserProfileContext;
import cc.thonly.wikikernel.common.extension.UserProfileHook;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.PersonalToolbarHookService;
import cc.thonly.wikikernel.service.UserProfileHookService;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 插件「用户页资料卡 / 个人工具区」钩子（{@link UserProfileHook} / {@link PersonalToolbarHook}）的 core 侧接线：
 * <ul>
 *     <li>没有任何插件时资料卡用默认头像、没有额外链接，个人工具区与迁移前完全一致；</li>
 *     <li>资料卡头像取第一个非空返回值、额外链接 HTML 按 order 拼接进「贡献 | …」一行；</li>
 *     <li>个人工具条目按 order 升序追加在最后一项（退出）之后；</li>
 *     <li>插件抛异常时只记警告并跳过（页面不 500），其它插件照常生效；</li>
 *     <li>上下文内容：用户名 / 用户 ID（账号不存在为 0）/ 是否本人 / 当前用户 / 默认头像 /
 *     站点名 / 站点根地址；API 请求不询问这两类钩子。</li>
 * </ul>
 * 用假的 {@link PluginManager} 提供扩展点实现（core 测试不加载任何插件 jar）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PluginUserHookTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private FakeExtensions extensions;

    @AfterEach
    void clearFakes() {
        extensions.clear();
    }

    // ==================== 没有插件时：行为与迁移前一致 ====================

    @Test
    void withoutPluginsProfileAndToolbarAreUnchanged() throws Exception {
        WikiUser user = insertUser("钩子空实现用户" + System.nanoTime());
        String userPage = "用户:" + user.getUsername();
        wikiPageService.editPage(user, userPage, "简介");

        String html = page(userPage, sessionOf(user));
        assertTrue(html.contains("/img/avatar-default.svg"), html);
        assertFalse(html.contains("hook-profile-link"), "没有插件时资料卡没有额外链接");
        assertFalse(html.contains("toolbar-hook"), "没有插件时个人工具区没有额外条目");
    }

    // ==================== 资料卡钩子 ====================

    @Test
    void profileAvatarAndLinksComeFromPlugins() throws Exception {
        WikiUser user = insertUser("钩子资料卡用户" + System.nanoTime());
        String userPage = "用户:" + user.getUsername();
        wikiPageService.editPage(user, userPage, "简介");
        List<UserProfileContext> seen = new CopyOnWriteArrayList<>();
        extensions.profileHooks.add(new UserProfileHook() {
            @Override
            public String avatarUrl(UserProfileContext context) {
                seen.add(context);
                return "/avatars/hook-" + context.userId() + ".png";
            }

            @Override
            public String linksHtml(UserProfileContext context) {
                return "<span class=\"wiki-profile-sep\">|</span>"
                        + "<a class=\"hook-profile-link\" href=\"/wiki/特殊:钩子\">"
                        + context.username() + "|" + context.self() + "|" + context.userId() + "|"
                        + context.viewer().name() + "</a>";
            }
        });

        // 本人访问：资料卡用插件给的头像，链接里能看到上下文（self=true）
        String html = page(userPage, sessionOf(user));
        assertTrue(html.contains("/avatars/hook-" + user.getId() + ".png"), html);
        assertTrue(html.contains("class=\"wiki-avatar\" src=\"/avatars/hook-" + user.getId() + ".png\""),
                "插件给出的头像优先（不再用默认头像）：" + html);
        assertTrue(html.contains("hook-profile-link"), html);
        assertTrue(html.contains(user.getUsername() + "|true|" + user.getId() + "|" + user.getUsername()), html);

        UserProfileContext context = seen.get(0);
        assertEquals(user.getId(), context.userId());
        assertEquals(user.getUsername(), context.username());
        assertTrue(context.self());
        assertTrue(context.viewer().loggedIn());
        assertEquals("/img/avatar-default.svg", context.defaultAvatarUrl());
    }

    @Test
    void profileAvatarUsesFirstNonEmptyResultAndLinksFollowOrder() throws Exception {
        WikiUser user = insertUser("钩子顺序用户" + System.nanoTime());
        String userPage = "用户:" + user.getUsername();
        wikiPageService.editPage(user, userPage, "简介");
        extensions.profileHooks.add(new UserProfileHook() {
            @Override
            public int order() {
                return 20;
            }

            @Override
            public String avatarUrl(UserProfileContext context) {
                return "/avatars/second.png";
            }

            @Override
            public String linksHtml(UserProfileContext context) {
                return "B";
            }
        });
        extensions.profileHooks.add(new UserProfileHook() {
            @Override
            public int order() {
                return 10;
            }

            @Override
            public String linksHtml(UserProfileContext context) {
                return "A";
            }
        });

        String html = page(userPage, sessionOf(user));
        assertTrue(html.contains("/avatars/second.png"), "order 靠前但返回 null 的插件不拦截头像：" + html);
        assertTrue(html.contains("AB"), "额外链接按 order 升序拼接：" + html);
    }

    @Test
    void profileContextForMissingAccountHasZeroUserId() throws Exception {
        // 页面存在但账号不存在（用户命名空间的普通页面）：userId=0、self=false，插件据此不显示「仅本人」入口
        String title = "用户:幽灵" + System.nanoTime();
        wikiPageService.editPage(null, title, "正文");
        List<UserProfileContext> seen = new CopyOnWriteArrayList<>();
        extensions.profileHooks.add(new UserProfileHook() {
            @Override
            public String linksHtml(UserProfileContext context) {
                seen.add(context);
                return null;
            }
        });

        page(title, null);
        assertEquals(1, seen.size());
        assertEquals(0, seen.get(0).userId());
        assertFalse(seen.get(0).self());
        assertTrue(seen.get(0).username().startsWith("幽灵"));
    }

    // ==================== 个人工具区钩子 ====================

    @Test
    void toolbarItemsAreAppendedInOrderAndFailuresAreSkipped() throws Exception {
        WikiUser user = insertUser("钩子顶栏用户" + System.nanoTime());
        String page = "钩子顶栏页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");
        List<String> calls = new ArrayList<>();
        extensions.toolbarHooks.add(toolbar(20, context -> {
            calls.add("第二");
            return "<li class=\"toolbar-hook-b\">B</li>";
        }));
        extensions.toolbarHooks.add(toolbar(10, context -> {
            calls.add("第一");
            return "<li class=\"toolbar-hook-a\">A:" + context.viewer().name() + ":" + context.siteName()
                    + ":" + context.baseUrl() + "</li>";
        }));
        extensions.toolbarHooks.add(toolbar(30, context -> {
            throw new IllegalStateException("坏钩子");
        }));

        String html = page(page, sessionOf(user));
        assertEquals(List.of("第一", "第二"), calls, "按 order 升序执行，坏钩子被跳过");
        assertTrue(html.contains("toolbar-hook-a"), html);
        assertTrue(html.contains("toolbar-hook-b"), html);
        assertTrue(html.indexOf("toolbar-hook-a") < html.indexOf("toolbar-hook-b"), "按 order 顺序插入");
        assertTrue(html.indexOf("toolbar-hook-a") > html.indexOf("退出"), "插件条目在最后一项之后");
        assertTrue(html.contains("A:" + user.getUsername() + ":WikiKernel:http://localhost"), html);
    }

    @Test
    void toolbarHookSeesAnonymousViewer() throws Exception {
        String page = "钩子匿名页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");
        AtomicReference<Viewer> seen = new AtomicReference<>();
        extensions.toolbarHooks.add(toolbar(10, context -> {
            seen.set(context.viewer());
            return null;
        }));

        page(page, null);
        assertTrue(seen.get() != null && seen.get().anonymous(), "匿名用户也带上下文（由插件决定是否显示）");
    }

    // ==================== API 请求不询问页面钩子 ====================

    @Test
    void apiRequestsDoNotConsultPageHooks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        extensions.toolbarHooks.add(toolbar(10, context -> {
            calls.incrementAndGet();
            return "<li>x</li>";
        }));
        extensions.profileHooks.add(new UserProfileHook() {
            @Override
            public String avatarUrl(UserProfileContext context) {
                calls.incrementAndGet();
                return "/avatars/x.png";
            }
        });

        mockMvc.perform(get("/api/page-preview").param("title", "首页")).andExpect(status().isOk());
        assertEquals(0, calls.get(), "API 请求不渲染模板，不应询问这两类钩子");
    }

    // ==================== 工具与测试替身 ====================

    private String page(String title, MockHttpSession session) throws Exception {
        var request = get("/wiki/{title}", title);
        if (session != null) {
            request.session(session);
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
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

    private static PersonalToolbarHook toolbar(int order, Function<PersonalToolbarContext, String> body) {
        return new PersonalToolbarHook() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public String itemsHtml(PersonalToolbarContext context) {
                return body.apply(context);
            }
        };
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
        UserProfileHookService userProfileHookService(FakeExtensions extensions) {
            return new UserProfileHookService(extensions.manager());
        }

        @Bean
        @Primary
        PersonalToolbarHookService personalToolbarHookService(FakeExtensions extensions) {
            return new PersonalToolbarHookService(extensions.manager());
        }
    }

    /** 可变的假扩展点集合（每个测试自己添加）。 */
    static class FakeExtensions {

        final List<UserProfileHook> profileHooks = new CopyOnWriteArrayList<>();
        final List<PersonalToolbarHook> toolbarHooks = new CopyOnWriteArrayList<>();

        void clear() {
            profileHooks.clear();
            toolbarHooks.clear();
        }

        /** 只提供这两类扩展点的 {@link PluginManager} 替身（不启动任何插件容器）。 */
        PluginManager manager() {
            return new PluginManager(null, null, null, null) {
                @Override
                public <T extends Extension> List<T> extensions(Class<T> extensionPoint) {
                    List<? extends Extension> source;
                    if (extensionPoint == UserProfileHook.class) {
                        source = profileHooks;
                    } else if (extensionPoint == PersonalToolbarHook.class) {
                        source = toolbarHooks;
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
