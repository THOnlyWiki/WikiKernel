package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未知页面红链（仿 MediaWiki）：[[目标]] 不存在 → {@code class="new"} 红链；
 * 已存在页面、特殊页链接不标红。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class RedLinkTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Test
    void missingPageLinksAreRed() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String existing = "红链目标" + suffix;
        String ghost = "红链幽灵" + suffix;
        String source = "红链来源" + suffix;
        wikiPageService.editPage(null, existing, "存在的页面");
        wikiPageService.editPage(null, source,
                "已存在：[[" + existing + "]]，不存在：[[%s]]，特殊页：[[特殊:日志]]，带文字：[[%s|自定义文字]]"
                        .formatted(ghost, ghost));

        mockMvc.perform(get("/wiki/{title}", source))
                .andExpect(status().isOk())
                // 存在的页面是普通链接
                .andExpect(content().string(containsString(
                        "<a href=\"/wiki/" + existing + "\">" + existing + "</a>")))
                // 不存在的页面是红链（class="new"）
                .andExpect(content().string(containsString(
                        "<a class=\"new\" href=\"/wiki/" + ghost + "\">" + ghost + "</a>")))
                .andExpect(content().string(containsString(
                        "<a class=\"new\" href=\"/wiki/" + ghost + "\">自定义文字</a>")))
                // 特殊页链接（没有页面行）不标红
                .andExpect(content().string(not(containsString("class=\"new\" href=\"/wiki/特殊:"))))
                // 存在的页面不会标红
                .andExpect(content().string(not(containsString("class=\"new\" href=\"/wiki/" + existing))));
    }

    @Test
    void deletedPageBecomesRedLink() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String gone = "红链删除页" + suffix;
        String source = "红链删除来源" + suffix;
        wikiPageService.editPage(null, gone, "将要删除");
        wikiPageService.editPage(null, source, "[[" + gone + "]]");
        wikiPageService.deletePage(null, gone);

        mockMvc.perform(get("/wiki/{title}", source))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<a class=\"new\" href=\"/wiki/" + gone + "\">" + gone + "</a>")));
    }
}
