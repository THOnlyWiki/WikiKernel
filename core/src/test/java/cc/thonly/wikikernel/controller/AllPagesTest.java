package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code 特殊:全部页面}（仿 MediaWiki Special:AllPages）：
 * 命名空间筛选、标题范围（开始于 / 结束于，结束标题包含）、隐藏重定向、三列列表与标题翻页。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class AllPagesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Test
    void filtersByNamespaceRangeAndRedirects() throws Exception {
        WikiUser user = insertUser("全部页面用户" + System.nanoTime());

        String suffix = String.valueOf(System.nanoTime());
        String prefix = "全部页排序" + suffix + "-";
        String a = prefix + "A";
        String b = prefix + "B";
        String c = prefix + "C";
        String d = prefix + "D";
        wikiPageService.editPage(user, a, "正文");
        wikiPageService.editPage(user, b, "#REDIRECT [[" + a + "]]");
        wikiPageService.editPage(user, c, "正文");
        wikiPageService.editPage(user, d, "正文");
        String userPage = "用户:" + prefix + "用户页";
        wikiPageService.editPage(user, userPage, "正文");

        // 从 A 开始（默认全部命名空间）：A B C D 都在；三列列表，无「更新于」
        mockMvc.perform(get("/wiki/{title}", "特殊:全部页面").param("from", a))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"allpages-chunk\"")))
                .andExpect(content().string(containsString(">" + a + "</a>")))
                .andExpect(content().string(containsString(">" + b + "</a>")))
                .andExpect(content().string(containsString(">" + c + "</a>")))
                .andExpect(content().string(containsString(">" + d + "</a>")))
                .andExpect(content().string(not(containsString("更新于"))));

        // 结束于 C（包含 C）：D 不出现
        mockMvc.perform(get("/wiki/{title}", "特殊:全部页面")
                        .param("from", a).param("to", c))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">" + c + "</a>")))
                .andExpect(content().string(not(containsString(">" + d + "</a>"))));

        // 隐藏重定向：B（#REDIRECT）不出现，A / C 仍在
        mockMvc.perform(get("/wiki/{title}", "特殊:全部页面")
                        .param("from", a).param("hideredirects", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">" + a + "</a>")))
                .andExpect(content().string(containsString(">" + c + "</a>")))
                .andExpect(content().string(not(containsString(">" + b + "</a>"))));

        // 命名空间 = 用户：只剩用户页
        mockMvc.perform(get("/wiki/{title}", "特殊:全部页面")
                        .param("from", a).param("namespace", "user"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">" + userPage + "</a>")))
                .andExpect(content().string(not(containsString(">" + a + "</a>"))));
    }

    @Test
    void paginatesWithNextAndPreviousLinks() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String prefix = "翻页测试" + suffix + "-";
        for (int i = 0; i < 105; i++) {
            insertPage(prefix + String.format("%03d", i));
        }

        // 第一块：出现「下一页（第 101 条标题）」
        mockMvc.perform(get("/wiki/{title}", "特殊:全部页面").param("from", prefix))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">" + prefix + "000</a>")))
                .andExpect(content().string(containsString("下一页（" + prefix + "100）")));

        // 第二块（从第 101 条开始）：只剩 100–104，出现「上一页」，不再有 000–099
        mockMvc.perform(get("/wiki/{title}", "特殊:全部页面").param("from", prefix + "100"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">" + prefix + "104</a>")))
                .andExpect(content().string(containsString("上一页（")))
                .andExpect(content().string(not(containsString(">" + prefix + "099</a>"))));
    }

    // ==================== 工具 ====================

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    /** 批量造数据（不建历史版本）：分页 / 范围测试够用。 */
    private void insertPage(String title) {
        LocalDateTime now = LocalDateTime.now();
        WikiPage page = new WikiPage();
        page.setTitle(title);
        page.setHided(false);
        page.setDeleted(false);
        page.setProtectedPage(false);
        page.setCreateAt(now);
        page.setUpdateAt(now);
        wikiPageMapper.insert(page);
    }
}
