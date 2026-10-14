package cc.thonly.wikikernel.plugin.pagebanner.service;

import cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes;
import cc.thonly.wikikernel.plugin.pagebanner.PageBannerSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.config;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.image;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.renderer;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.video;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageBannerRenderer}：开关 / 白名单 / 逐页配置 / 轮换机制 / 图片池 / caption /
 * {@code {{PAGEBANNER:…}}} 优先 / 图片缺失时不显示，以及**多图轮播**（顺序稳定、单图退化、
 * 轮播配置进 {@code data-*} 属性）。
 */
class PageBannerRendererTest {

    private static final String POOL_A = "池甲.png";
    private static final String POOL_B = "池乙.png";

    @Test
    void whitelistModeOnlyShowsListedPages() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.FILES_KEY, POOL_A,
                PageBannerSettings.WHITELIST_ENABLED_KEY, "true",
                PageBannerSettings.WHITELIST_KEY, "白名单页"), image(POOL_A));

        String html = renderer.htmlFor("白名单页", "", "", false);
        assertTrue(html != null && html.contains("class=\"page-banner\""), String.valueOf(html));
        assertTrue(html.contains("src=\"/files/" + POOL_A + "\""), html);
        assertTrue(html.contains("alt=\"" + POOL_A + "\""), html);

        assertNull(renderer.htmlFor("其他页", "", "", false));
        assertFalse(renderer.mayDisplay("其他页", false));
        assertTrue(renderer.mayDisplay("白名单页", false));
    }

    @Test
    void whitelistOffShowsEveryPageWithPoolImage() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.FILES_KEY, POOL_A,
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image(POOL_A));
        assertTrue(renderer.htmlFor("任意页", "", "", false).contains("class=\"page-banner\""));
    }

    @Test
    void perPageMappingPinsBannerAndImpliesWhitelist() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.FILES_KEY, POOL_A,
                PageBannerSettings.WHITELIST_ENABLED_KEY, "true",
                PageBannerSettings.WHITELIST_KEY, "",
                PageBannerSettings.PER_PAGE_KEY, "映射页=固定.png\n裸标题页"),
                image(POOL_A), image("固定.png"));

        // 逐页配置里指定的文件优先于图片池
        assertTrue(renderer.htmlFor("映射页", "", "", false).contains("alt=\"固定.png\""));
        // 只写标题：从图片池按轮换机制取
        assertTrue(renderer.htmlFor("裸标题页", "", "", false).contains("alt=\"" + POOL_A + "\""));
        // 既不在白名单也不在逐页配置：没有横幅
        assertNull(renderer.htmlFor("其他页", "", "", false));
    }

    @Test
    void pageBannerDirectiveWinsOverPerPageAndPool() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.FILES_KEY, POOL_A,
                PageBannerSettings.WHITELIST_ENABLED_KEY, "true",
                PageBannerSettings.PER_PAGE_KEY, "演示页=固定.png"), image(POOL_A), image("指定.png"));

        String html = renderer.htmlFor("演示页", "指定.png", "山上的日出", false);
        assertTrue(html != null && html.contains("src=\"/files/指定.png\""), String.valueOf(html));
        assertFalse(html.contains("固定.png"), html);
        assertTrue(html.contains("<div class=\"page-banner-caption\">山上的日出</div>"), html);
        // 有说明文字时图片的 alt 留空（避免读屏重复念一遍说明）
        assertEquals(List.of(""), alts(html), html);
        // 没有说明文字时 alt 用文件名
        assertEquals(List.of("指定.png"),
                alts(renderer.htmlFor("演示页", "指定.png", "", false)));
    }

    @Test
    void captionAndFileNamesAreEscaped() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("危险.png"));
        String html = renderer.htmlFor("页", "危险.png", "<b>粗体</b> & \"引号\"", false);
        assertTrue(html != null && html.contains("&lt;b&gt;粗体&lt;/b&gt; &amp; &quot;引号&quot;"),
                String.valueOf(html));
        assertFalse(html.contains("<b>粗体</b>"), html);
    }

    @Test
    void missingOrNonImageFileHidesBanner() {
        Map<String, String> values = config(
                PageBannerSettings.FILES_KEY, "缺失.png",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false");
        assertNull(renderer(values, image(POOL_A)).htmlFor("页", "", "", false),
                "图片池里的文件不存在时不显示横幅");
        assertNull(renderer(config(PageBannerSettings.WHITELIST_ENABLED_KEY, "false"),
                video("动画.webm")).htmlFor("页", "动画.webm", "", false),
                "非图片文件不显示横幅");
        assertNull(renderer(values, image(POOL_A)).htmlFor("页", "指定但不存在.png", "", false));
    }

    @Test
    void emptyPoolMeansNoDefaultBanner() {
        PageBannerRenderer renderer = renderer(config(PageBannerSettings.WHITELIST_ENABLED_KEY, "false"));
        assertNull(renderer.htmlFor("页", "", "", false));
        // 页面里显式指定时仍然显示
        PageBannerRenderer withFile = renderer(config(PageBannerSettings.WHITELIST_ENABLED_KEY, "false"),
                image("指定.png"));
        assertTrue(withFile.htmlFor("页", "指定.png", "", false).contains("alt=\"指定.png\""));
    }

    @Test
    void disabledFeatureAndExcludedPageHideBanner() {
        PageBannerRenderer disabled = renderer(config(
                PageBannerSettings.ENABLED_KEY, "false",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("指定.png"));
        assertNull(disabled.htmlFor("页", "指定.png", "说明", false), "功能关闭时不显示横幅");
        assertFalse(disabled.mayDisplay("页", false));

        PageBannerRenderer enabled = renderer(config(
                PageBannerSettings.ENABLED_KEY, "true",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("指定.png"));
        assertNull(enabled.htmlFor("页", "指定.png", "说明", true), "<exclude-banner-page /> 排除的页面不显示横幅");
        assertFalse(enabled.mayDisplay("页", true));
    }

    @Test
    void rotationModesBehave() {
        // rotate：每**次浏览**换一个起始张（同一次渲染内 head 与正文共用同一份结果，见 slidesForProperties）
        for (int index = 0; index < 3; index++) {
            PageBannerRenderer renderer = renderer(config(
                    PageBannerSettings.FILES_KEY, POOL_A + "|" + POOL_B,
                    PageBannerSettings.WHITELIST_ENABLED_KEY, "false",
                    PageBannerSettings.ROTATION_KEY, "rotate"), image(POOL_A), image(POOL_B));
            String viewed = renderer.htmlFor("轮换页", "", "", false);
            assertEquals(POOL_A, alt(viewed), "第 " + (index + 1) + " 次浏览从第一张开始");
            // 同一页面的正文与 head 片段看到的是同一批图片（顺序也相同）
            assertEquals(sources(viewed),
                    sources(renderer.htmlForProperties("轮换页", Map.of())));
        }

        for (String mode : List.of("stable", "daily", "hourly")) {
            PageBannerRenderer renderer = renderer(config(
                    PageBannerSettings.FILES_KEY, POOL_A + "|" + POOL_B,
                    PageBannerSettings.WHITELIST_ENABLED_KEY, "false",
                    PageBannerSettings.ROTATION_KEY, mode), image(POOL_A), image(POOL_B));
            assertEquals(alt(renderer.htmlFor("稳定页", "", "", false)),
                    alt(renderer.htmlFor("稳定页", "", "", false)), mode + "：同一页面每次都是同一张");
        }

        PageBannerRenderer random = renderer(config(
                PageBannerSettings.FILES_KEY, POOL_A + "|" + POOL_B,
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false",
                PageBannerSettings.ROTATION_KEY, "random"), image(POOL_A), image(POOL_B));
        List<String> picks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            picks.add(alt(random.htmlFor("随机页 " + i, "", "", false)));
        }
        assertTrue(picks.stream().allMatch(pick -> POOL_A.equals(pick) || POOL_B.equals(pick)), picks.toString());
    }

    @Test
    void invalidRotationFallsBackToStable() {
        PageBannerSettings settings = PageBannerFakes.settings(config(
                PageBannerSettings.ROTATION_KEY, "bogus"));
        assertEquals("stable", settings.rotation());
        assertEquals(List.of("stable", "random", "rotate", "daily", "hourly"), PageBannerSettings.ROTATION_MODES);
    }

    @Test
    void settingsAreParsedLikeCore() {
        PageBannerSettings settings = PageBannerFakes.settings(config(
                PageBannerSettings.ENABLED_KEY, "true",
                PageBannerSettings.FILES_KEY, "甲.png|乙.png\n丙.png",
                PageBannerSettings.WHITELIST_KEY, "首页|演示_页",
                PageBannerSettings.PER_PAGE_KEY, "逐页页=丙.png\n只写标题页"));

        assertTrue(settings.enabled());
        assertEquals(List.of("甲.png", "乙.png", "丙.png"), settings.files());
        assertEquals(List.of("首页", "演示_页"), settings.whitelist());
        Map<String, String> perPage = settings.perPage();
        assertEquals("丙.png", perPage.get("逐页页"));
        assertEquals("", perPage.get("只写标题页"));
        // 标题规范化：下划线折叠成空格，命名空间后的首字母大写
        assertEquals("演示 页", PageBannerSettings.normalize("演示_页"));
        assertEquals("模板:Infobox", PageBannerSettings.normalize("模板:infobox"));
    }

    @Test
    void poolRendersEveryImageInStableOrder() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.FILES_KEY, POOL_A + "|" + POOL_B + "|丙.png",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"),
                image(POOL_A), image(POOL_B), image("丙.png"));

        String html = renderer.htmlFor("多图页", "", "", false);
        assertNotNull(html);
        // stable：顺序是图片池的一个**固定**循环移位（起始张由标题哈希决定）
        assertTrue(isRotationOf(List.of("/files/" + POOL_A, "/files/" + POOL_B, "/files/丙.png"),
                sources(html)), html);
        assertTrue(html.contains("data-slide-count=\"3\""), html);
        assertTrue(html.contains("data-page-banner-carousel"), html);
        // 顺序稳定：同一页面再次渲染（同一进程）还是同样的顺序
        assertEquals(sources(html), sources(renderer.htmlFor("多图页", "", "", false)));
    }

    @Test
    void missingAndDuplicatePoolImagesAreSkippedInOrder() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.FILES_KEY, "缺失.png|" + POOL_A + "|" + POOL_B + "|" + POOL_A + "|动画.webm",
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"),
                image(POOL_A), image(POOL_B), video("动画.webm"));

        String html = renderer.htmlFor("页", "", "", false);
        assertEquals(List.of("/files/" + POOL_A, "/files/" + POOL_B), sources(html), String.valueOf(html));
        assertTrue(html.contains("data-slide-count=\"2\""), html);
    }

    @Test
    void directiveAndPerPageCanCarrySeveralImages() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.FILES_KEY, POOL_A,
                PageBannerSettings.WHITELIST_ENABLED_KEY, "true",
                PageBannerSettings.PER_PAGE_KEY, "映射页=甲.png | 乙.png"),
                image(POOL_A), image("甲.png"), image("乙.png"));

        // 逐页配置里写多个文件名 = 该页固定的轮播顺序
        String mapped = renderer.htmlFor("映射页", "", "", false);
        assertEquals(List.of("/files/甲.png", "/files/乙.png"), sources(mapped), String.valueOf(mapped));
        // {{PAGEBANNER:甲|乙}}：指令按 `|` 拆参数后由 PageBannerDirective 拼回同一个属性
        String requested = renderer.htmlFor("映射页", "甲.png|乙.png", "", false);
        assertEquals(List.of("/files/甲.png", "/files/乙.png"), sources(requested), String.valueOf(requested));
    }

    @Test
    void singleImageDegradesToStaticBanner() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.FILES_KEY, POOL_A,
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image(POOL_A));

        String html = renderer.htmlFor("单图页", "", "", false);
        assertNotNull(html);
        assertFalse(html.contains(PageBannerRenderer.CAROUSEL_ATTRIBUTE), html);
        assertFalse(html.contains(PageBannerRenderer.CAROUSEL_CLASS), html);
        assertFalse(html.contains("page-banner-prev"), html);
        assertFalse(html.contains("page-banner-next"), html);
        assertFalse(html.contains(PageBannerRenderer.DOT_CLASS), html);
        assertFalse(html.contains("page-banner-progress"), html);
        assertTrue(html.contains("loading=\"lazy\""), html);
        assertEquals(1, sources(html).size(), html);
    }

    @Test
    void carouselMarkupCarriesControlsAndConfiguration() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.FILES_KEY, POOL_A + "|" + POOL_B,
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false",
                PageBannerSettings.INTERVAL_KEY, "9",
                PageBannerSettings.TRANSITION_KEY, "slide",
                PageBannerSettings.CONTROLS_KEY, "true",
                PageBannerSettings.PAUSE_ON_HOVER_KEY, "false"), image(POOL_A), image(POOL_B));

        String html = renderer.htmlFor("轮播页", "", "说明", false);
        assertTrue(html.contains(PageBannerRenderer.CAROUSEL_ATTRIBUTE), html);
        assertTrue(html.contains("data-interval=\"9\""), html);
        assertTrue(html.contains("data-transition=\"slide\""), html);
        assertTrue(html.contains("data-controls=\"true\""), html);
        assertTrue(html.contains("data-pause-on-hover=\"false\""), html);
        assertTrue(html.contains("data-slide-count=\"2\""), html);
        assertTrue(html.contains("page-banner-prev"), html);
        assertTrue(html.contains("page-banner-next"), html);
        assertTrue(html.contains("class=\"" + PageBannerRenderer.DOT_CLASS), html);
        assertTrue(html.contains("page-banner-dot--active"), html);
        assertTrue(html.contains("data-slide-to=\"1\""), html);
        assertTrue(html.contains("class=\"page-banner-slide page-banner-slide--active\""), html);
        assertTrue(html.contains("loading=\"lazy\""), html);
        // 无障碍：容器是 group + carousel，指示点用 aria-current，说明文字每张都在
        assertTrue(html.contains("role=\"group\""), html);
        assertTrue(html.contains("aria-roledescription=\"carousel\""), html);
        assertTrue(html.contains("aria-current=\"true\""), html);
        assertTrue(html.contains("aria-hidden=\"false\""), html);
        assertTrue(html.contains("aria-hidden=\"true\""), html);
        assertTrue(html.contains("aria-label=\""), html);
        assertEquals(2, occurrences(html, "<div class=\"page-banner-caption\">说明</div>"), html);
    }

    @Test
    void carouselControlsCanBeSwitchedOff() {
        PageBannerRenderer renderer = renderer(config(
                PageBannerSettings.FILES_KEY, POOL_A + "|" + POOL_B,
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false",
                PageBannerSettings.CONTROLS_KEY, "false"), image(POOL_A), image(POOL_B));

        String html = renderer.htmlFor("无控件页", "", "", false);
        assertEquals(List.of("/files/" + POOL_A, "/files/" + POOL_B), sources(html), html);
        assertTrue(html.contains("data-controls=\"false\""), html);
        assertFalse(html.contains("page-banner-prev"), html);
        assertFalse(html.contains("page-banner-next"), html);
        assertFalse(html.contains("data-slide-to="), html);
        assertFalse(html.contains(PageBannerRenderer.CONTROLS_CLASS), html);
    }

    @Test
    void carouselDoesNotTurnOnWithoutRequests() {
        // 单图 + 指令只有一张：没有轮播标记
        PageBannerRenderer single = renderer(config(
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("只有一张.png"));
        assertFalse(single.htmlFor("页", "只有一张.png", "", false)
                .contains(PageBannerRenderer.CAROUSEL_ATTRIBUTE));
        // 指令只让**存在**的图片成立：其中一张不存在时只渲染存在的那张
        PageBannerRenderer partly = renderer(config(
                PageBannerSettings.WHITELIST_ENABLED_KEY, "false"), image("甲.png"));
        String html = partly.htmlFor("页", "甲.png|不存在.png", "", false);
        assertEquals(List.of("/files/甲.png"), sources(html), String.valueOf(html));
        assertFalse(html.contains(PageBannerRenderer.CAROUSEL_ATTRIBUTE), html);
    }

    /** 去掉 HTML 前后的空白，方便断言。 */
    private static String alt(String html) {
        if (html == null) {
            return "";
        }
        int start = html.indexOf("alt=\"");
        int end = html.indexOf('"', start + 5);
        return start < 0 || end < 0 ? "" : html.substring(start + 5, end);
    }

    /** HTML 里的图片直链（按出现顺序）。 */
    private static List<String> sources(String html) {
        return attributes(html, "src=\"");
    }

    /** HTML 里的图片 alt（按出现顺序）。 */
    private static List<String> alts(String html) {
        return attributes(html, "alt=\"");
    }

    private static List<String> attributes(String html, String marker) {
        List<String> values = new ArrayList<>();
        if (html == null) {
            return values;
        }
        int index = html.indexOf(marker);
        while (index >= 0) {
            int end = html.indexOf('"', index + marker.length());
            if (end < 0) {
                break;
            }
            values.add(html.substring(index + marker.length(), end));
            index = html.indexOf(marker, end);
        }
        return values;
    }

    private static int occurrences(String html, String needle) {
        int count = 0;
        int index = html.indexOf(needle);
        while (index >= 0) {
            count++;
            index = html.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /** {@code actual} 是否是 {@code expected} 的一个循环移位（顺序稳定但起始张可变时用）。 */
    private static boolean isRotationOf(List<String> expected, List<String> actual) {
        if (expected.size() != actual.size() || expected.isEmpty()) {
            return false;
        }
        for (int start = 0; start < expected.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < expected.size(); offset++) {
                if (!expected.get((start + offset) % expected.size()).equals(actual.get(offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
