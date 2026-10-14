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
 * 输入框（InputBox）已迁到插件 {@code input-box}：core 不再解析 {@code <inputbox>}，
 * 插件未加载时标签按原文转义显示（与迁移前“功能关闭”一致）。
 * 表单渲染与 {@code features.input-box} 开关由插件测试 {@code InputBoxTagTest} 覆盖。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class InputBoxTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Test
    void coreRendersInputBoxTagAsTextWithoutPlugin() throws Exception {
        String title = "输入框页" + System.nanoTime();
        wikiPageService.editPage(null, title, """
                <inputbox>
                type=create
                buttonlabel=新建
                </inputbox>""");
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("class=\"inputbox\""))))
                .andExpect(content().string(containsString("&lt;inputbox&gt;")));
    }
}
