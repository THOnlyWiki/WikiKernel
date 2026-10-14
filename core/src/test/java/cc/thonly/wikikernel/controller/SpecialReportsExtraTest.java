package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 更多统计 / 维护报告特殊页（仿 MediaWiki 数据与报告分组）：最多链接页面 / 最多链接模板 / 最多链接分类 /
 * 最多分类 / 最多修订 / 最少修订 / 待撰模板 / 待撰分类 / 待撰文件 / 未使用模板 / 未使用分类 /
 * 未分类分类 / 未分类模板 / 断链页面 / 受保护页面 / 媒体统计 / 未被监视页面。
 * <p>
 * 独立上下文（额外的测试属性）：扫描类报告只取最早的若干页面，与别的测试类共用内存库时
 * 本类创建的页面可能落在扫描窗口之外。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.reports-extra-test=true"
})
@AutoConfigureMockMvc
class SpecialReportsExtraTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private FileService fileService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiRevisionMapper wikiRevisionMapper;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void linkedTemplateCategoryAndRevisionReports() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        // 最多链接页面：两个页面链向同一目标
        String target = "AAA链接目标" + suffix;
        wikiPageService.editPage(null, target, "目标内容");
        wikiPageService.editPage(null, "AAA链接者甲" + suffix, "[[" + target + "]]");
        wikiPageService.editPage(null, "AAA链接者乙" + suffix, "[[" + target + "]]");
        mockMvc.perform(get("/wiki/{title}", "特殊:最多链接页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(target)));

        // 最多链接模板：一个模板被页面引用
        String template = "AAA模板" + suffix;
        wikiPageService.editPage(null, "模板:" + template, "模板内容");
        wikiPageService.editPage(null, "AAA嵌入页" + suffix, "{{" + template + "}}");
        mockMvc.perform(get("/wiki/{title}", "特殊:最多链接模板"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("模板:" + template)));

        // 最多链接分类：分类有成员
        String category = "AAA分类" + suffix;
        wikiPageService.editPage(null, "AAA分类成员" + suffix, "[[分类:" + category + "]]");
        mockMvc.perform(get("/wiki/{title}", "特殊:最多链接分类"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("分类:" + category)));

        // 最多分类：一个页面归入两个分类
        String multi = "AAA多分类页" + suffix;
        wikiPageService.editPage(null, multi, "[[分类:AAA分类甲" + suffix + "]]\n[[分类:AAA分类乙" + suffix + "]]");
        mockMvc.perform(get("/wiki/{title}", "特殊:最多分类"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(multi)));

        // 最多修订 / 最少修订：页面出现在列表里
        String revised = "AAA多修订页" + suffix;
        wikiPageService.editPage(null, revised, "第一版");
        wikiPageService.editPage(null, revised, "第二版");
        wikiPageService.editPage(null, revised, "第三版");
        mockMvc.perform(get("/wiki/{title}", "特殊:最多修订"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(revised)));
        mockMvc.perform(get("/wiki/{title}", "特殊:最少修订")).andExpect(status().isOk());
    }

    @Test
    void wantedAndUnusedReports() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        // 待撰模板：被调用但未创建
        String wantedTemplate = "AAA待撰模板" + suffix;
        wikiPageService.editPage(null, "AAA待撰模板页" + suffix, "{{" + wantedTemplate + "}}");
        mockMvc.perform(get("/wiki/{title}", "特殊:待撰模板"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("模板:" + wantedTemplate)));

        // 待撰分类：被使用但未创建
        String wantedCategory = "AAA待撰分类" + suffix;
        wikiPageService.editPage(null, "AAA待撰分类页" + suffix, "[[分类:" + wantedCategory + "]]");
        mockMvc.perform(get("/wiki/{title}", "特殊:待撰分类"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("分类:" + wantedCategory)));

        // 待撰文件：被嵌入但未上传
        String wantedFile = "AAA待撰文件" + suffix + ".png";
        wikiPageService.editPage(null, "AAA待撰文件页" + suffix, "[[文件:" + wantedFile + "]]");
        mockMvc.perform(get("/wiki/{title}", "特殊:待撰文件"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(wantedFile)));

        // 未使用模板 / 未分类模板：创建后没有页面使用、没有分类
        String unusedTemplate = "AAA未使用模板" + suffix;
        wikiPageService.editPage(null, "模板:" + unusedTemplate, "模板说明");
        mockMvc.perform(get("/wiki/{title}", "特殊:未使用模板"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("模板:" + unusedTemplate)));
        mockMvc.perform(get("/wiki/{title}", "特殊:未分类模板"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("模板:" + unusedTemplate)));

        // 未使用分类 / 未分类分类：创建后没有成员、没有分类
        String unusedCategory = "AAA未使用分类" + suffix;
        wikiPageService.editPage(null, "分类:" + unusedCategory, "分类说明");
        mockMvc.perform(get("/wiki/{title}", "特殊:未使用分类"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("分类:" + unusedCategory)));
        mockMvc.perform(get("/wiki/{title}", "特殊:未分类分类"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("分类:" + unusedCategory)));
    }

    @Test
    void deadendProtectedMediaAndUnwatchedReports() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        // 断链页面：正文里没有任何页面链接
        String deadend = "AAA断链页" + suffix;
        wikiPageService.editPage(null, deadend, "没有任何链接的正文");
        mockMvc.perform(get("/wiki/{title}", "特殊:断链页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(deadend)));

        // 受保护页面
        String protectedTitle = "AAA受保护页" + suffix;
        insertPage(protectedTitle, "受保护内容", true);
        mockMvc.perform(get("/wiki/{title}", "特殊:受保护页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(protectedTitle)))
                .andExpect(content().string(containsString("全保护")));

        // 媒体统计：按 MIME 类型统计
        String fileName = "AAA媒体统计" + suffix + ".png";
        fileService.store(null, fileName, "", new byte[]{1, 2, 3, 4});
        mockMvc.perform(get("/wiki/{title}", "特殊:媒体统计"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("image/png")));

        // 未被监视页面：匿名用户看不到（仅管理员）
        mockMvc.perform(get("/wiki/{title}", "特殊:未被监视页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("只有管理员可以查看未被监视页面")));

        // 管理员可以看到页面列表
        WikiUser admin = adminUser();
        mockMvc.perform(get("/wiki/{title}", "特殊:未被监视页面").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(deadend)));
    }

    @Test
    void newReportsAreListedOnSpecialPages() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:特殊页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("最多链接页面")))
                .andExpect(content().string(containsString("待撰模板")))
                .andExpect(content().string(containsString("媒体统计")))
                .andExpect(content().string(containsString("未被监视页面")));
    }

    // ==================== 工具 ====================

    private WikiUser adminUser() {
        WikiUser user = new WikiUser();
        user.setUsername("报告管理员" + System.nanoTime());
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_ADMIN));
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }

    private void insertPage(String title, String content, boolean protectedPage) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 6, 12, 0, 0);
        WikiPage page = new WikiPage();
        page.setTitle(title);
        page.setHided(false);
        page.setDeleted(false);
        page.setProtectedPage(protectedPage);
        page.setCreateAt(now);
        page.setUpdateAt(now);
        wikiPageMapper.insert(page);

        WikiRevision revision = new WikiRevision();
        revision.setPageId(page.getId());
        revision.setContent(content);
        revision.setDeleted(false);
        revision.setCreateAt(now);
        wikiRevisionMapper.insert(revision);

        page.setLatestRevisionId(revision.getId());
        wikiPageMapper.updateById(page);
    }
}
