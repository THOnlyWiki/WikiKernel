package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.PageDisplayHookService;
import cc.thonly.wikikernel.service.WikiPageService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @AfterEach
    void clearFakes() {
        extensions.hooks.clear();
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
