package cc.thonly.wikikernel.plugin.wikiseo.service;

import cc.thonly.wikikernel.common.plugin.support.MapPluginConfig;
import cc.thonly.wikikernel.plugin.wikiseo.SeoFakes;
import cc.thonly.wikikernel.plugin.wikiseo.WikiSeoConfig;
import cc.thonly.wikikernel.plugin.wikiseo.hook.SeoHeadProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动描述：正文抽纯文本与截断逻辑（本机化实现，替代原版的 TextExtracts）。
 */
class SeoAutoDescriptionTest {

    @Test
    void plainTextStripsTemplatesLinksAndHtml() {
        String wikitext = "{{信息框|标题=x}}\n'''示例页面'''是[[示例|一个示例]]，"
                + "见[https://example.com 外链]与[http://b.example 另一个]。<ref>引用</ref>"
                + "<span class=\"x\">结尾</span>";
        String text = SeoAutoDescription.plainText(wikitext);

        assertFalse(text.contains("{{"), text);
        assertFalse(text.contains("[[") || text.contains("]]"), text);
        assertFalse(text.contains("'''"), text);
        assertFalse(text.contains("<"), text);
        assertFalse(text.contains("https://example.com"), text);
        assertTrue(text.contains("示例页面是一个示例"), text);
        assertTrue(text.contains("外链"), text);
        assertTrue(text.contains("结尾"), text);
    }

    @Test
    void nestedTemplatesAreRemoved() {
        String text = SeoAutoDescription.plainText("{{a|{{b|{{c}}}}}}正文开始。");
        assertFalse(text.contains("{"), text);
        assertEquals("正文开始。", text);
    }

    @Test
    void longTextIsCutAtLastSentenceEnd() {
        String first = "一".repeat(60) + "。";
        String second = "二".repeat(60) + "。";
        String third = "三".repeat(60) + "。";
        String text = first + second + third;  // 183 个字符，前 150 个字符落在第三句中间

        String description = SeoAutoDescription.from(text, false);

        assertEquals(first + second, description);
        assertTrue(description.length() <= SeoAutoDescription.LIMIT + 1);
    }

    @Test
    void longTextWithoutSentenceEndKeepsPrefix() {
        String text = "字".repeat(200);
        String description = SeoAutoDescription.from(text, false);
        assertEquals(SeoAutoDescription.LIMIT, description.length());
    }

    @Test
    void cleanModeCutsAtLastPeriod() {
        // 前 150 字符里依次有「。」和「！」，clean 模式会退回到「。」
        String text = "开".repeat(50) + "。" + "中".repeat(50) + "！" + "后".repeat(60);

        String relaxed = SeoAutoDescription.from(text, false);
        String cleaned = SeoAutoDescription.from(text, true);

        assertEquals("开".repeat(50) + "。" + "中".repeat(50) + "！", relaxed);
        assertEquals("开".repeat(50) + "。", cleaned);
        assertTrue(cleaned.endsWith("。"), cleaned);
    }

    @Test
    void cleanModeKeepsTextWithoutPeriod() {
        String text = "没有句号的一段文字";
        assertEquals(text, SeoAutoDescription.from(text, true));
    }

    @Test
    void shortTextIsReturnedAsIs() {
        assertEquals("简短描述。", SeoAutoDescription.from("简短描述。", false));
        assertEquals("", SeoAutoDescription.from("", false));
        assertEquals("", SeoAutoDescription.from(null, false));
        assertEquals("", SeoAutoDescription.from("   \n  ", false));
    }

    @Test
    void autoSourceDetection() {
        assertTrue(SeoAutoDescription.isAutoSource("auto"));
        assertTrue(SeoAutoDescription.isAutoSource("TEXTEXTRACTS"));
        assertTrue(SeoAutoDescription.isAutoSource(" auto "));
        assertFalse(SeoAutoDescription.isAutoSource("手工描述"));
        assertFalse(SeoAutoDescription.isAutoSource(null));
    }

    @Test
    void headProviderFallsBackWhenNoPageContent() {
        WikiSeoConfig config = WikiSeoConfig.from(
                new MapPluginConfig(
                        Map.of("enable-auto-description", "true")));
        SeoFakes.FakePageStore store = new SeoFakes.FakePageStore().content(1, "示例页面", "   ", null);

        String html = String.join("\n", new SeoHeadProvider(config,
                SeoValidator.forGenerators(List.of(new MetaTagGenerator())),
                List.of(new MetaTagGenerator()), store).headItems(new SeoFakes.FakeHeadContext()));

        assertFalse(html.contains("name=\"description\""), html);
    }
}
