package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
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
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 工具箱页面：链入页面 / 相关更改 / 用户贡献 / 日志 / 页面信息。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class UserToolsPagesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiRevisionMapper wikiRevisionMapper;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Test
    void whatLinksHereFindsLinkingPages() throws Exception {
        insertPage("链接目标页", "目标", null);
        insertPage("链接来源页", "见 [[链接目标页]] 的说明", null);
        insertPage("无关来源页", "没有链接", null);

        mockMvc.perform(get("/wiki/{title}", "特殊:链入页面").param("page", "链接目标页"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("链接来源页")))
                .andExpect(content().string(not(containsString("无关来源页"))));
    }

    @Test
    void relatedChangesShowsLinkingPageEdits() throws Exception {
        insertPage("相关目标页", "目标", null);
        insertPage("相关来源页", "[[相关目标页]]", null);

        mockMvc.perform(get("/wiki/{title}", "特殊:相关更改").param("page", "相关目标页"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("相关来源页")));
    }

    @Test
    void userContributionsListsEdits() throws Exception {
        WikiUser user = insertUser("贡献测试用户");
        insertPage("贡献测试条目", "内容", user.getId());

        mockMvc.perform(get("/wiki/{title}", "特殊:用户贡献").param("name", user.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(user.getUsername())))
                .andExpect(content().string(containsString("贡献测试条目")))
                // 每条贡献可编辑对应历史版本（链接文案走 common.edit 消息，可能是「编辑」/「编辑源代码」）
                .andExpect(content().string(containsString("?action=edit&amp;oldid=")))
                .andExpect(content().string(containsString(">编辑")))
                .andExpect(content().string(containsString(">当前</span>")));
    }

    @Test
    void logPageAndRightsPageShowEntries() throws Exception {
        WikiUser admin = insertUser("日志管理员");
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        WikiUser target = insertUser("日志目标用户");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, admin.toTransferCopy());
        mockMvc.perform(post("/api/user/permissions")
                        .session(session)
                        .param("username", target.getUsername())
                        .param("permissions", PermissionService.GROUP_ADMIN))
                .andExpect(jsonPath("$.code").value(200));

        // 日志页（按用户过滤）
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("name", target.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已授予")))
                .andExpect(content().string(containsString("管理员")))
                .andExpect(content().string(containsString("日志管理员")));

        // 用户权限页底部的日志区
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限").param("name", target.getUsername()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已授予")));
    }

    @Test
    void emptyLogShowsMediaWikiMessage() throws Exception {
        WikiUser quiet = insertUser("无日志用户");

        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("name", quiet.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("在日志中不存在匹配项")));
    }

    @Test
    void pageInfoShowsPageAndUserInfo() throws Exception {
        insertPage("信息测试页", "正文", null);
        mockMvc.perform(get("/wiki/{title}", "特殊:页面信息").param("page", "信息测试页"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("信息测试页")))
                .andExpect(content().string(containsString("页面长度")))
                .andExpect(content().string(containsString("总编辑数")))
                .andExpect(content().string(containsString("不同作者总数")));

        // ?action=info（仿 MediaWiki）：同样的「页面信息」表格，标题为“X”的信息
        mockMvc.perform(get("/wiki/{title}", "信息测试页").param("action", "info"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("“信息测试页”的信息")))
                .andExpect(content().string(containsString("页面ID")))
                .andExpect(content().string(containsString("页面创建者")))
                .andExpect(content().string(containsString("最后编辑者")))
                .andExpect(content().string(containsString("mw-page-info")));

        WikiUser user = insertUser("信息测试用户");
        permissionService.grantDefault(user.getId());
        mockMvc.perform(get("/wiki/{title}", "特殊:页面信息").param("page", "用户:" + user.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("这是用户页")))
                .andExpect(content().string(containsString("编辑贡献")));

        // 特殊页的 ?action=info：显示基本信息表格
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改").param("action", "info"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("“特殊:最近更改”的信息")))
                .andExpect(content().string(containsString("页面内容类型")))
                .andExpect(content().string(containsString("特殊页面")));
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

    private void insertPage(String title, String content, Long userId) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 6, 12, 0, 0);
        WikiPage page = new WikiPage();
        page.setTitle(title);
        page.setHided(false);
        page.setDeleted(false);
        page.setCreateAt(now);
        page.setUpdateAt(now);
        wikiPageMapper.insert(page);

        WikiRevision revision = new WikiRevision();
        revision.setPageId(page.getId());
        revision.setContent(content);
        revision.setUserId(userId);
        revision.setDeleted(false);
        revision.setCreateAt(now);
        wikiRevisionMapper.insert(revision);

        page.setLatestRevisionId(revision.getId());
        wikiPageMapper.updateById(page);
    }
}
