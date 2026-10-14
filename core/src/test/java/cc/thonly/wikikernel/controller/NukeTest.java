package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 大量删除（特殊:大量删除 + {@code POST /api/nuke/delete}，仿 MediaWiki Nuke）：
 * 权限（匿名 / 普通用户）、按用户名或前缀列出、批量删除与空选择的校验。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class NukeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void anonymousSeesNoPermissionAndNoForm() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:大量删除"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("需要管理员权限")))
                .andExpect(content().string(not(containsString("wiki-nuke-form"))));
    }

    @Test
    void normalUserAndAnonymousCannotDelete() throws Exception {
        WikiUser user = insertUser("大量删除普通用户" + System.nanoTime());

        // 匿名：401
        mockMvc.perform(post("/api/nuke/delete").param("titles", "任意页面"))
                .andExpect(jsonPath("$.code").value(401));

        // 普通用户：403
        mockMvc.perform(post("/api/nuke/delete").session(sessionOf(user))
                        .param("titles", "任意页面"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("需要管理员权限")));
    }

    @Test
    void adminListsPagesCreatedByUser() throws Exception {
        WikiUser admin = admin();
        WikiUser author = insertUser("大量删除作者" + System.nanoTime());
        String title1 = "大量删除测试页甲" + System.nanoTime();
        String title2 = "大量删除测试页乙" + System.nanoTime();
        wikiPageService.editPage(author, title1, "内容甲");
        wikiPageService.editPage(author, title2, "内容乙");

        mockMvc.perform(get("/wiki/{title}", "特殊:大量删除").session(sessionOf(admin))
                        .param("name", author.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("wiki-nuke-form")))
                .andExpect(content().string(containsString(title1)))
                .andExpect(content().string(containsString(title2)));
    }

    @Test
    void adminDeletesSelectedPages() throws Exception {
        WikiUser admin = admin();
        WikiUser author = insertUser("大量删除被删作者" + System.nanoTime());
        String title = "大量删除待删页" + System.nanoTime();
        wikiPageService.editPage(author, title, "待删内容");

        mockMvc.perform(post("/api/nuke/delete").session(sessionOf(admin))
                        .param("titles", title)
                        .param("reason", "测试批量删除"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(containsString("已删除")));

        // 页面已软删除
        assertTrue(wikiPageService.getByTitle(title).isEmpty());

        // 列表页不再出现该页面
        mockMvc.perform(get("/wiki/{title}", "特殊:大量删除").session(sessionOf(admin))
                        .param("prefix", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("没有找到符合条件的页面")))
                .andExpect(content().string(not(containsString("wiki-nuke-form"))));
    }

    @Test
    void unknownUsernameShowsEmpty() throws Exception {
        WikiUser admin = admin();
        mockMvc.perform(get("/wiki/{title}", "特殊:大量删除").session(sessionOf(admin))
                        .param("name", "不存在用户" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("没有找到符合条件的页面")));
    }

    @Test
    void noCriteriaShowsHint() throws Exception {
        WikiUser admin = admin();
        mockMvc.perform(get("/wiki/{title}", "特殊:大量删除").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请填写用户名或标题前缀")))
                .andExpect(content().string(containsString("nuke-search-form")))
                .andExpect(content().string(not(containsString("wiki-nuke-form"))));
    }

    @Test
    void noTitlesSelectedReturnsError() throws Exception {
        WikiUser admin = admin();
        mockMvc.perform(post("/api/nuke/delete").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("请先选择要删除的页面")));
    }

    @Test
    void titleListModeListsGivenPages() throws Exception {
        WikiUser admin = admin();
        String suffix = String.valueOf(System.nanoTime());
        String title1 = "大量删除列表甲" + suffix;
        String title2 = "大量删除列表乙" + suffix;
        wikiPageService.editPage(null, title1, "内容甲");
        wikiPageService.editPage(null, title2, "内容乙");

        String titles = title1 + "\n" + title2 + "\n" + "不存在页面" + suffix + "\n[[大量删除列表乙" + suffix + "]]\n"
                + title1 + "\n非法|标题";
        mockMvc.perform(get("/wiki/{title}", "特殊:大量删除").session(sessionOf(admin))
                        .param("titles", titles))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("wiki-nuke-form")))
                .andExpect(content().string(containsString(title1)))
                .andExpect(content().string(containsString(title2)))
                // 不存在的标题进入提示（不列出）
                .andExpect(content().string(containsString("不存在页面" + suffix)))
                .andExpect(content().string(containsString("以下标题不存在或已删除")))
                // 非法标题提示
                .andExpect(content().string(containsString("无效标题")));
    }

    @Test
    void titleListIgnoresUsernameAndPrefixAndDeletes() throws Exception {
        WikiUser admin = admin();
        String suffix = String.valueOf(System.nanoTime());
        String title = "大量删除直删" + suffix;
        wikiPageService.editPage(null, title, "内容");

        // 同时给了前缀（不匹配任何页面）与标题列表：列表优先
        mockMvc.perform(get("/wiki/{title}", "特殊:大量删除").session(sessionOf(admin))
                        .param("prefix", "绝不匹配的前缀" + suffix)
                        .param("titles", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(not(containsString("没有找到符合条件的页面"))));

        // 用标题列表直接删除
        mockMvc.perform(post("/api/nuke/delete").session(sessionOf(admin))
                        .param("titles", title)
                        .param("reason", "标题列表测试"))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(wikiPageService.getByTitle(title).isEmpty());

        // 删除后再用标题列表查询：进入「不存在」提示
        mockMvc.perform(get("/wiki/{title}", "特殊:大量删除").session(sessionOf(admin))
                        .param("titles", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("以下标题不存在或已删除")))
                .andExpect(content().string(not(containsString("wiki-nuke-form"))));
    }

    @Test
    void tooManyTitlesShowsError() throws Exception {
        WikiUser admin = admin();
        StringBuilder titles = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            titles.append("大量删除超限").append(i).append('\n');
        }
        mockMvc.perform(get("/wiki/{title}", "特殊:大量删除").session(sessionOf(admin))
                        .param("titles", titles.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("一次最多处理 500 个标题")))
                .andExpect(content().string(not(containsString("wiki-nuke-form"))));
    }

    // ==================== 工具 ====================

    private WikiUser admin() {
        WikiUser admin = insertUser("大量删除管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        return admin;
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
