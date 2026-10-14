package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 页脚（MediaWiki 风格）：
 * <ul>
 *     <li>默认显示「此页面最后编辑于…」信息行 + 隐私政策 / 关于WikiKernel / 免责声明 三链接 + 语言链接；</li>
 *     <li>特殊页没有版本，不显示「最后编辑」行，但三链接照常；</li>
 *     <li>{@code WikiKernel:Footer-Left} / {@code WikiKernel:Footer-Right} 页面存在时替换左右内容。</li>
 * </ul>
 * <p>
 * 独立上下文（额外的测试属性）：避免创建的页脚页面影响其它用例。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.footer-test=true"
})
@AutoConfigureMockMvc
class FooterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Test
    void footerLeftAndRightPagesControlFooterContent() throws Exception {
        String page = "页脚测试页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");

        // 默认：MediaWiki 风格页脚（最后编辑行 + 隐私政策 / 关于 / 免责声明 + 语言链接）
        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("footer-info-lastmod")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*此页面最后编辑于\\d{4}年\\d{1,2}月\\d{1,2}日 \\(星期[一二三四五六日]\\) \\d{2}:\\d{2}。.*")))
                .andExpect(content().string(containsString("隐私政策")))
                .andExpect(content().string(containsString("关于WikiKernel")))
                .andExpect(content().string(containsString("免责声明")))
                .andExpect(content().string(containsString("footer-languages")))
                // 左区 / 右区结构：三链接在左区、语言在右区
                .andExpect(content().string(containsString("class=\"footer-left\"")))
                .andExpect(content().string(containsString("class=\"footer-right\"")))
                .andExpect(content().string(containsString("footer-places-list")))
                .andExpect(content().string(containsString("footer-languages-list")))
                .andExpect(content().string(not(containsString("footer-custom"))));

        // 特殊页：同样有底部三链接，但特殊页没有版本，不显示「最后编辑」行
        mockMvc.perform(get("/wiki/{title}", "特殊:权限配置"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("隐私政策")))
                .andExpect(content().string(not(containsString("footer-info-lastmod"))));

        // 两个页脚页面存在后：左区替换内置三链接（左对齐），右区替换语言链接（右对齐）
        wikiPageService.editPage(null, "WikiKernel:Footer-Left", "左侧自定义页脚");
        wikiPageService.editPage(null, "WikiKernel:Footer-Right", "'''右侧'''自定义页脚");

        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("左侧自定义页脚")))
                .andExpect(content().string(containsString("<b>右侧</b>自定义页脚")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*footer-custom-left[^>]*>.*?左侧自定义页脚.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*footer-custom-right[^>]*>.*?<b>右侧</b>自定义页脚.*")))
                .andExpect(content().string(not(containsString("隐私政策"))))
                // 「Powered by」标识行属于页脚图标区（footer-icons），不随 Footer-Left/Right 页面被替换
                .andExpect(content().string(containsString("Powered by Spring Boot")))
                .andExpect(content().string(not(containsString("footer-languages"))));
    }
}
