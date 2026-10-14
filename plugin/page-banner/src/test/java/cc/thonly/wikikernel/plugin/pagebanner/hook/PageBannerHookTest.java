package cc.thonly.wikikernel.plugin.pagebanner.hook;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.plugin.pagebanner.PageBannerSettings;
import cc.thonly.wikikernel.plugin.pagebanner.service.PageBannerRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.FakeRenderContext;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.config;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.displayContext;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.functionCall;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.image;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.renderer;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.tagCall;import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageBannerHook}：标题上方的横幅 HTML（读本次渲染写入的页面属性）、插件样式注入，
 * 以及多图时的轮播脚本注入与 {@code window.WIKI_MESSAGES} 文案。
 */
class PageBannerHookTest {

    @Test
    void directivePropertiesDriveTheBanner() {
        PageBannerHook hook = new PageBannerHook(renderer(config(
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("横幅.png")));

        // 渲染阶段：{{PAGEBANNER:…}} 写页面属性
        FakeRenderContext renderContext = new FakeRenderContext("演示页");
        assertEquals("", new PageBannerDirective().invoke(functionCall(renderContext, "横幅.png|caption=说明")));

        // 显示阶段：钩子按页面属性生成标题上方的横幅
        String html = hook.beforeTitle(displayContext("演示页", renderContext.properties()));
        assertTrue(html != null && html.contains("class=\"page-banner\""), String.valueOf(html));
        assertTrue(html.contains("src=\"/files/横幅.png\""), html);
        assertTrue(html.contains("<div class=\"page-banner-caption\">说明</div>"), html);
        assertEquals(50, hook.order());
    }

    @Test
    void excludeTagHidesTheBannerEvenWithDirective() {
        PageBannerHook hook = new PageBannerHook(renderer(config(
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("横幅.png")));

        FakeRenderContext renderContext = new FakeRenderContext("演示页");
        new PageBannerDirective().invoke(functionCall(renderContext, "横幅.png|caption=说明"));
        new ExcludeBannerTag().render(tagCall(renderContext, ""));

        assertNull(hook.beforeTitle(displayContext("演示页", renderContext.properties())));
        assertEquals(List.of(), hook.headItems(displayContext("演示页", renderContext.properties())));
    }

    @Test
    void bannerComesFromThePoolWhenThePageHasNoDirective() {
        PageBannerHook hook = new PageBannerHook(renderer(config(
                PageBannerSettings.FILES_KEY, "池甲.png",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("池甲.png")));
        String html = hook.beforeTitle(displayContext("任意页", Map.of()));
        assertTrue(html != null && html.contains("alt=\"池甲.png\""), String.valueOf(html));
    }

    @Test
    void noBannerWithoutPluginSettings() {
        // 开关关闭：即使页面写了 {{PAGEBANNER:…}} 也不显示
        PageBannerHook disabled = new PageBannerHook(renderer(config(
                PageBannerSettings.ENABLED_KEY, "false",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("横幅.png")));
        assertNull(disabled.beforeTitle(displayContext("演示页",
                Map.of(PageBannerRenderer.FILE_PROPERTY, "横幅.png"))));

        // 图片池为空且页面没有指定：没有横幅
        PageBannerHook empty = new PageBannerHook(renderer(config(
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false")));
        assertNull(empty.beforeTitle(displayContext("演示页", Map.of())));
    }

    @Test
    void headItemsInjectTheStylesheetOnlyWhenABannerMayShow() {
        PageBannerHook whitelisted = new PageBannerHook(renderer(config(
                PageBannerSettings.WHITELIST_ENABLED_KEY, "true",
                PageBannerSettings.WHITELIST_KEY, "白名单页"), image("池甲.png")));
        PageDisplayContext listed = displayContext("白名单页", Map.of());
        assertTrue(String.join("\n", whitelisted.headItems(listed))
                .contains("/plugins/page-banner/page-banner.css"));
        assertEquals(List.of(), whitelisted.headItems(displayContext("其他页", Map.of())));

        // 排除标记 / 功能关闭：连样式都不注入
        assertEquals(List.of(), whitelisted.headItems(displayContext("白名单页",
                Map.of(PageBannerRenderer.EXCLUDE_PROPERTY, "1"))));
        PageBannerHook disabled = new PageBannerHook(renderer(config(
                PageBannerSettings.ENABLED_KEY, "false",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("池甲.png")));
        assertEquals(List.of(), disabled.headItems(displayContext("任意页", Map.of())));
    }

    /** 多图：注入轮播脚本 + 文案脚本；单图：只有样式（不加载脚本）。 */
    @Test
    void headItemsInjectTheCarouselScriptOnlyForSeveralImages() {
        Map<String, String> values = config(
                PageBannerSettings.FILES_KEY, "甲.png|乙.png",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false");
        PageBannerHook hook = new PageBannerHook(renderer(values, image("甲.png"), image("乙.png")));
        String items = String.join("\n", hook.headItems(displayContext("轮播页", Map.of())));
        assertTrue(items.contains("/plugins/page-banner/page-banner.css"), items);
        assertTrue(items.contains("<script defer src=\"/plugins/page-banner/page-banner.js\"></script>"), items);
        // 前端文案不写死在脚本里：经 window.WIKI_MESSAGES 注入
        assertTrue(items.contains("window.WIKI_MESSAGES"), items);
        assertTrue(items.contains("pageBannerPrev"), items);
        assertTrue(items.contains("pageBannerNext"), items);

        PageBannerHook single = new PageBannerHook(renderer(config(
                PageBannerSettings.FILES_KEY, "甲.png",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("甲.png")));
        String singleItems = String.join("\n", single.headItems(displayContext("单图页", Map.of())));
        assertTrue(singleItems.contains("/plugins/page-banner/page-banner.css"), singleItems);
        assertFalse(singleItems.contains("page-banner.js"), singleItems);
    }

    /** 轮播配置进了 data-* 属性（含本地化后的控件文案；漏翻时用翻译键本身，不写死中文）。 */
    @Test
    void carouselConfigurationAndLabelsReachTheMarkup() {
        PageBannerHook hook = new PageBannerHook(renderer(config(
                PageBannerSettings.FILES_KEY, "甲.png|乙.png",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false",
                PageBannerSettings.INTERVAL_KEY, "7",
                PageBannerSettings.TRANSITION_KEY, "slide"), image("甲.png"), image("乙.png")));

        String html = hook.beforeTitle(displayContext("轮播页", Map.of()));
        assertTrue(html != null && html.contains("data-interval=\"7\""), String.valueOf(html));
        assertTrue(html.contains("data-transition=\"slide\""), html);
        assertTrue(html.contains("data-controls=\"true\""), html);
        assertTrue(html.contains("data-pause-on-hover=\"true\""), html);
        // 没有消息源：文案 = 翻译键本身（本项目规则：缺键显示键本身）
        assertTrue(html.contains("data-prev-label=\"" + PageBannerRenderer.PREV_LABEL_KEY + "\""), html);
        assertTrue(html.contains("aria-label=\"" + PageBannerRenderer.GOTO_LABEL_KEY + "\""), html);
    }
}
