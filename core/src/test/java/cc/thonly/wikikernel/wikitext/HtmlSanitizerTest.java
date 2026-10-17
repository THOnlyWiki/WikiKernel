package cc.thonly.wikikernel.wikitext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML 白名单：自闭合标签必须变成合法 HTML，否则浏览器会把后续整页吞进元素里。
 */
class HtmlSanitizerTest {

    @Test
    void selfClosingIframeIsClosed() {
        String tag = HtmlSanitizer.sanitizeTag(
                "<iframe class=\"iframeMap\" width=\"100%\" src=\"https://example.com/map\" />");
        assertNotNull(tag);
        assertTrue(tag.startsWith("<iframe"), tag);
        assertTrue(tag.endsWith("></iframe>"), tag);
    }

    @Test
    void iframeWithoutSrcIsDropped() {
        assertEquals("", HtmlSanitizer.sanitizeTag(
                "<iframe class=\"iframeMap\" width=\"100%\" height=\"1200px\" />"));
    }

    @Test
    void selfClosingImgStaysVoid() {
        assertEquals("<img src=\"https://example.com/a.png\">",
                HtmlSanitizer.sanitizeTag("<img src=\"https://example.com/a.png\" />"));
    }

    @Test
    void selfClosingDivGetsClosingTag() {
        assertEquals("<div class=\"x\"></div>",
                HtmlSanitizer.sanitizeTag("<div class=\"x\" />"));
    }
}
