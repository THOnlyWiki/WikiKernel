package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 报告类特殊页（仿 MediaWiki）：统计 / 前缀索引 / 最长最短最旧 / 孤立 / 待撰 / 未分类 /
 * 未使用文件 / 最新文件 / 三种重定向报告 / 用户列表 / 活跃用户，以及重定向型特殊页
 * （重定向 / 编辑页面 / 固定链接 / 差异）。
 * <p>
 * 独立上下文（额外的测试属性）：待撰页面等报告只扫描最早的若干页面，和别的测试类共用内存库时，
 * 本类自己创建的页面可能落在扫描窗口之外（曾因此在全量测试里偶发失败）。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.reports-test=true"
})
@AutoConfigureMockMvc
class SpecialReportsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private FileService fileService;

    @Test
    void reportPagesRender() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        // 以 AAA 开头，确保在按标题排序的报告里排在最前（测试库是共享的）
        String lonely = "AAA孤立页" + suffix;
        String wanted = "AAA待撰页" + suffix;
        String linker = "AAA链接页" + suffix;
        String redirect = "AAA重定向" + suffix;
        String broken = "AAA坏重定向" + suffix;
        String doubles = "AAA双重重定向" + suffix;
        String uncategorized = "AAA未分类页" + suffix;
        wikiPageService.editPage(null, lonely, "孤立的简短内容");
        wikiPageService.editPage(null, linker, "[[" + wanted + "]]");
        wikiPageService.editPage(null, redirect, "#REDIRECT [[" + linker + "]]");
        wikiPageService.editPage(null, broken, "#REDIRECT [[AAA不存在目标" + suffix + "]]");
        wikiPageService.editPage(null, doubles, "#REDIRECT [[" + redirect + "]]");
        wikiPageService.editPage(null, uncategorized, "没有分类的内容");
        String fileName = "AAA报告文件" + suffix + ".png";
        fileService.store(null, fileName, "", new byte[]{1, 2, 3});

        mockMvc.perform(get("/wiki/{title}", "特殊:统计"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("页面总数")))
                .andExpect(content().string(containsString("管理员数")));

        mockMvc.perform(get("/wiki/{title}", "特殊:前缀索引").param("prefix", "AAA孤立页"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(lonely)));

        mockMvc.perform(get("/wiki/{title}", "特殊:最长页面")).andExpect(status().isOk());
        mockMvc.perform(get("/wiki/{title}", "特殊:最短页面")).andExpect(status().isOk());
        mockMvc.perform(get("/wiki/{title}", "特殊:最旧页面")).andExpect(status().isOk());

        mockMvc.perform(get("/wiki/{title}", "特殊:孤立页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(lonely)));

        mockMvc.perform(get("/wiki/{title}", "特殊:待撰页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(wanted)));

        mockMvc.perform(get("/wiki/{title}", "特殊:未分类页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(uncategorized)));

        mockMvc.perform(get("/wiki/{title}", "特殊:未使用文件")).andExpect(status().isOk());

        mockMvc.perform(get("/wiki/{title}", "特殊:最新文件"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(fileName)));

        mockMvc.perform(get("/wiki/{title}", "特殊:重定向列表"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(redirect)));

        mockMvc.perform(get("/wiki/{title}", "特殊:双重重定向"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(doubles)));

        mockMvc.perform(get("/wiki/{title}", "特殊:损坏的重定向"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(broken)));

        mockMvc.perform(get("/wiki/{title}", "特殊:用户列表")).andExpect(status().isOk());
        mockMvc.perform(get("/wiki/{title}", "特殊:活跃用户")).andExpect(status().isOk());

        // 特殊页面列表里能看到这些新页面
        mockMvc.perform(get("/wiki/{title}", "特殊:特殊页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("最长页面")))
                .andExpect(content().string(containsString("待撰页面")));
    }

    @Test
    void redirectTypePagesWork() throws Exception {
        String title = "AAA跳转目标页" + System.nanoTime();
        wikiPageService.editPage(null, title, "内容");
        Long revision = wikiPageService.getByTitle(title).orElseThrow().getLatestRevisionId();

        mockMvc.perform(get("/wiki/{title}", "特殊:编辑页面").param("page", title))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/wiki/{title}", "特殊:固定链接").param("page", title)
                        .param("oldid", String.valueOf(revision)))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/wiki/{title}", "特殊:差异").param("diff", String.valueOf(revision)))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/wiki/{title}", "特殊:重定向").param("page", "page/" + title))
                .andExpect(status().is3xxRedirection());
        // 缺少参数：显示用法提示
        mockMvc.perform(get("/wiki/{title}", "特殊:编辑页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("特殊:编辑页面/页面标题")));
    }
}
