package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 差异对比页（仿 MediaWiki DifferenceEngine）：{@code ?diff=prev/next/<id>/cur[&oldid=N]}、
 * 两栏 diff（删除黄色 / 新增蓝色 / 行内加粗）、首版提示、无差异提示、404 与历史 / 最近更改的链接。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class DiffPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void diffPrevAndCurShowLineChanges() throws Exception {
        WikiUser user = insertUser("差异测试用户" + System.nanoTime());
        String title = "差异测试页" + System.nanoTime();
        wikiPageService.editPage(user, title, "第一行", "第一版");
        wikiPageService.editPage(user, title, "第一行\n第二行\n第三行", "第二版");
        wikiPageService.editPage(user, title, "第一行\n第二行改\n第三行", "第三版");
        WikiPage page = wikiPageService.getVisibleByTitle(title).orElseThrow();
        List<WikiRevision> history = wikiPageService.getHistory(page.getId());
        long rev1 = history.get(2).getId();
        long rev2 = history.get(1).getId();
        long rev3 = history.get(0).getId();

        // diff=prev&oldid=rev2：rev1 → rev2（第一版之前没有版本 → 只有新增、无删除）
        mockMvc.perform(get("/wiki/{title}", title).param("diff", "prev").param("oldid", String.valueOf(rev2)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("diff-addedline")))
                .andExpect(content().string(containsString("第二行")))
                .andExpect(content().string(containsString("第三行")))
                .andExpect(content().string(containsString("修订 " + rev2)))
                .andExpect(content().string(containsString("下一差异")))
                .andExpect(content().string(not(containsString("diff-deletedline"))));

        // diff=cur&oldid=rev2：rev2 → 当前版本 rev3（有删除、新增与行内加粗；rev3 是当前版本 → 无「下一差异」）
        mockMvc.perform(get("/wiki/{title}", title).param("diff", "cur").param("oldid", String.valueOf(rev2)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("diff-deletedline")))
                .andExpect(content().string(containsString("diff-addedline")))
                .andExpect(content().string(containsString("第二行")))
                .andExpect(content().string(containsString(">改</span>")))
                .andExpect(content().string(containsString("diffchange")))
                .andExpect(content().string(containsString("上一差异")))
                .andExpect(content().string(not(containsString("下一差异"))));

        // 只给 diff=rev3：旧侧自动取 rev3 的上一版本
        mockMvc.perform(get("/wiki/{title}", title).param("diff", String.valueOf(rev3)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("diffchange")));

        // 第一版的 diff=prev：显示「这是最早的版本」
        mockMvc.perform(get("/wiki/{title}", title).param("diff", "prev").param("oldid", String.valueOf(rev1)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("（这是最早的版本）")));

        // 内容相同的两次编辑：没有差异。
        wikiPageService.editPage(user, title, "第一行\n第二行改\n第三行", "第四版（内容相同）");
        long rev4 = wikiPageService.getVisibleByTitle(title).orElseThrow().getLatestRevisionId();
        mockMvc.perform(get("/wiki/{title}", title).param("diff", "prev").param("oldid", String.valueOf(rev4)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("没有差异。")));

        // 不存在的版本 → 404
        mockMvc.perform(get("/wiki/{title}", title).param("diff", "999999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyAndRecentChangesLinkToDiffs() throws Exception {
        WikiUser user = insertUser("差异链接用户" + System.nanoTime());
        String title = "差异链接页" + System.nanoTime();
        wikiPageService.editPage(user, title, "v1", "第一版");
        wikiPageService.editPage(user, title, "v2", "第二版");

        // 历史页：每行有「差异」（prev）与「当前」（cur）链接
        mockMvc.perform(get("/wiki/{title}", title).param("action", "history"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("diff=prev&amp;oldid=")))
                .andExpect(content().string(containsString("diff=cur&amp;oldid=")));

        // 旧版本阅读页也有「与当前版本的差异」链接
        long rev1 = wikiPageService.getHistory(
                wikiPageService.getVisibleByTitle(title).orElseThrow().getId()).get(1).getId();
        mockMvc.perform(get("/wiki/{title}", title).param("oldid", String.valueOf(rev1)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("与当前版本的差异")));

        // 最近更改的「差异」链接指向 diff=prev
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("diff=prev&amp;oldid=")));

        // 用户贡献每条也有差异链接
        mockMvc.perform(get("/wiki/{title}", "特殊:用户贡献").param("name", user.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("diff=prev&amp;oldid=")));
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
