package cc.thonly.wikikernel.plugin.abusefilter.service;

import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilter;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilterRevision;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseLog;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterStore;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterFakes;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件服务：过滤器 CRUD + 历史恢复、求值与操作（disallow / warn / tag / throttle / block /
 * blockautopromote）、日志与变量快照、功能开关关闭时不评估。
 */
class AbuseFilterServiceTest {

    private static final String EDITOR = "编辑者甲";

    private AbuseFilterFakes.Fixture fixture() {
        AbuseFilterFakes.Fixture fixture = new AbuseFilterFakes.Fixture();
        fixture.addUser(EDITOR, AbuseFilterSupport.GROUP_AUTOCONFIRMED);
        return fixture;
    }

    private long filter(AbuseFilterFakes.Fixture fixture, String description, String pattern,
                        String actions, String tags) {
        return fixture.service().save(0, new AbuseFilterService.FilterForm(description, pattern, actions,
                tags, true, false, false, 0, 0, "user", "测试"), 1L);
    }

    private Map<String, Object> editVariables(String title, String newText) {
        return AbuseVariables.edit(1L, title, false, "旧内容", newText, "摘要", false);
    }

    // ==================== CRUD 与历史 ====================

    @Test
    void saveInsertsFilterAndRevisionAndRestoreBringsOldVersionBack() {
        AbuseFilterFakes.Fixture fixture = fixture();
        AbuseFilterService service = fixture.service();

        long id = filter(fixture, "初版说明", "page_title == \"页面\"", "disallow", "");
        WikiAbuseFilter saved = service.find(id).orElseThrow();
        assertEquals("初版说明", saved.getDescription());
        assertEquals("disallow", saved.getActions());
        assertTrue(Boolean.TRUE.equals(saved.getEnabled()));
        assertEquals(1L, saved.getLastEditorId());
        assertEquals(1, service.history(id).size());

        // 第二次保存：条件与操作都改掉，历史里应该有两条
        service.save(id, new AbuseFilterService.FilterForm("改版说明", "true", "warn", "标签甲",
                false, false, true, 3, 60, "ip", "改一版"), 2L);
        List<WikiAbuseFilterRevision> history = service.history(id);
        assertEquals(2, history.size());
        assertEquals("改版说明", history.get(0).getDescription());
        assertEquals("true", history.get(0).getPattern());
        assertEquals("ip", history.get(0).getThrottleGroup());

        long firstRevision = history.get(1).getId();
        assertTrue(service.restore(id, firstRevision, 3L));
        WikiAbuseFilter restored = service.find(id).orElseThrow();
        assertEquals("初版说明", restored.getDescription());
        assertEquals("disallow", restored.getActions());
        assertTrue(Boolean.TRUE.equals(restored.getEnabled()));
        assertEquals(3, service.history(id).size(), "恢复也会写一条新修订");

        assertFalse(service.restore(id, 999L, 1L), "不存在的修订不能恢复");
    }

    @Test
    void saveRejectsBadPatternAndUnknownAction() {
        AbuseFilterFakes.Fixture fixture = fixture();
        AbuseFilterService service = fixture.service();
        assertThrows(AbuseCondition.SyntaxException.class, () -> service.save(0,
                new AbuseFilterService.FilterForm("坏条件", "no_such_variable == 1", "disallow", "",
                        true, false, false, 0, 0, "user", ""), 1L));
        IllegalArgumentException unknownAction = assertThrows(IllegalArgumentException.class,
                () -> service.save(0, new AbuseFilterService.FilterForm("坏操作", "true", "explode", "",
                        true, false, false, 0, 0, "user", ""), 1L));
        assertEquals("未知操作：explode", unknownAction.getMessage(), "文案走插件消息包（i18n/messages.properties）");
        assertThrows(IllegalArgumentException.class, () -> service.save(0,
                new AbuseFilterService.FilterForm("节流缺参数", "true", "throttle", "",
                        true, false, false, 0, 0, "user", ""), 1L));
    }

    @Test
    void hiddenFiltersAreOnlyListedForManagers() {
        AbuseFilterFakes.Fixture fixture = fixture();
        long visible = filter(fixture, "公开过滤器", "true", "warn", "");
        long hidden = filter(fixture, "隐藏过滤器", "true", "warn", "");
        fixture.service().save(hidden, new AbuseFilterService.FilterForm("隐藏过滤器", "true", "warn", "",
                true, false, true, 0, 0, "user", "隐藏"), 1L);
        assertEquals(2, fixture.service().filters(true).size());
        assertEquals(List.of(visible), fixture.service().filters(false).stream()
                .map(WikiAbuseFilter::getId).toList());
    }

    // ==================== 求值与操作 ====================

    @Test
    void disallowBlocksAndWritesLogWithVariableSnapshot() {
        AbuseFilterFakes.Fixture fixture = fixture();
        long id = filter(fixture, "阻止垃圾链接", "new_wikitext contains \"spam\"", "disallow", "");
        Viewer viewer = Viewer.of(1L, EDITOR, Set.of(AbuseFilterSupport.GROUP_AUTOCONFIRMED));

        AbuseFilterService.Outcome outcome = fixture.service().evaluate("edit", viewer, "203.0.113.7",
                editVariables("测试页面", "spam http://example.com"));
        assertTrue(outcome.disallowed());
        assertEquals(List.of("阻止垃圾链接"), outcome.messages());
        assertEquals(List.of(id), outcome.filterIds());

        WikiAbuseLog log = fixture.service().logs(true, 10).get(0);
        assertEquals(id, log.getFilterId());
        assertEquals("edit", log.getAction());
        assertEquals("测试页面", log.getPageTitle());
        assertEquals("disallow", log.getActionsTaken());
        assertEquals(1L, log.getUserId());
        assertEquals("203.0.113.7", log.getIp());

        Map<String, Object> variables = fixture.service().logVariables(log);
        assertEquals("edit", variables.get("action"));
        assertEquals("测试页面", variables.get("page_title"));
        assertEquals(EDITOR, variables.get("user_name"));
        assertEquals(Boolean.TRUE, variables.get("user_autoconfirmed"));
        assertTrue(String.valueOf(variables.get("added_lines")).contains("spam"));
        assertEquals(1, fixture.store().filters.get(id).getHits(), "命中次数 +1");
    }

    @Test
    void warnDegradeAndTagApplyWithoutBlocking() {
        AbuseFilterFakes.Fixture fixture = fixture();
        filter(fixture, "提醒过滤器", "page_title == \"测试页面\"", "warn,degrade,tag", "自定义标签");
        Viewer viewer = Viewer.of(1L, EDITOR, Set.of());

        AbuseFilterService.Outcome outcome = fixture.service().evaluate("edit", viewer, "203.0.113.7",
                editVariables("测试页面", "新内容"));
        assertFalse(outcome.disallowed());
        assertEquals(List.of("提醒过滤器"), outcome.warnings());
        assertEquals("自定义标签", outcome.tags());
        assertEquals(List.of("自定义标签"), outcome.tagList());
        assertEquals("warn,tag", fixture.service().logs(true, 10).get(0).getActionsTaken(),
                "warn 与 degrade 同时勾选时按 warn 记录（与迁移前一致）");
    }

    @Test
    void tagFallsBackToFilterIdWhenNoCustomTags() {
        AbuseFilterFakes.Fixture fixture = fixture();
        long id = filter(fixture, "默认标签", "true", "tag", "");
        AbuseFilterService.Outcome outcome = fixture.service().evaluate("edit",
                Viewer.of(1L, EDITOR, Set.of()), "", editVariables("测试页面", "新内容"));
        assertEquals("abusefilter-" + id, outcome.tags());
    }

    @Test
    void throttleOnlyRunsOtherActionsAfterTheLimit() {
        AbuseFilterFakes.Fixture fixture = fixture();
        long id = fixture.service().save(0, new AbuseFilterService.FilterForm("节流过滤器",
                "true", "disallow,throttle", "", true, false, false, 2, 60, "user", ""), 1L);
        Viewer viewer = Viewer.of(1L, EDITOR, Set.of());
        Map<String, Object> variables = editVariables("测试页面", "新内容");

        assertFalse(fixture.service().evaluate("edit", viewer, "203.0.113.7", variables).disallowed(),
                "第 1 次未超过限制：只记节流");
        assertFalse(fixture.service().evaluate("edit", viewer, "203.0.113.7", variables).disallowed(),
                "第 2 次仍未超过限制");
        assertTrue(fixture.service().evaluate("edit", viewer, "203.0.113.7", variables).disallowed(),
                "第 3 次超过限制：disallow 生效");
        assertEquals("throttle", fixture.service().logs(true, 10).get(2).getActionsTaken());
        assertEquals(3, fixture.store().filters.get(id).getHits(), "三次命中都计数");
    }

    @Test
    void blockAndBlockAutopromoteActionsRunThroughTheBridges() {
        AbuseFilterFakes.Fixture fixture = fixture();
        long id = filter(fixture, "封禁过滤器", "true", "block,blockautopromote", "");
        Viewer viewer = Viewer.of(1L, EDITOR, Set.of(AbuseFilterSupport.GROUP_AUTOCONFIRMED));

        fixture.service().evaluate("edit", viewer, "203.0.113.7", editVariables("测试页面", "新内容"));

        assertEquals(1, fixture.userWriter.systemAccounts.size(), "系统账号「滥用过滤器」被兜底创建");
        assertEquals(AbuseFilterSupport.GROUP_BOT,
                fixture.userWriter.systemAccounts.get(AbuseFilterService.SYSTEM_ACCOUNT));
        assertEquals(List.of(AbuseFilterService.SYSTEM_ACCOUNT + "|" + EDITOR
                + "|滥用过滤器：封禁过滤器"), fixture.blockWriter.blocks, "封禁原因走插件消息包里的 abusefilter.blockReason");
        assertEquals(List.of("1|" + AbuseFilterSupport.GROUP_AUTOCONFIRMED), fixture.userWriter.revoked);
        assertEquals("block,blockautopromote",
                fixture.service().logs(true, 10).get(0).getActionsTaken());
        assertEquals(1, fixture.store().filters.get(id).getHits(), "命中次数 +1");
    }

    @Test
    void ipBlockIsUsedForAnonymousTriggers() {
        AbuseFilterFakes.Fixture fixture = fixture();
        filter(fixture, "匿名封禁", "true", "block", "");
        fixture.service().evaluate("edit", Viewer.ANONYMOUS, "203.0.113.9", editVariables("测试页面", "新内容"));
        assertTrue(fixture.blockWriter.blocks.get(0).contains("|203.0.113.9|"));
    }

    @Test
    void disabledFeatureSkipsEvaluationEntirely() {
        AbuseFilterFakes.Fixture fixture = fixture();
        filter(fixture, "关闭时不应生效", "true", "disallow", "");
        fixture.enabled(false);

        AbuseFilterService.Outcome outcome = fixture.service().evaluate("edit",
                Viewer.of(1L, EDITOR, Set.of()), "203.0.113.7", editVariables("测试页面", "新内容"));
        assertFalse(outcome.disallowed());
        assertTrue(outcome.warnings().isEmpty());
        assertTrue(fixture.service().logs(true, 10).isEmpty(), "关闭时不写滥用日志");
        assertEquals(0, fixture.store().filters.values().iterator().next().getHits(),
                "关闭时不计命中次数");
    }

    @Test
    void disabledFiltersAndDeletedFiltersAreIgnored() {
        AbuseFilterFakes.Fixture fixture = fixture();
        long disabled = filter(fixture, "停用", "true", "disallow", "");
        long deleted = filter(fixture, "已删除", "true", "disallow", "");
        fixture.service().save(disabled, new AbuseFilterService.FilterForm("停用", "true", "disallow", "",
                false, false, false, 0, 0, "user", ""), 1L);
        fixture.service().save(deleted, new AbuseFilterService.FilterForm("已删除", "true", "disallow", "",
                true, true, false, 0, 0, "user", ""), 1L);

        AbuseFilterService.Outcome outcome = fixture.service().evaluate("edit",
                Viewer.of(1L, EDITOR, Set.of()), "", editVariables("测试页面", "新内容"));
        assertFalse(outcome.disallowed());
        assertTrue(fixture.service().logs(true, 10).isEmpty());
    }

    // ==================== 日志与测试页 ====================

    @Test
    void logsCanBeHiddenAndPaged() {
        AbuseFilterFakes.Fixture fixture = fixture();
        filter(fixture, "记录过滤器", "true", "", "");
        fixture.service().evaluate("edit", Viewer.of(1L, EDITOR, Set.of()), "",
                editVariables("测试页面", "新内容"));
        long logId = fixture.service().logs(true, 10).get(0).getId();

        assertTrue(fixture.service().hideLog(logId, true));
        assertTrue(fixture.service().logs(false, 10).isEmpty(), "隐藏后普通用户看不到");
        assertEquals(1, fixture.service().logs(true, 10).size(), "管理员仍然能看到");
        assertTrue(fixture.service().hideLog(logId, false));
        assertEquals(1, fixture.service().logs(false, 10).size());
        assertFalse(fixture.service().hideLog(999L, true));
    }

    @Test
    void testRunsPatternAgainstRecentEditsWithoutActions() {
        AbuseFilterFakes.Fixture fixture = fixture();
        long userId = 1L;
        fixture.store().edits.add(new AbuseFilterStore.RecentEdit(7L, 3L, "最近页面", userId, EDITOR,
                LocalDateTime.now(), "摘要", "spam http://example.com", "旧内容"));
        fixture.store().stats.put(userId, new AbuseFilterStore.UserStats(5L, LocalDateTime.now()));

        List<AbuseFilterService.TestHit> hits = fixture.service().test("new_wikitext contains \"spam\"", 20);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).matched());
        assertEquals("最近页面", hits.get(0).pageTitle());
        assertEquals(EDITOR, hits.get(0).userName());

        List<AbuseFilterService.TestHit> miss = fixture.service().test("page_title == \"别的页面\"", 20);
        assertFalse(miss.get(0).matched());
        assertTrue(fixture.service().logs(true, 10).isEmpty(), "测试不执行任何操作、不写日志");
        assertThrows(AbuseCondition.SyntaxException.class, () -> fixture.service().test("nope == 1", 20));
    }

    @Test
    void systemAccountIsCreatedOnlyWhenEnabled() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.enabled(false);
        assertFalse(fixture.service().ensureSystemAccount(), "关闭时不创建系统账号");
        fixture.enabled(true);
        assertTrue(fixture.service().ensureSystemAccount());
        assertFalse(fixture.service().ensureSystemAccount(), "已存在时不重复创建");
    }
}
