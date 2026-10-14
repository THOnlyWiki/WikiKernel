package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 特殊:最近更改：同一词条折叠成组、首版标记、隐藏页排除、本地化。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class RecentChangesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiRevisionMapper wikiRevisionMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private LogService logService;

    @Autowired
    private PermissionService permissionService;

    @Test
    void groupsEditsOfSamePageIntoCollapsibleGroup() throws Exception {
        WikiUser actor = insertUser("最近更改用户" + System.nanoTime());
        String title = "折叠词条" + System.nanoTime();
        wikiPageService.editPage(actor, title, "第一版");
        wikiPageService.editPage(actor, title, "第二版", "第二次编辑");
        wikiPageService.editPage(actor, title, "第三版", "第三次编辑");

        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                // 页面说明与过滤器栏（仿 MediaWiki）
                .andExpect(content().string(containsString("此页面可以追踪本wiki的最近更改")))
                .andExpect(content().string(containsString("激活的过滤器")))
                .andExpect(content().string(containsString("人工（非机器人）")))
                .andExpect(content().string(containsString("动态更新")))
                .andExpect(content().string(containsString("缩略列表")))
                // 同一词条三次编辑折叠成一组
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString("3次更改")))
                .andExpect(content().string(containsString("<details class=\"rc-group\"")))
                // 动态更新用的稳定键
                .andExpect(content().string(containsString("data-key=\"g-")))
                // 行内元素：新页面标记 / 差异 / 历史 / 留言 / 贡献 / 字节变化
                .andExpect(content().string(containsString(">新</b>")))
                .andExpect(content().string(containsString(">差异</a>")))
                .andExpect(content().string(containsString(">历史</a>")))
                .andExpect(content().string(containsString(">留言</a>")))
                .andExpect(content().string(containsString(">贡献</a>")))
                .andExpect(content().string(containsString("rc-bytes-plus")))
                // 过滤器下拉链接必须是“特殊:最近更改”前缀（回归：曾经生成 /wiki/最近更改）
                .andExpect(content().string(containsString(
                        "/wiki/%E7%89%B9%E6%AE%8A%3A%E6%9C%80%E8%BF%91%E6%9B%B4%E6%94%B9?limit=100&amp;days=7")))
                .andExpect(content().string(containsString("第二次编辑")))
                .andExpect(content().string(containsString("第三次编辑")))
                .andExpect(content().string(containsString(actor.getUsername())));
    }

    @Test
    void englishAliasAvailableAndHiddenPagesExcluded() throws Exception {
        // 英文 + 别名
        mockMvc.perform(get("/wiki/{title}", "Special:RecentChanges").param("uselang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Recent changes")))
                .andExpect(content().string(containsString("changes")));

        // 已隐藏页面不出现
        String hiddenTitle = "隐藏最近更改词条" + System.nanoTime();
        insertHiddenPage(hiddenTitle);

        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(hiddenTitle))));
    }

    @Test
    void logLinesLinkLabelAndTarget() throws Exception {
        String username = "RC注册用户" + System.nanoTime();
        mockMvc.perform(post("/api/user/register")
                        .param("username", username).param("password", "secret123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 注册目标的用户名应该是可点击的用户页链接
        String userLink = URLEncoder.encode("用户:" + username, StandardCharsets.UTF_8).replace("+", "%20");
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                // 日志名链到同类型日志（特殊:日志?type=register）
                .andExpect(content().string(containsString("type=register")))
                .andExpect(content().string(containsString(userLink)));
    }

    @Test
    void editSummarySectionOnlyAndGroupedLogs() throws Exception {
        WikiUser actor = insertUser("摘要用户" + System.nanoTime());
        String title = "摘要测试页" + System.nanoTime();
        wikiPageService.editPage(actor, title, "第一版", "/* 段落 */");
        wikiPageService.editPage(actor, title, "第二版", "普通摘要文本");

        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                // 段落注释不加括号（rc-comment-section），普通摘要加括号（CSS 生成）
                .andExpect(content().string(containsString("rc-comment-section")))
                .andExpect(content().string(containsString("普通摘要文本")));

        // 两条注册日志合并成折叠组：「（用户注册日志） [甲；乙]」
        String userA = "合并日志甲" + System.nanoTime();
        String userB = "合并日志乙" + System.nanoTime();
        mockMvc.perform(post("/api/user/register")
                        .param("username", userA).param("password", "secret123"))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/user/register")
                        .param("username", userB).param("password", "secret123"))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rc-log-group")))
                .andExpect(content().string(containsString("rc-log-targets")))
                .andExpect(content().string(containsString("data-key=\"lg-")))
                .andExpect(content().string(containsString(userA)))
                .andExpect(content().string(containsString(userB)));
    }

    @Test
    void hiddenLogShowsMarkerOnlyForManagers() throws Exception {
        String username = "隐藏日志用户" + System.nanoTime();
        mockMvc.perform(post("/api/user/register")
                        .param("username", username).param("password", "secret123"))
                .andExpect(jsonPath("$.code").value(200));
        WikiUser target = userService.findByUsername(username).orElseThrow();
        WikiLog registerLog = logService.recentForTarget("register", target.getId(), true, 5).get(0);
        logService.setHided(registerLog.getId(), true);

        // 匿名：隐藏日志不可见
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("rc-hided"))));

        // 管理员：可见 + 删除线（rc-hided）+ （已隐藏）标记
        WikiUser admin = insertUser("隐藏日志管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rc-hided")))
                .andExpect(content().string(containsString("（已隐藏）")))
                .andExpect(content().string(containsString(username)));
    }

    // ==================== 工具 ====================

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private void insertHiddenPage(String title) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 6, 12, 0, 0);
        WikiPage page = new WikiPage();
        page.setTitle(title);
        page.setHided(true);
        page.setDeleted(false);
        page.setCreateAt(now);
        page.setUpdateAt(now);
        wikiPageMapper.insert(page);

        WikiRevision revision = new WikiRevision();
        revision.setPageId(page.getId());
        revision.setContent("隐藏内容");
        revision.setDeleted(false);
        revision.setCreateAt(now);
        wikiRevisionMapper.insert(revision);

        page.setLatestRevisionId(revision.getId());
        wikiPageMapper.updateById(page);
    }
}
