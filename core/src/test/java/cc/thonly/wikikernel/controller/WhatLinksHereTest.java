package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 特殊:链入页面（仿 MediaWiki WhatLinksHere）：
 * 筛选表单、嵌入 / 重定向标记与工具链接、隐藏选项、命名空间过滤、统计与翻页。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class WhatLinksHereTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    private static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String page(String title, String... params) throws Exception {
        var request = get("/wiki/{title}", "特殊:链入页面").param("page", title);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void listsLinksTransclusionsAndRedirectsWithMarkers() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String target = "链入目标" + suffix;
        String linker = "链入来源" + suffix;
        String redirector = "链入重定向" + suffix;
        String template = "链入模板" + suffix;
        String transcluder = "链入嵌入" + suffix;
        wikiPageService.editPage(null, target, "目标正文");
        wikiPageService.editPage(null, linker, "[[" + target + "]]");
        wikiPageService.editPage(null, redirector, "#REDIRECT [[" + target + "]]");
        wikiPageService.editPage(null, "模板:" + template, "模板正文");
        wikiPageService.editPage(null, transcluder, "{{模板:" + template + "}}");

        String html = mockMvc.perform(get("/wiki/{title}", "特殊:链入页面").param("page", target))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("链接至“" + target + "”的页面")))
                .andExpect(content().string(containsString("以下页面链接至" + target + "：")))
                .andExpect(content().string(containsString("显示2项。")))
                .andExpect(content().string(containsString(">" + linker + "</a>")))
                .andExpect(content().string(containsString(">" + redirector + "</a>")))
                .andExpect(content().string(containsString("（重定向页面）")))
                .andExpect(content().string(containsString("mw-redirect")))
                .andExpect(content().string(containsString("redirect=no")))
                .andExpect(content().string(containsString("←链接")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 工具链接指向来源页的链入页面
        assertTrue(html.contains(q(linker)), "工具链接应指向来源页 " + linker);

        // 模板反查：使用模板的页面带「（嵌入）」标记，且不出现无关标题
        String transHtml = page("模板:" + template);
        assertTrue(transHtml.contains(">" + transcluder + "</a>"), "嵌入页应出现");
        assertTrue(transHtml.contains("（嵌入）"), "应有（嵌入）标记");
        assertFalse(transHtml.contains(">" + linker + "</a>"), "无关页面不应出现");

        // 隐藏链入：普通链入消失，重定向保留
        String hiddenLinks = page(target, "hidelinks", "1");
        assertFalse(hiddenLinks.contains(">" + linker + "</a>"), "隐藏链入后普通链接不出现");
        assertTrue(hiddenLinks.contains(">" + redirector + "</a>"), "隐藏链入不影响重定向");

        // 隐藏重定向：重定向消失，普通链入保留
        String hiddenRedirects = page(target, "hideredirs", "1");
        assertFalse(hiddenRedirects.contains(">" + redirector + "</a>"), "隐藏重定向后重定向不出现");
        assertTrue(hiddenRedirects.contains(">" + linker + "</a>"), "隐藏重定向不影响普通链入");

        // 隐藏嵌入：模板反查为空
        String hiddenTrans = page("模板:" + template, "hidetrans", "1");
        assertFalse(hiddenTrans.contains(">" + transcluder + "</a>"), "隐藏嵌入后嵌入页不出现");
    }

    @Test
    void listsRelativeSubpageLinks() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String parent = "链入父页" + suffix;
        String child = parent + "/子页";
        wikiPageService.editPage(null, child, "子页正文");
        wikiPageService.editPage(null, parent, "[[/子页|子页链接]]");

        mockMvc.perform(get("/wiki/{title}", "特殊:链入页面").param("page", child))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">" + parent + "</a>")));
    }

    @Test
    void filtersByNamespaceAndPaginates() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String target = "翻页目标" + suffix;
        wikiPageService.editPage(null, target, "目标");
        for (int i = 1; i <= 3; i++) {
            wikiPageService.editPage(null, "翻页来源" + i + " " + suffix, "[[" + target + "]]");
        }
        String templateSource = "模板:翻页来源" + suffix;
        wikiPageService.editPage(null, templateSource, "[[" + target + "]]");

        // 命名空间过滤：只看「模板」命名空间（参数值是命名空间的稳定 id）
        String nsHtml = page(target, "namespace", "template");
        assertTrue(nsHtml.contains(">" + templateSource + "</a>"), "模板命名空间的来源应出现");
        assertFalse(nsHtml.contains(">翻页来源1 " + suffix + "</a>"), "主命名空间来源应被过滤掉");

        // 翻页：limit=1 -> 显示总数 4，只渲染 1 行；排序按标题，「模板:」在「翻页」之前
        String firstPage = page(target, "limit", "1");
        assertTrue(firstPage.contains("显示4项。"), "统计的是筛选后的总数");
        assertEquals(1, firstPage.split("mw-whatlinkshere-tools", -1).length - 1, "limit=1 时应只显示一行");
        assertTrue(firstPage.contains("下1条"), "应有下一页链接");
        assertTrue(firstPage.contains(">" + templateSource + "</a>"), "第一条按标题排序应是模板命名空间那条");
        assertFalse(firstPage.contains(">翻页来源1 " + suffix + "</a>"), "第一页不应出现后面的条目");

        // 下一页（游标 = 当前页最后一条标题）
        String secondPage = page(target, "limit", "1", "from", templateSource, "dir", "next");
        assertTrue(secondPage.contains(">翻页来源1 " + suffix + "</a>"), "下一页应是下一条");
        assertTrue(secondPage.contains("上1条"), "应有上一页链接");

        // 上一页方向
        String previous = page(target, "limit", "1", "from", "翻页来源1 " + suffix, "dir", "prev");
        assertTrue(previous.contains(">" + templateSource + "</a>"), "上一页应回到前一条");
    }
}
