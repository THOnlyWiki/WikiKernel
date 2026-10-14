package cc.thonly.wikikernel.plugin.moderation;

import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.ActionGuard;
import cc.thonly.wikikernel.common.extension.EditFormField;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationHeadProvider;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationLogType;
import cc.thonly.wikikernel.plugin.moderation.web.ModerationSpecialPage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件装配（注册了哪些扩展点）与 特殊:审核 的模型
 * （状态 / 类型 / 标题过滤、页签计数、行模型、权限与「未启用」提示）、前端资源注入、文案包与日志类型。
 */
class ModerationPluginTest {

    private static final String CHECKUSER = "核查员甲";
    private static final String PLAIN = "普通用户甲";

    private ModerationFakes.Fixture fixture() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        fixture.addUser(CHECKUSER, ModerationSupport.GROUP_CHECKUSER);
        fixture.addUser(PLAIN);
        return fixture;
    }

    private ModerationPlugin plugin(ModerationFakes.Fixture fixture) {
        ModerationPlugin plugin = new ModerationPlugin();
        PluginContextFake.inject(plugin, "service", fixture.service());
        PluginContextFake.inject(plugin, "support", fixture.support());
        PluginContextFake.inject(plugin, "wikiPageStore", fixture.pageStore());
        PluginContextFake.inject(plugin, "wikiDiff", fixture.wikiDiff());
        PluginContextFake.inject(plugin, "wikiText", fixture.wikiText());
        PluginContextFake.inject(plugin, "userStore", fixture.userStore());
        return plugin;
    }

    private static WikiUser user(long id, String name) {
        WikiUser user = new WikiUser();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    private static SpecialPageRequest request(Map<String, String> parameters, WikiUser user) {
        return new SpecialPageRequest("审核", parameters, user);
    }

    // ==================== 装配 ====================

    @Test
    void pluginRegistersEveryExtensionPoint() {
        ModerationFakes.Fixture fixture = fixture();
        PluginContextFake context = new PluginContextFake(ModerationPlugin.ID);
        plugin(fixture).onStart(context);

        assertEquals("moderation", plugin(fixture).descriptor().id());
        assertTrue(context.has(MessageBundle.class), "文案包");
        assertTrue(context.has(SpecialPage.class), "特殊:审核");
        assertTrue(context.has(EditGuard.class), "编辑 / 创建排队");
        assertTrue(context.has(ActionGuard.class), "上传 / 删除排队");
        assertTrue(context.has(EditFormField.class), "编辑页提示");
        assertTrue(context.has(PageDisplayHook.class), "提示条 / 待审创建页");
        assertTrue(context.has(HtmlHeadProvider.class), "前端脚本与样式");
        assertEquals(2, context.extensions(LogType.class).size(), "两个日志类型（通过 / 拒绝）");
        assertEquals(List.of(ModerationLogType.ID_APPROVE, ModerationLogType.ID_REJECT),
                context.extensions(LogType.class).stream().map(LogType::id).toList());
        assertTrue(context.logs.stream().anyMatch(line -> line.contains("已启用审核")));
    }

    @Test
    void disabledSwitchIsReportedInLogs() {
        ModerationFakes.Fixture fixture = fixture();
        fixture.enabled(false);
        ModerationPlugin plugin = plugin(fixture);
        PluginContextFake context = new PluginContextFake(ModerationPlugin.ID);
        plugin.onStart(context);

        assertTrue(context.logs.stream().anyMatch(line -> line.contains("审核已关闭")));
        assertTrue(context.has(EditGuard.class), "关闭时仍然注册钩子（运行期读开关）");
    }

    // ==================== 特殊:审核 ====================

    @Test
    void specialPageShowsDisabledNoticeForReviewers() {
        ModerationFakes.Fixture fixture = fixture();
        fixture.enabled(false);
        ModerationSpecialPage page = fixture.specialPage();

        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request(Map.of(), user(1L, CHECKUSER)), model);
        assertEquals(Boolean.TRUE, model.get("canReview"));
        assertEquals(Boolean.FALSE, model.get("enabled"));
        assertNull(model.get("rows"), "未启用时不查队列");
    }

    @Test
    void specialPageIsReviewerOnly() {
        ModerationFakes.Fixture fixture = fixture();
        fixture.service().submitEdit(fixture.viewer(2L), null, null, "审核页过滤页甲",
                "甲", "", false, false, "");
        ModerationSpecialPage page = fixture.specialPage();

        Map<String, Object> plain = new LinkedHashMap<>();
        page.populate(request(Map.of(), user(2L, PLAIN)), plain);
        assertEquals(Boolean.FALSE, plain.get("canReview"));
        assertNull(plain.get("rows"), "非审核员看不到队列");

        Map<String, Object> anonymous = new LinkedHashMap<>();
        page.populate(request(Map.of(), null), anonymous);
        assertEquals(Boolean.FALSE, anonymous.get("canReview"));
    }

    @Test
    void specialPageListsRowsWithFiltersTabsAndCounts() {
        ModerationFakes.Fixture fixture = fixture();
        long authorId = 2L;
        String title = "审核页过滤页甲";
        fixture.addPage(title, "旧甲\n旧乙");
        fixture.addPage("审核页过滤页乙", "乙的内容");

        WikiPendingChange edit = fixture.service().submitEdit(fixture.viewer(authorId),
                fixture.pageIds.get(title), 1L, title, "新甲\n旧乙", "改一行", false, false, "");
        fixture.service().submitUpload(fixture.viewer(authorId), "审核页图.png", "图片说明", new byte[]{1});
        WikiPendingChange delete = fixture.service().submitFileDelete(fixture.viewer(authorId),
                9L, "审核页删.png", true, "清理");
        fixture.service().reject(fixture.viewer(1L), delete.getId(), "不删");

        ModerationSpecialPage page = fixture.specialPage();
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request(Map.of(), user(1L, CHECKUSER)), model);

        assertEquals(Boolean.TRUE, model.get("canReview"));
        assertEquals(Boolean.TRUE, model.get("enabled"));
        List<Map<String, Object>> rows = cast(model.get("rows"));
        assertEquals(3, rows.size(), "默认列出全部状态");
        Map<String, Object> editRow = rows.stream()
                .filter(row -> WikiPendingChange.TYPE_EDIT.equals(row.get("changeType")))
                .findFirst().orElseThrow();
        assertEquals(title, editRow.get("title"));
        assertEquals("改一行", editRow.get("comment"));
        assertEquals("页面编辑", editRow.get("typeLabel"));
        assertEquals("待审", editRow.get("statusLabel"));
        assertEquals("普通用户甲", editRow.get("author"));
        assertTrue(String.valueOf(editRow.get("authorUrl")).contains("%E7%94%A8%E6%88%B7"), "作者链到用户页");
        assertFalse(Boolean.TRUE.equals(editRow.get("isNew")));
        List<Map<String, Object>> diffRows = cast(editRow.get("diffRows"));
        assertTrue(diffRows.stream().anyMatch(row -> "旧甲".equals(row.get("oldChange"))));
        assertTrue(diffRows.stream().anyMatch(row -> "新甲".equals(row.get("newChange"))));
        assertEquals(edit.getId(), editRow.get("id"));

        Map<String, Object> uploadRow = rows.stream()
                .filter(row -> WikiPendingChange.TYPE_UPLOAD.equals(row.get("changeType")))
                .findFirst().orElseThrow();
        assertEquals("审核页图.png", uploadRow.get("fileName"));
        assertEquals("图片说明", uploadRow.get("fileDescription"));
        assertEquals(Boolean.TRUE, uploadRow.get("isImage"));
        assertEquals("/api/moderation/pending-file/"
                + fixture.store.list(WikiPendingChange.STATUS_PENDING, WikiPendingChange.TYPE_UPLOAD, "", 1)
                .get(0).getId(), uploadRow.get("previewUrl"));

        Map<String, Object> deleteRow = rows.stream()
                .filter(row -> WikiPendingChange.TYPE_FILE_DELETE.equals(row.get("changeType")))
                .findFirst().orElseThrow();
        assertEquals("删除文件", deleteRow.get("typeLabel"));
        assertTrue(Boolean.TRUE.equals(deleteRow.get("hardDelete")));
        assertEquals("已拒绝", deleteRow.get("statusLabel"));
        assertEquals("核查员甲", deleteRow.get("reviewer"));
        assertEquals("不删", deleteRow.get("reviewComment"));

        // 页签：全部 + 三种状态，计数正确
        List<Map<String, Object>> tabs = cast(model.get("tabs"));
        assertEquals(4, tabs.size());
        assertEquals("全部", tabs.get(0).get("label"));
        assertEquals(3L, tabs.get(0).get("count"));
        assertEquals("待审", tabs.get(1).get("label"));
        assertEquals(2L, tabs.get(1).get("count"));
        assertEquals(Boolean.TRUE, tabs.get(0).get("selected"));
        assertTrue(String.valueOf(tabs.get(1).get("url")).contains("status=pending"));

        // 状态 / 类型 / 标题过滤
        Map<String, Object> pendingOnly = new LinkedHashMap<>();
        page.populate(request(Map.of("status", "pending"), user(1L, CHECKUSER)), pendingOnly);
        assertEquals(2, cast(pendingOnly.get("rows")).size());
        assertEquals(Boolean.TRUE, rows(pendingOnly.get("tabs")).get(1).get("selected"));

        Map<String, Object> uploadsOnly = new LinkedHashMap<>();
        page.populate(request(Map.of("type", "upload"), user(1L, CHECKUSER)), uploadsOnly);
        assertEquals(1, cast(uploadsOnly.get("rows")).size());

        Map<String, Object> byTitle = new LinkedHashMap<>();
        page.populate(request(Map.of("title", title), user(1L, CHECKUSER)), byTitle);
        List<Map<String, Object>> filtered = cast(byTitle.get("rows"));
        assertEquals(1, filtered.size());
        assertEquals(title, byTitle.get("titleFilter"));
        assertTrue(String.valueOf(rows(byTitle.get("tabs")).get(1).get("url")).contains("title="));

        // 非法过滤值退化为「全部」
        Map<String, Object> invalid = new LinkedHashMap<>();
        page.populate(request(Map.of("status", "bogus", "type", "bogus"), user(1L, CHECKUSER)), invalid);
        assertEquals(3, cast(invalid.get("rows")).size());
        assertEquals("", invalid.get("typeFilter"));
    }

    @Test
    void specialPageNameAliasesAndTemplate() {
        ModerationSpecialPage page = fixture().specialPage();
        assertEquals("审核", page.name());
        assertEquals("审核", page.displayName());
        assertEquals(List.of("Moderation"), page.aliases());
        assertEquals("special/moderation", page.template());
    }

    // ==================== 前端资源 / 文案 / 日志类型 ====================

    @Test
    void headProviderInjectsAssetsOnlyWhenEnabled() {
        ModerationFakes.Fixture fixture = fixture();
        ModerationHeadProvider provider = new ModerationHeadProvider(fixture.support());
        assertTrue(provider.siteWide());
        List<String> items = provider.headItems(null);
        assertTrue(items.stream().anyMatch(item -> item.contains("/plugins/moderation/moderation.js")));
        assertTrue(items.stream().anyMatch(item -> item.contains("/plugins/moderation/moderation.css")));
        assertTrue(items.stream().anyMatch(item -> item.contains("moderationApprove")));
        assertFalse(items.stream().anyMatch(item -> item.contains("/js/moderation.js")),
                "脚本 URL 已从 core 的 /js/ 迁到插件目录");

        fixture.enabled(false);
        assertEquals(List.of(), provider.headItems(null), "关闭时不注入");
    }

    @Test
    void messagesBundleKeepsTheOriginalKeys() {
        Map<String, Map<String, String>> messages = new ModerationMessages().messages();
        assertTrue(messages.get("zh").containsKey("moderation.bannerOne"));
        assertTrue(messages.get("zh").containsKey("moderation.editNotice"));
        assertTrue(messages.get("zh").containsKey("moderation.pendingCreate"));
        assertEquals("审核", messages.get("zh").get("special.moderation"));
        assertEquals("Moderation", messages.get("en").get("special.moderation"));
        assertEquals("Moderation", messages.get("en").get("log.typeName.moderation"));
        assertEquals("Moderation log", messages.get("en").get("recentChanges.logName.moderation"));
        assertEquals("文件已提交，等待审核。", messages.get("zh").get("files.uploadPending"));
        assertFalse(messages.get("zh").containsKey("settings.moderation"),
                "站点设置 / 安装向导文案仍属于 core，不随插件走");
        assertFalse(messages.get("zh").containsKey("permission.group.moderatorexempt"),
                "用户组显示名仍属于 core");
    }

    @Test
    void logTypeFormatsDetailWithLocalizedMessages() {
        ModerationFakes.Fixture fixture = fixture();
        ModerationSupport support = new ModerationSupport(ModerationFakes.config(true),
                ModerationFakes.messages(Map.of(
                        "log.moderation.change.edit", "页面编辑",
                        "log.moderation.comment", "（意见：{0}）")),
                fixture.pageStore());
        ModerationLogType approve = new ModerationLogType(ModerationLogType.ID_APPROVE, "审核", support);
        ModerationLogType reject = new ModerationLogType(ModerationLogType.ID_REJECT, "审核", support);

        assertEquals(LogType.TargetKind.PAGE, approve.targetKind());
        assertEquals(75, approve.order());
        assertEquals(76, reject.order());
        assertEquals("页面编辑（意见：通过）", approve.detailText("edit|通过"));
        assertEquals("页面编辑", approve.detailText("edit|"));
        assertEquals("页面编辑", approve.detailText("edit"));
        assertEquals("upload", approve.detailText("upload"), "未知变更类型回退原样");
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> cast(Object value) {
        return (List<T>) value;
    }

    /** 模板模型里的行 / 页签列表（{@code List<Map<String, Object>>}）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, int index) {
        return rows.get(index);
    }
}
