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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数学公式（Math）已迁到插件 {@code math}：core 未加载插件时 {@code <math>} / {@code <chem>}
 * 按原文转义显示；公式占位与开关行为由插件测试 {@code MathTagTest} 覆盖。
 * KaTeX 运行时（{@code /vendor/katex/…}）仍随 core 分发，这里验证静态资源可访问。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class MathTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Test
    void mathTagsAreUnknownToCoreWithoutPlugin() throws Exception {
        String title = "数学演示页" + System.nanoTime();
        wikiPageService.editPage(null, title,
                "行内：<math>a^2 + b^2 = c^2</math>\n\n<chem>2H_2 + O_2 -> 2H_2O</chem>");
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;math&gt;")))
                .andExpect(content().string(containsString("&lt;chem&gt;")))
                .andExpect(content().string(not(containsString("class=\"mw-math\""))))
                .andExpect(content().string(not(containsString("/plugins/math/math.js"))));
    }

    @Test
    void katexAssetsAreServed() throws Exception {
        mockMvc.perform(get("/vendor/katex/katex.min.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("katex")));
        mockMvc.perform(get("/vendor/katex/katex.min.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("css")));
        mockMvc.perform(get("/vendor/katex/contrib/mhchem.min.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")));
    }
}
