package cc.thonly.wikikernel.plugin.abusefilter;

import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseLog;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.ActionGuard;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.abusefilter.hook.AbuseFilterHeadProvider;
import cc.thonly.wikikernel.plugin.abusefilter.model.AbuseFilterTitles;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterService;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseVariables;
import cc.thonly.wikikernel.plugin.abusefilter.web.AbuseFilterSpecialPage;
import cc.thonly.wikikernel.plugin.abusefilter.web.AbuseLogSpecialPage;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件装配（注册了哪些扩展点、系统账号的创建时机）与两个特殊页的模型
 * （列表 / 编辑 / 历史 / 测试、滥用日志列表与详情、隐藏条目、权限）。
 */
class AbuseFilterPluginTest {

    private static final String ADMIN = "管理员甲";
    private static final String EDITOR = "编辑者甲";

    private AbuseFilterFakes.Fixture fixture() {
        AbuseFilterFakes.Fixture fixture = new AbuseFilterFakes.Fixture();
        fixture.addUser(ADMIN, AbuseFilterSupport.GROUP_ADMIN);
        fixture.addUser(EDITOR);
        return fixture;
    }

    private AbuseFilterPlugin plugin(AbuseFilterFakes.Fixture fixture) {
        AbuseFilterPlugin plugin = new AbuseFilterPlugin();
        PluginContextFake.inject(plugin, "service", fixture.service());
        PluginContextFake.inject(plugin, "support", fixture.support());
        PluginContextFake.inject(plugin, "userStore", fixture.userStore());
        PluginContextFake.inject(plugin, "wikiPageStore", fixture.pageStore());
        return plugin;
    }

    private static WikiUser user(long id, String name) {
        WikiUser user = new WikiUser();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    private static SpecialPageRequest request(String name, Map<String, String> parameters, WikiUser user) {
        return new SpecialPageRequest(name, parameters, user);
    }

    // ==================== 装配 ====================

    @Test
    void pluginRegistersEveryExtensionAndCreatesTheSystemAccountWhenEnabled() {
        AbuseFilterFakes.Fixture fixture = fixture();
        PluginContextFake context = new PluginContextFake("abuse-filter");
        plugin(fixture).onStart(context);

        assertEquals("abuse-filter", plugin(fixture).descriptor().id());
        assertTrue(context.has(MessageBundle.class), "文案包");
        assertTrue(context.has(EditGuard.class), "编辑 / 创建（web / api / ai）");
        assertTrue(context.has(ActionGuard.class), "移动 / 上传 / 删除 / 保护 / 封禁");
        assertTrue(context.has(RegistrationGuard.class), "注册");
        assertTrue(context.has(HtmlHeadProvider.class), "前端脚本与样式");
        assertEquals(2, context.extensions(SpecialPage.class).size(), "两个特殊页");

        assertEquals(Set.of(AbuseFilterService.SYSTEM_ACCOUNT), fixture.userWriter.systemAccounts.keySet(),
                "启用时创建系统账号「滥用过滤器」");
        assertEquals(AbuseFilterSupport.GROUP_BOT,
                fixture.userWriter.systemAccounts.get(AbuseFilterService.SYSTEM_ACCOUNT));
    }

    @Test
    void pluginDoesNotCreateTheSystemAccountWhenDisabled() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.enabled(false);
        PluginContextFake context = new PluginContextFake("abuse-filter");
        plugin(fixture).onStart(context);

        assertTrue(fixture.userWriter.systemAccounts.isEmpty(), "关闭时不创建系统账号");
        assertTrue(context.logs.stream().anyMatch(line -> line.contains("已关闭")));
    }

    // ==================== 特殊:滥用过滤器 ====================

    @Test
    void specialPageIsManagerOnlyAndShowsDisabledNotice() {
        AbuseFilterFakes.Fixture fixture = fixture();
        AbuseFilterSpecialPage page = new AbuseFilterSpecialPage(fixture.service(), fixture.support(),
                fixture.userStore());

        assertThrows(ResponseStatusException.class, () -> page.populate(
                request("滥用过滤器", Map.of(), user(2L, EDITOR)), new LinkedHashMap<>()),
                "非管理员不能查看过滤器管理页");

        fixture.enabled(false);
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request("滥用过滤器", Map.of(), user(1L, ADMIN)), model);
        assertEquals(Boolean.TRUE, model.get("disabled"));
        assertEquals("list", model.get("mode"));
        assertEquals(List.of(), model.get("abuseFilters"));
    }

    @Test
    void specialPageListsEditsAndHistory() {
        AbuseFilterFakes.Fixture fixture = fixture();
        long id = fixture.service().save(0, new AbuseFilterService.FilterForm("垃圾链接过滤器",
                "added_lines contains \"spam\"", "disallow,warn", "", true, false, false,
                0, 0, "user", "初版"), 1L);
        fixture.service().save(id, new AbuseFilterService.FilterForm("垃圾链接过滤器（改）",
                "added_lines contains \"spam\"", "disallow", "标签甲", true, false, false,
                3, 60, "ip", "改一版"), 1L);
        AbuseFilterSpecialPage page = new AbuseFilterSpecialPage(fixture.service(), fixture.support(),
                fixture.userStore());

        Map<String, Object> list = new LinkedHashMap<>();
        page.populate(request("滥用过滤器", Map.of(), user(1L, ADMIN)), list);
        assertEquals("list", list.get("mode"));
        List<Map<String, Object>> rows = cast(list.get("abuseFilters"));
        assertEquals(1, rows.size());
        assertEquals("垃圾链接过滤器（改）", rows.get(0).get("description"));
        assertEquals(Boolean.TRUE, rows.get(0).get("enabled"));
        assertEquals(ADMIN, rows.get(0).get("lastEditorName"));
        assertTrue(String.valueOf(rows.get(0).get("actionsText")).contains("阻止操作"),
                "操作文案走消息键（插件消息包里的本地化名）");
        assertEquals(7, cast(list.get("actionOptions")).size(), "七种操作都列出");

        Map<String, Object> edit = new LinkedHashMap<>();
        page.populate(request("滥用过滤器", Map.of("edit", String.valueOf(id)), user(1L, ADMIN)), edit);
        assertEquals("edit", edit.get("mode"));
        assertEquals("abusefilter", edit.get("codeLanguage"));
        assertEquals(List.of("disallow"), cast(edit.get("selectedActions")));

        Map<String, Object> create = new LinkedHashMap<>();
        page.populate(request("滥用过滤器", Map.of("new", "1"), user(1L, ADMIN)), create);
        assertEquals("edit", create.get("mode"));
        assertNull(create.get("abuseFilter"));

        Map<String, Object> history = new LinkedHashMap<>();
        page.populate(request("滥用过滤器", Map.of("history", String.valueOf(id)), user(1L, ADMIN)), history);
        assertEquals("history", history.get("mode"));
        List<Map<String, Object>> historyRows = cast(history.get("abuseHistory"));
        assertEquals(2, historyRows.size());
        assertEquals("初版", historyRows.get(1).get("comment"));

        Map<String, Object> test = new LinkedHashMap<>();
        page.populate(request("滥用过滤器", Map.of("test", "1"), user(1L, ADMIN)), test);
        assertEquals("test", test.get("mode"));

        Map<String, Object> missing = new LinkedHashMap<>();
        page.populate(request("滥用过滤器", Map.of("edit", "999"), user(1L, ADMIN)), missing);
        assertEquals("list", missing.get("mode"));
        assertEquals("过滤器不存在。", missing.get("abuseError"));
    }

    // ==================== 特殊:滥用日志 ====================

    @Test
    void abuseLogListsDetailsAndHidesEntries() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.service().save(0, new AbuseFilterService.FilterForm("记录过滤器", "true",
                "", "", true, false, false, 0, 0, "user", ""), 1L);
        fixture.service().evaluate("edit", Viewer.of(2L, EDITOR, Set.of()), "203.0.113.7",
                AbuseVariables.edit(0L, "被记录的页面", true, "", "新内容", "摘要", false));
        long logId = fixture.service().logs(true, 10).get(0).getId();
        AbuseLogSpecialPage page = new AbuseLogSpecialPage(fixture.service(), fixture.support(),
                fixture.userStore());

        Map<String, Object> list = new LinkedHashMap<>();
        page.populate(request("滥用日志", Map.of(), null), list);
        assertEquals("list", list.get("mode"));
        assertEquals(Boolean.FALSE, list.get("canManage"));
        List<Map<String, Object>> rows = cast(list.get("logs"));
        assertEquals(1, rows.size());
        assertEquals("记录过滤器", rows.get(0).get("filterDescription"));
        assertEquals(EDITOR, rows.get(0).get("userText"));
        assertEquals("（仅记录）", rows.get(0).get("actionsText"));

        Map<String, Object> detail = new LinkedHashMap<>();
        page.populate(request("滥用日志", Map.of("id", String.valueOf(logId)), user(1L, ADMIN)), detail);
        assertEquals("detail", detail.get("mode"));
        assertEquals(Boolean.TRUE, detail.get("canManage"));
        List<Map<String, Object>> variables = cast(detail.get("variables"));
        assertTrue(variables.stream().anyMatch(row -> "page_title".equals(row.get("name"))));
        assertTrue(variables.stream().anyMatch(row -> "被记录的页面".equals(row.get("value"))));
        // 隐藏后：普通访客看不到条目与详情，管理员仍然可见
        fixture.service().hideLog(logId, true);
        Map<String, Object> hiddenList = new LinkedHashMap<>();
        page.populate(request("滥用日志", Map.of(), null), hiddenList);
        assertEquals(List.of(), cast(hiddenList.get("logs")));
        Map<String, Object> hiddenDetail = new LinkedHashMap<>();
        page.populate(request("滥用日志", Map.of("id", String.valueOf(logId)), null), hiddenDetail);
        assertEquals("missing", hiddenDetail.get("mode"));
        Map<String, Object> managerDetail = new LinkedHashMap<>();
        page.populate(request("滥用日志", Map.of("id", String.valueOf(logId)), user(1L, ADMIN)), managerDetail);
        assertEquals("detail", managerDetail.get("mode"));
    }

    @Test
    void headProviderInjectsAssetsOnlyWhenEnabled() {
        AbuseFilterFakes.Fixture fixture = fixture();
        AbuseFilterHeadProvider provider = new AbuseFilterHeadProvider(fixture.support());
        assertTrue(provider.siteWide());
        assertTrue(provider.headItems(null).stream()
                .anyMatch(item -> item.contains("/plugins/abuse-filter/abuse-filter.js")));
        assertTrue(provider.headItems(null).stream()
                .anyMatch(item -> item.contains("abuseTestPage")));

        fixture.enabled(false);
        assertEquals(List.of(), provider.headItems(null));
    }

    @Test
    void messagesBundleKeepsTheOriginalKeys() {
        Map<String, Map<String, String>> messages = new AbuseFilterMessages().messages();
        assertTrue(messages.get("zh").containsKey("abusefilter.disallowed"));
        assertTrue(messages.get("zh").containsKey("abusefilter.warnConfirm"));
        assertTrue(messages.get("zh").containsKey("special.abuseLog"));
        assertEquals("滥用过滤器", messages.get("zh").get("special.abuseFilter"));
        assertEquals("Abuse log", messages.get("en").get("special.abuseLog"));
        assertFalse(messages.get("zh").containsKey("settings.abuseFilter"),
                "站点设置文案仍属于 core，不随插件走");
    }

    @Test
    void titlesAndVariablesFollowMediaWikiNames() {
        assertEquals("测试页面", AbuseFilterTitles.titleOnly("模板:测试页面"));
        assertEquals(10L, AbuseFilterTitles.namespaceId("模板:测试页面"));
        assertEquals(6L, AbuseFilterTitles.namespaceId("文件:图片.png"));
        assertEquals(0L, AbuseFilterTitles.namespaceId("主命名空间页面"));

        Map<String, Object> move = AbuseVariables.move("模板:甲", "模板:乙");
        assertEquals("move", move.get("action"));
        assertEquals("甲", move.get("moved_from"));
        assertEquals("乙", move.get("moved_to"));
        assertEquals(10L, move.get("page_namespace"));

        Map<String, Object> upload = AbuseVariables.upload("图片.png", 2048L, "image/png", "说明");
        assertEquals("upload", upload.get("action"));
        assertEquals(2048L, upload.get("file_size"));
        assertEquals("image/png", upload.get("file_mime"));

        Map<String, Object> account = AbuseVariables.createAccount("新人甲");
        assertEquals("createaccount", account.get("action"));
        assertEquals("新人甲", account.get("accountname"));

        Map<String, Object> edit = AbuseVariables.edit(3L, "页面", false, "旧\n一样", "新\n一样",
                "摘要", true);
        assertEquals("edit", edit.get("action"));
        assertEquals(3L, edit.get("page_id"));
        assertEquals(Boolean.TRUE, edit.get("minor_edit"));
        assertEquals(List.of("新"), edit.get("added_lines"));
        assertEquals(List.of("旧"), edit.get("removed_lines"));
    }

    @Test
    void logEntryIsCreatedForEveryMatchedFilter() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.service().save(0, new AbuseFilterService.FilterForm("第一个", "true", "warn", "",
                true, false, false, 0, 0, "user", ""), 1L);
        fixture.service().save(0, new AbuseFilterService.FilterForm("第二个", "true", "warn", "",
                true, false, false, 0, 0, "user", ""), 1L);
        AbuseFilterService.Outcome outcome = fixture.service().evaluate("edit", Viewer.ANONYMOUS, "1.2.3.4",
                AbuseVariables.edit(0L, "页面", true, "", "内容", "", false));
        assertEquals(2, outcome.filterIds().size());
        List<WikiAbuseLog> logs = fixture.service().logs(true, 10);
        assertEquals(2, logs.size());
        assertTrue(logs.stream().allMatch(entry -> entry.getUserId() == null));
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> cast(Object value) {
        return (List<T>) value;
    }
}
