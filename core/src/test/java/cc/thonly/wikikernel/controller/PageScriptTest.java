package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站点 / 页面自定义脚本：
 * 每个页面加载 {@code WikiKernel:Wiki.js} 与 {@code {title}/任意.js}（?action=raw 外部脚本）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PageScriptTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Test
    void headLoadsSiteScriptAndPageScript() throws Exception {
        wikiPageService.editPage(null, "WikiKernel:Wiki.js", "window.WIKI_SITE_JS = true;");
        wikiPageService.editPage(null, "WikiKernel:Common.js", "window.WIKI_COMMON_JS = true;");
        String page = "脚本测试页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");
        wikiPageService.editPage(null, page + "/自定义.js", "window.PAGE_JS = 1;");

        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                // 全站脚本：WikiKernel:Wiki.js
                .andExpect(content().string(containsString(
                        "/wiki/WikiKernel%3AWiki.js?action=raw&amp;ctype=text/javascript")))
                // 全站脚本：WikiKernel:Common.js
                .andExpect(content().string(containsString(
                        "/wiki/WikiKernel%3ACommon.js?action=raw&amp;ctype=text/javascript")))
                // 页面级脚本：{标题}/任意.js
                .andExpect(content().string(containsString(
                        "%E8%87%AA%E5%AE%9A%E4%B9%89.js?action=raw&amp;ctype=text/javascript")));
    }

    @Test
    void rawEndpointReturnsJavaScriptAndPlainText() throws Exception {
        wikiPageService.editPage(null, "WikiKernel:Wiki.js", "window.WIKI_SITE_JS = true;");

        mockMvc.perform(get("/wiki/{title}", "WikiKernel:Wiki.js")
                        .param("action", "raw")
                        .param("ctype", "text/javascript"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/javascript")))
                .andExpect(content().string(containsString("WIKI_SITE_JS")));

        // 不指定 ctype → text/plain
        mockMvc.perform(get("/wiki/{title}", "WikiKernel:Wiki.js").param("action", "raw"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/plain")));

        // 页面不存在 → 404
        mockMvc.perform(get("/wiki/{title}", "不存在的raw页面x").param("action", "raw"))
                .andExpect(status().isNotFound());
    }
}
