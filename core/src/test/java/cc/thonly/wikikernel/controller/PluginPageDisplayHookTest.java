package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.PageDisplayHookService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.user.UserSession;
import org.springframework.mock.web.MockHttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 页面显示钩子（{@link PageDisplayHook}）的 core 侧接线：
 * <ul>
 *     <li>{@code beforeContent} → 正文之前的片段（审核的「此页面有 N 条待审更改」提示条用它）；</li>
 *     <li>{@code renderMissingPage} → 页面**不存在**时认领该标题（审核的「待审创建」页用它），
 *     没有钩子认领时照常 404；</li>
 *     <li>钩子按 {@code order()} 升序、抛异常只跳过该插件；</li>
 *     <li>没有插件时页面渲染与迁移前完全一致。</li>
 * </ul>
 * 用假的 {@link PluginManager} 提供扩展点实现（core 测试不加载任何插件 jar）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PluginPageDisplayHookTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private FakeDisplayExtensions extensions;

    @Autowired private PermissionService permissions;
    @Autowired private WikiUserMapper users;

    @AfterEach
    void clearFakes() {
        extensions.hooks.clear();
    }

    @Test
    void pluginMissingPageLoadsItsCssAndJavaScriptWithoutCreatingAnArticle() throws Exception {
        String title = "插件 虚拟页面" + System.nanoTime() + "/子页面";
        wikiPageService.editPage(null, title + "/Common.css", ".virtual-custom-css { color: rgb(1, 2, 3); }");
        wikiPageService.editPage(null, title + "/Common.js", "window.VIRTUAL_CUSTOM_JS = true;");
        extensions.hooks.add(new PageDisplayHook() {
            @Override public String renderMissingPage(PageDisplayContext context) {
                return context.title().equals(title) ? "<p class=\"virtual-custom-css\">虚拟正文</p>" : null;
            }
        });
        String html = mockMvc.perform(get("/wiki/{title}/{rest}", title.split("/")[0].replace(' ', '_'), "子页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".virtual-custom-css { color: rgb(1, 2, 3); }")))
                .andExpect(content().string(containsString("/Common.js?action=raw&amp;ctype=text/javascript")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get("/wiki/{title}/{rest}", title.split("/")[0], "子页面/Common.js")
                        .param("action", "raw").param("ctype", "text/javascript"))
                .andExpect(status().isOk())
                .andExpect(content().string("window.VIRTUAL_CUSTOM_JS = true;"));
        org.junit.jupiter.api.Assertions.assertTrue(wikiPageService.getByTitle(title).isEmpty());
        java.nio.file.Path fixture = java.nio.file.Path.of("build/ui-fixtures/virtual-assets.html");
        java.nio.file.Files.createDirectories(fixture.getParent());
        java.nio.file.Files.writeString(fixture, html);
    }

    @Test
    void protectedVirtualPageChecksReadPermissionBeforeRenderingItsBody() throws Exception {
        String title = "受保护的虚拟页面" + System.nanoTime();
        extensions.hooks.add(new PageDisplayHook() {
            @Override public String renderMissingPage(PageDisplayContext context) {
                return context.title().equals(title) ? "<p>PRIVATE_VIRTUAL_BODY</p>" : null;
            }
        });
        WikiUser admin = new WikiUser();
        admin.setUsername("VirtualAdmin" + System.nanoTime()); admin.setPassword("unused"); users.insert(admin);
        permissions.grantDefault(admin.getId()); permissions.grant(admin.getId(), PermissionService.GROUP_ADMIN, null);
        MockHttpSession session = new MockHttpSession(); session.setAttribute(UserSession.ATTRIBUTE, admin.toTransferCopy());
        mockMvc.perform(post("/api/pages/protect").session(session).param("title", title)
                        .param("readGroups", PermissionService.GROUP_ADMIN).param("editGroups", PermissionService.GROUP_ADMIN))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/wiki/{title}", title)).andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("PRIVATE_VIRTUAL_BODY"))));
        mockMvc.perform(get("/wiki/{title}", title).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("PRIVATE_VIRTUAL_BODY")));
        mockMvc.perform(get("/wiki/特殊:保护页面").session(session).param("page", title))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"wiki-protect-form\"")));
        org.junit.jupiter.api.Assertions.assertTrue(wikiPageService.getByTitle(title).isEmpty());
    }

    @Test
    void withoutPluginsPagesAreUnchanged() throws Exception {
        String title = "页面钩子空实现页" + System.nanoTime();
        wikiPageService.editPage(null, title, "正文甲");

        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("正文甲")));
        // 没有插件认领的标题照常 404
        mockMvc.perform(get("/wiki/{title}", "页面钩子不存在页" + System.nanoTime()))
                .andExpect(status().isNotFound());
    }

    @Test
    void beforeContentFragmentIsInsertedAboveTheBody() throws Exception {
        String title = "页面钩子提示条页" + System.nanoTime();
        wikiPageService.editPage(null, title, "正文乙");
        extensions.hooks.add(new PageDisplayHook() {
            @Override
            public String beforeContent(PageDisplayContext context) {
                return "<div class=\"wiki-warning hook-banner\">此页面有 1 条待审更改。</div>";
            }
        });

        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hook-banner")))
                .andExpect(content().string(containsString("正文乙")))
                // 提示条在正文之前
                .andExpect(content().string(matchesPattern(
                        "(?s).*hook-banner.*正文乙.*")));
    }

    @Test
    void missingPageCanBeClaimedByAPlugin() throws Exception {
        String title = "页面钩子待审创建页" + System.nanoTime();
        List<PageDisplayContext> seen = new CopyOnWriteArrayList<>();
        extensions.hooks.add(new PageDisplayHook() {
            @Override
            public String renderMissingPage(PageDisplayContext context) {
                seen.add(context);
                if (!context.title().equals(title)) {
                    return null;
                }
                return "<p class=\"hook-pending-preview\">待审内容预览：甲甲甲</p>";
            }
        });

        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hook-pending-preview")))
                .andExpect(content().string(containsString("甲甲甲")))
                .andExpect(content().string(containsString(title)));
        // 上下文：不存在的标题 pageId=0 / 正文为空
        assertEquals(0, seen.get(0).pageId());
        assertEquals("", seen.get(0).content());
        assertEquals(title, seen.get(0).title());

        // 其它标题不被认领 → 仍然 404
        mockMvc.perform(get("/wiki/{title}", "页面钩子其它页" + System.nanoTime()))
                .andExpect(status().isNotFound());
    }

    @Test
    void firstClaimingHookWinsAndFailuresAreTolerated() throws Exception {
        String title = "页面钩子认领顺序页" + System.nanoTime();
        extensions.hooks.add(new PageDisplayHook() {
            @Override
            public int order() {
                return 10;
            }

            @Override
            public String renderMissingPage(PageDisplayContext context) {
                throw new IllegalStateException("插件坏了");
            }
        });
        extensions.hooks.add(new PageDisplayHook() {
            @Override
            public int order() {
                return 20;
            }

            @Override
            public String renderMissingPage(PageDisplayContext context) {
                return "<p class=\"hook-first-claim\">第一个认领</p>";
            }
        });

        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hook-first-claim")));
    }

    @Test
    void beforeContentFailuresDoNotBreakThePage() throws Exception {
        String title = "页面钩子异常页" + System.nanoTime();
        wikiPageService.editPage(null, title, "正文丙");
        extensions.hooks.add(new PageDisplayHook() {
            @Override
            public String beforeContent(PageDisplayContext context) {
                throw new IllegalStateException("插件坏了");
            }

            @Override
            public String afterContent(PageDisplayContext context) {
                throw new IllegalStateException("插件也坏了");
            }
        });

        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("正文丙")))
                .andExpect(content().string(not(containsString("插件坏了"))));
    }

    // ==================== 工具与测试替身 ====================

    @TestConfiguration
    static class FakeDisplayHooks {

        @Bean
        FakeDisplayExtensions fakeDisplayExtensions() {
            return new FakeDisplayExtensions();
        }

        @Bean
        @Primary
        PageDisplayHookService pageDisplayHookService(FakeDisplayExtensions extensions) {
            return new PageDisplayHookService(extensions.manager());
        }
    }

    /** 可变的假页面显示钩子集合（每个测试自己添加）。 */
    static class FakeDisplayExtensions {

        final List<PageDisplayHook> hooks = new CopyOnWriteArrayList<>();

        /** 只提供 {@link PageDisplayHook} 的 {@link PluginManager} 替身（不启动任何插件容器）。 */
        PluginManager manager() {
            return new PluginManager(null, null, null, null) {
                @Override
                public <T extends Extension> List<T> extensions(Class<T> extensionPoint) {
                    if (extensionPoint != PageDisplayHook.class) {
                        return List.of();
                    }
                    List<T> result = new ArrayList<>(hooks.size());
                    for (PageDisplayHook hook : hooks) {
                        result.add(extensionPoint.cast(hook));
                    }
                    return List.copyOf(result);
                }
            };
        }
    }
}
