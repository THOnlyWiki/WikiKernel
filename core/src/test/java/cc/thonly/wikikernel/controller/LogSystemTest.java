package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.LogService;
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
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 日志系统：页面生命周期/注册记录、类型/用户/目标/时间范围筛选、隐藏（权限）、本地化。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class LogSystemTest {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private LogService logService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void pageLifecycleAndRegisterAreLogged() throws Exception {
        WikiUser actor = insertUser("日志操作者");
        wikiPageService.editPage(actor, "日志生命周期页", "第一版");
        wikiPageService.editPage(actor, "日志生命周期页", "第二版", "改了一下");
        wikiPageService.deletePage(actor, "日志生命周期页");

        mockMvc.perform(get("/wiki/{title}", "特殊:日志"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("创建了页面")))
                .andExpect(content().string(containsString("编辑了页面")))
                .andExpect(content().string(containsString("删除了页面")))
                .andExpect(content().string(containsString("日志生命周期页")))
                .andExpect(content().string(containsString("改了一下")))
                .andExpect(content().string(containsString("日志操作者")));

        String username = "注册日志用户" + System.nanoTime();
        mockMvc.perform(post("/api/user/register")
                        .param("username", username)
                        .param("password", "secret123"))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("name", username))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("注册了账号")))
                .andExpect(content().string(containsString(username)));
    }

    @Test
    void typeFilterShowsOnlyThatType() throws Exception {
        WikiUser actor = insertUser("类型过滤用户" + System.nanoTime());
        String title = "类型过滤页" + System.nanoTime();
        wikiPageService.editPage(actor, title, "第一版");
        wikiPageService.editPage(actor, title, "第二版");

        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("type", "edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑了页面")))
                .andExpect(content().string(not(containsString("创建了页面"))));
    }

    @Test
    void hideFlowRequiresManagePermission() throws Exception {
        WikiUser actor = insertUser("隐藏日志操作者");
        WikiUser admin = insertUser("隐藏日志管理员");
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));

        String title = "隐藏测试页" + System.nanoTime();
        wikiPageService.editPage(actor, title, "内容");
        WikiLog log = logService.recent(null, actor.getId(), true, 50).stream()
                .filter(item -> title.equals(item.getTarget()))
                .findFirst()
                .orElseThrow();

        // 匿名：401
        mockMvc.perform(post("/api/logs/hide").param("id", log.getId().toString()))
                .andExpect(jsonPath("$.code").value(401));

        // 普通用户：403
        WikiUser normal = insertUser("隐藏普通用户");
        MockHttpSession normalSession = sessionOf(normal);
        mockMvc.perform(post("/api/logs/hide").session(normalSession).param("id", log.getId().toString()))
                .andExpect(jsonPath("$.code").value(403));

        // 管理员：200
        MockHttpSession adminSession = sessionOf(admin);
        mockMvc.perform(post("/api/logs/hide")
                        .session(adminSession)
                        .param("id", log.getId().toString())
                        .param("hidden", "true"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("日志已隐藏"));

        assertTrue(logService.recent(null, actor.getId(), true, 50).stream()
                .filter(item -> item.getId().equals(log.getId()))
                .findFirst().orElseThrow().getHided());

        // 匿名看不到隐藏日志；管理员能看到并带“（已隐藏）”
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("name", actor.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(title))));
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("name", actor.getUsername()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString("（已隐藏）")));

        // 恢复显示
        mockMvc.perform(post("/api/logs/hide")
                        .session(adminSession)
                        .param("id", log.getId().toString())
                        .param("hidden", "false"))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void logPageIsLocalized() throws Exception {
        WikiUser actor = insertUser("本地化日志用户");
        wikiPageService.editPage(actor, "本地化日志页", "内容");

        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("uselang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("created page")))
                .andExpect(content().string(containsString("edited page")))
                .andExpect(content().string(containsString("Page creation")))
                .andExpect(content().string(containsString("<select name=\"type\"")))
                .andExpect(content().string(containsString("All public logs")))
                .andExpect(content().string(containsString("Target page")));
    }

    @Test
    void logSearchFiltersByTargetTimeRangeAndUser() throws Exception {
        WikiUser actor = insertUser("日志搜索用户" + System.nanoTime());
        String title = "日志搜索页" + System.nanoTime();
        wikiPageService.editPage(actor, title, "第一版");
        wikiPageService.editPage(actor, title, "第二版");

        // 筛选栏：类型下拉 + 用户自动补全 + 时间范围输入
        mockMvc.perform(get("/wiki/{title}", "特殊:日志"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<select name=\"type\"")))
                .andExpect(content().string(containsString("全部公开日志")))
                .andExpect(content().string(containsString("datetime-local")))
                .andExpect(content().string(containsString("list=\"log-user-list\"")))
                .andExpect(content().string(containsString("<datalist id=\"log-user-list\">")))
                .andExpect(content().string(containsString(actor.getUsername())));

        // 目标页面片段过滤：能搜到本页且搜不到无关页面
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("page", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(not(containsString("日志生命周期页"))));

        // 未来起始时间 → 无结果
        String future = LocalDateTime.now().plusDays(1).format(DATE_TIME);
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("page", title).param("start", future))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("在日志中不存在匹配项")));

        // 过去结束时间 → 无结果
        String past = LocalDateTime.now().minusDays(1).format(DATE_TIME);
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("page", title).param("end", past))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("在日志中不存在匹配项")));

        // 包含当前时间的范围 → 有结果（日期形式也支持，结束日期按当天 23:59:59）
        String from = LocalDateTime.now().minusMinutes(10).format(DATE_TIME);
        String today = LocalDateTime.now().toLocalDate().toString();
        mockMvc.perform(get("/wiki/{title}", "特殊:日志")
                        .param("page", title).param("start", from).param("end", today))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑了页面")));

        // 类型 + 用户组合：只看该用户的编辑日志
        mockMvc.perform(get("/wiki/{title}", "特殊:日志")
                        .param("type", "edit").param("name", actor.getUsername()).param("page", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑了页面")))
                .andExpect(content().string(not(containsString("创建了页面"))));

        // 非法时间 → 提示并忽略该条件（查询仍执行）
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("start", "not-a-date"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("时间格式无效")));
    }

    @Test
    void logPaginationLimitAndOffset() throws Exception {
        WikiUser actor = insertUser("日志分页用户" + System.nanoTime());
        String prefix = "分页" + System.nanoTime() + "甲";
        for (int i = 1; i <= 12; i++) {
            wikiPageService.editPage(actor, prefix + i + "号", "内容" + i);
        }
        String user = actor.getUsername();

        // 第 1 页（limit=10）：最新 10 条（12 号 .. 3 号），有「下一页」，没有「最新」链接
        mockMvc.perform(get("/wiki/{title}", "特殊:日志")
                        .param("type", "create").param("name", user).param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(prefix + "12号")))
                .andExpect(content().string(containsString(prefix + "3号")))
                .andExpect(content().string(not(containsString(prefix + "2号"))))
                .andExpect(content().string(containsString("offset=10")))
                .andExpect(content().string(not(containsString("offset=20"))))
                .andExpect(content().string(not(containsString("offset=0"))))
                .andExpect(content().string(containsString("每页")))
                .andExpect(content().string(containsString("limit=100")))
                .andExpect(content().string(containsString("limit=500")));

        // 第 2 页（offset=10）：剩下 2 条（2 号 / 1 号），没有「下一页」
        mockMvc.perform(get("/wiki/{title}", "特殊:日志")
                        .param("type", "create").param("name", user)
                        .param("limit", "10").param("offset", "10"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(prefix + "2号")))
                .andExpect(content().string(containsString(prefix + "1号")))
                .andExpect(content().string(not(containsString(prefix + "12号"))))
                .andExpect(content().string(not(containsString("offset=20"))));

        // 非法 limit 回退默认 50：12 条全在第一页（没有下一页）
        mockMvc.perform(get("/wiki/{title}", "特殊:日志")
                        .param("type", "create").param("name", user).param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(prefix + "1号")))
                .andExpect(content().string(containsString(prefix + "12号")))
                .andExpect(content().string(not(containsString("offset=50"))));
    }

    @Test
    void logTableHasFixedColumnWidths() throws Exception {
        // 固定列宽（table-layout: fixed）：colgroup 定义 时间 / 操作者 / 操作 / 详情，
        // 管理员多一列「状态」，保证翻页 / 切每页条数时列不随内容长短跳变
        mockMvc.perform(get("/wiki/{title}", "特殊:日志"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"log-col-time\"")))
                .andExpect(content().string(containsString("class=\"log-col-actor\"")))
                .andExpect(content().string(containsString("class=\"log-col-action\"")))
                .andExpect(content().string(containsString("class=\"log-col-detail\"")))
                .andExpect(content().string(containsString("class=\"log-time\"")))
                .andExpect(content().string(not(containsString("log-col-status"))));

        WikiUser admin = insertUser("日志列宽管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("log-col-status")))
                .andExpect(content().string(containsString("log-actions")));
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

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
