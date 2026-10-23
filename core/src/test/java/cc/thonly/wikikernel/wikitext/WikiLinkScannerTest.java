package cc.thonly.wikikernel.wikitext;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiLinkScannerTest {

    @Test
    void resolvesSubpageRelativeTargets() {
        assertEquals("帮助:格式/条目命名",
                WikiLinkScanner.resolveRelativeTarget("/条目命名", "帮助:格式"));
        assertEquals("帮助:格式/兄弟",
                WikiLinkScanner.resolveRelativeTarget("../兄弟", "帮助:格式/子页"));
        assertEquals("帮助:格式/子页",
                WikiLinkScanner.resolveRelativeTarget("./子页", "帮助:格式"));
        assertEquals("帮助:格式/章节#示例",
                WikiLinkScanner.resolveRelativeTarget("/章节#示例", "帮助:格式"));
    }

    @Test
    void linksToResolvesRelativeSubpageLinks() {
        String content = "前文 [[/条目命名|条目命名规范]] 后文";
        assertTrue(WikiLinkScanner.linksTo(content, "帮助:格式", "帮助:格式/条目命名"));
        assertFalse(WikiLinkScanner.linksTo(content, "帮助:格式", "帮助:格式/其他"));
        assertFalse(WikiLinkScanner.linksTo(content, "其他:页面", "帮助:格式/条目命名"));
    }

    @Test
    void pageLinksResolveAndSkipNonPageTargets() {
        String content = "[[/子页]][[分类:示例]][[文件:图片.png]][https://example.com 外链]";
        assertEquals(List.of("帮助:格式/子页"),
                WikiLinkScanner.pageLinks(content, "帮助:格式"));
    }

    @Test
    void redirectTargetsAlsoResolveSubpages() {
        assertEquals("帮助:格式/子页",
                WikiRedirect.targetOf("#REDIRECT [[/子页]]", "帮助:格式").orElseThrow());
        assertEquals("帮助:格式/兄弟",
                WikiRedirect.targetOf("#重定向 [[../兄弟]]", "帮助:格式/子页").orElseThrow());
    }
}
