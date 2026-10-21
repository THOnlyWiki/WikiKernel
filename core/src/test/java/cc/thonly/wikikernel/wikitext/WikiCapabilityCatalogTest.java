package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.common.service.WikiCapabilityCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI Agent 能力目录的回归测试：确保 core 标签、特殊页和插件目录能被汇总，
 * 而不是在系统提示里只写死一句「支持 Wikitext」。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class WikiCapabilityCatalogTest {

    @Autowired
    private WikiCapabilityCatalog catalog;

    @Test
    void promptContainsCoreTagsFunctionsAndSpecialPages() {
        String prompt = catalog.promptSection();
        assertFalse(prompt.isBlank());
        assertTrue(prompt.contains("### 解析器标签"), prompt);
        assertTrue(prompt.contains("<ref>"), prompt);
        assertTrue(prompt.contains("{{#if:"), prompt);
        assertTrue(prompt.contains("{{PAGENAME}}"), prompt);
        assertTrue(prompt.contains("### 特殊页面"), prompt);
    }
}
