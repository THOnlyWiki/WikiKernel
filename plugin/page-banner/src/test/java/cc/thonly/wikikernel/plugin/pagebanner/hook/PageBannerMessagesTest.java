package cc.thonly.wikikernel.plugin.pagebanner.hook;

import cc.thonly.wikikernel.common.plugin.support.SimplePluginContext;
import cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageBannerMessages}：轮播设置项与控件的文案（中英双语，来自插件自己的
 * {@code i18n/messages*.properties}），以及缺键 / 无消息源时返回翻译键本身的规则。
 */
class PageBannerMessagesTest {

    @Test
    void translationsCoverBothLanguages() {
        Map<String, Map<String, String>> messages = new PageBannerMessages().messages();
        Map<String, String> zh = messages.get("zh");
        Map<String, String> en = messages.get("en");
        assertTrue(zh != null && !zh.isEmpty(), messages.toString());
        assertTrue(en != null && !en.isEmpty(), messages.toString());

        // 轮播设置项（core 的站点设置表单项新增这些字段时用）
        assertTrue(zh.containsKey("pageBanner.interval"), zh.toString());
        assertTrue(zh.containsKey("pageBanner.intervalHint"), zh.toString());
        assertTrue(zh.containsKey("pageBanner.transition"), zh.toString());
        assertTrue(zh.containsKey("pageBanner.transition.fade"), zh.toString());
        assertTrue(zh.containsKey("pageBanner.transition.slide"), zh.toString());
        assertTrue(zh.containsKey("pageBanner.controls"), zh.toString());
        assertTrue(zh.containsKey("pageBanner.controlsHint"), zh.toString());
        assertTrue(zh.containsKey("pageBanner.pauseOnHover"), zh.toString());
        assertTrue(zh.containsKey("pageBanner.pauseOnHoverHint"), zh.toString());
        // 轮播控件文案（服务端经 data-* / window.WIKI_MESSAGES 交给脚本）
        for (String key : new String[] {"pageBanner.prev", "pageBanner.next", "pageBanner.goto",
                "pageBanner.status", "pageBanner.region"}) {
            assertTrue(zh.containsKey(key), key + " 应有中文文案");
            assertTrue(en.containsKey(key), key + " 应有英文文案");
        }
        // 缺键显示键本身：不能出现「中文兜底」这种写法
        assertFalse(zh.get("pageBanner.prev").isBlank());
        assertFalse(en.get("pageBanner.prev").isBlank());
    }

    @Test
    void textResolvesThroughTheMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        PageBannerMessages messages = new PageBannerMessages(source);

        // 中文（默认语言）
        assertEquals("上一张", messages.text("pageBanner.prev"));
        // 英文（core 按请求语言解析，这里用 LocaleContextHolder 模拟）
        Locale previous = LocaleContextHolder.getLocale();
        try {
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            assertEquals("Next slide", messages.text("pageBanner.next"));
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
    }

    @Test
    void missingKeysAndMissingSourceReturnTheKeyItself() {
        // 没有消息源（独立运行 / 单元测试）：显示翻译键本身
        assertEquals("pageBanner.prev", new PageBannerMessages().text("pageBanner.prev"));
        // 键不存在：显示翻译键本身，不回落中文
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        assertEquals("pageBanner.notTranslated", new PageBannerMessages(source).text("pageBanner.notTranslated"));
    }

    /** 资源文件在插件自己的类路径上（core 也是从插件 jar 里读 i18n/messages*.properties）。 */
    @Test
    void bundleLoadsFromThePluginClasspath() {
        SimplePluginContext context = PageBannerFakes.pluginContext(Map.of());
        assertEquals("page-banner", context.pluginId());

        Map<String, Map<String, String>> messages = new PageBannerMessages().messages();
        assertEquals("固定（按标题哈希）", messages.get("zh").get("pageBanner.rotation.stable"));
        assertEquals("Slide", messages.get("en").get("pageBanner.transition.slide"));
    }
}
