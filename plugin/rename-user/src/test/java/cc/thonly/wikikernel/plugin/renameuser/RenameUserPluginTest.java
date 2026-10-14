package cc.thonly.wikikernel.plugin.renameuser;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.renameuser.hook.RenameUserHeadProvider;
import cc.thonly.wikikernel.plugin.renameuser.hook.RenameUserLogType;
import cc.thonly.wikikernel.plugin.renameuser.web.RenameUserApiController;
import cc.thonly.wikikernel.plugin.renameuser.web.RenameUserSpecialPage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Set;

/**
 * 重命名用户插件的装配与行为（不启动 Spring / core，用假 {@code UserAdminStore} 桥）。
 * 覆盖：扩展点注册、消息键存在性、接口权限与结果映射、特殊页模型、日志类型详情与前端资源。
 */
class RenameUserPluginTest {

    private RenameUserFakes.FakeUserAdminStore store() {
        return new RenameUserFakes.FakeUserAdminStore();
    }

    private RenameUserSupport support() {
        return new RenameUserSupport(RenameUserFakes.pluginMessages());
    }

    private RenameUserApiController api(RenameUserFakes.FakeUserAdminStore store,
                                        RenameUserSupport support, Viewer viewer) {
        return new RenameUserApiController(store, support, RenameUserFakes.currentUser(viewer));
    }

    private RenameUserPlugin plugin(RenameUserFakes.FakeUserAdminStore store, RenameUserSupport support) {
        RenameUserPlugin plugin = new RenameUserPlugin();
        PluginContextFake.inject(plugin, "store", store);
        PluginContextFake.inject(plugin, "support", support);
        return plugin;
    }

    // ==================== 装配 ====================

    @Test
    void pluginRegistersEveryExtensionPoint() {
        RenameUserFakes.FakeUserAdminStore store = store();
        RenameUserSupport support = support();
        PluginContextFake context = new PluginContextFake(RenameUserPlugin.ID);
        plugin(store, support).onStart(context);

        assertEquals("rename-user", plugin(store, support).descriptor().id());
        assertTrue(context.has(MessageBundle.class), "文案包");
        assertTrue(context.has(SpecialPage.class), "特殊:重命名用户");
        assertTrue(context.has(LogType.class), "renameuser 日志类型");
        assertTrue(context.has(HtmlHeadProvider.class), "前端脚本");
        assertEquals(List.of("renameuser"),
                context.extensions(LogType.class).stream().map(LogType::id).toList());
        assertTrue(context.logs.stream().anyMatch(line -> line.contains("已启用重命名用户")));
    }

    // ==================== 消息键 ====================

    @Test
    void messagesBundleKeepsTheOriginalKeys() {
        Map<String, Map<String, String>> messages = new RenameUserMessages().messages();
        Map<String, String> zh = messages.get("zh");
        assertNotNull(zh);
        assertEquals("重命名用户", zh.get("plugin.rename-user.name"));
        assertEquals("重命名用户", zh.get("special.renameUser"));
        assertEquals("用户重命名", zh.get("log.typeName.renameuser"));
        assertEquals("重命名用户 {0}", zh.get("log.entry.renameuser"));
        assertTrue(zh.containsKey("renameUser.summary"));
        assertTrue(zh.containsKey("renameUser.confirm"));
        assertTrue(zh.containsKey("renameUser.success"));
        assertTrue(zh.containsKey("renameUser.recent"));
        assertTrue(zh.containsKey("log.renameuser.newName"));
        assertTrue(zh.containsKey("log.renameuser.reason"));

        Map<String, String> en = messages.get("en");
        assertNotNull(en);
        assertEquals("Rename user", en.get("plugin.rename-user.name"));
        assertEquals("Rename user", en.get("special.renameUser"));
        assertEquals("User rename", en.get("log.typeName.renameuser"));
        assertTrue(en.containsKey("plugin.rename-user.description"));
    }

    // ==================== 接口行为 ====================

    @Test
    void apiRequiresLoginAndRenamePermission() {
        RenameUserFakes.FakeUserAdminStore store = store();
        RenameUserSupport support = support();
        WikiUser target = store.addUser("甲");
        String oldName = target.getUsername();

        // 未登录 → 401
        AjaxResult<?> anon = api(store, support, Viewer.ANONYMOUS)
                .rename(oldName, "乙", null, "1");
        assertEquals(401, anon.getCode());

        // 无「重命名用户」组 → 403
        store.permitted = false;
        AjaxResult<?> denied = api(store, support, Viewer.of(9L, "普通", Set.of()))
                .rename(oldName, "乙", null, "1");
        assertEquals(403, denied.getCode());
        assertTrue(denied.getMessage().contains("权限"));
    }

    @Test
    void apiMapsRenameResults() {
        RenameUserFakes.FakeUserAdminStore store = store();
        RenameUserSupport support = support();
        WikiUser target = store.addUser("甲");
        WikiUser occupied = store.addUser("乙");
        Viewer actor = Viewer.of(1L, "操作者", Set.of("重命名用户"));

        // 找不到旧用户 → 400
        AjaxResult<?> notFound = api(store, support, actor).rename("不存在", "丙", null, "1");
        assertEquals(400, notFound.getCode());
        assertTrue(notFound.getMessage().contains("不存在"));

        // 新名被占用 → 400
        store.nextRenameResult = RenameUserFakes.FakeUserAdminStore.RenameResult.NAME_IN_USE;
        AjaxResult<?> taken = api(store, support, actor).rename("甲", "乙", null, "1");
        assertEquals(400, taken.getCode());
        assertTrue(taken.getMessage().contains("已被占用"));

        // 新旧同名 → 400
        store.nextRenameResult = RenameUserFakes.FakeUserAdminStore.RenameResult.SAME_NAME;
        AjaxResult<?> same = api(store, support, actor).rename("甲", "甲", null, "1");
        assertEquals(400, same.getCode());
        assertTrue(same.getMessage().contains("相同"));

        // 新名非法 → 400
        store.nextRenameResult = RenameUserFakes.FakeUserAdminStore.RenameResult.INVALID_NAME;
        AjaxResult<?> invalid = api(store, support, actor).rename("甲", "非法#名字", null, "1");
        assertEquals(400, invalid.getCode());
        assertTrue(invalid.getMessage().contains("无效"));

        // 全部失败后原账号仍在（「找不到旧用户」在进桥前就被拒绝，其余三种失败各调一次桥）
        assertEquals(3, store.renameRequests.size());
        assertTrue(store.byName("甲").isPresent());
        assertTrue(store.byName("乙").isPresent());
    }

    @Test
    void apiSuccessReturnsUrlAndMoved() {
        RenameUserFakes.FakeUserAdminStore store = store();
        RenameUserSupport support = support();
        WikiUser target = store.addUser("甲");
        Viewer actor = Viewer.of(1L, "操作者", Set.of("重命名用户"));

        AjaxResult<Map<String, Object>> ok = api(store, support, actor)
                .rename(target.getUsername(), "乙", "理由", "1");
        assertEquals(200, ok.getCode());
        assertTrue(ok.getMessage().contains("乙"));
        assertEquals(1, store.renameRequests.size());
        assertEquals("甲", store.renameRequests.get(0).oldName());
        assertEquals("乙", store.renameRequests.get(0).newName());
        assertEquals("理由", store.renameRequests.get(0).reason());
        assertTrue(store.renameRequests.get(0).movePages());
        assertTrue(String.valueOf(ok.getData().get("url")).contains("/wiki/"));
        assertEquals(0, ok.getData().get("moved"));
    }

    // ==================== 特殊页 ====================

    @Test
    void specialPageHidesFormWithoutPermission() {
        RenameUserFakes.FakeUserAdminStore store = store();
        RenameUserSupport support = support();
        RenameUserSpecialPage page = new RenameUserSpecialPage(store, support);

        assertEquals("重命名用户", page.name());
        assertEquals(List.of("RenameUser"), page.aliases());
        assertEquals("special/rename-user", page.template());

        store.permitted = false;
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request(Map.of(), user(9L, "普通")), model);
        assertEquals(Boolean.FALSE, model.get("canRename"));
        assertNull(model.get("logRows"), "无权限不查最近的重命名");
    }

    @Test
    void specialPageShowsRecentRenamesWhenPermitted() {
        RenameUserFakes.FakeUserAdminStore store = store();
        RenameUserSupport support = support();
        store.addLogRow("操作者", "甲", "新用户名：乙");
        RenameUserSpecialPage page = new RenameUserSpecialPage(store, support);

        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request(Map.of(), user(1L, "重命名用户")), model);
        assertEquals(Boolean.TRUE, model.get("canRename"));
        List<Map<String, Object>> rows = cast(model.get("logRows"));
        assertEquals(1, rows.size());
        assertEquals("操作者", rows.get(0).get("actorName"));
        assertEquals("甲", rows.get(0).get("targetText"));
        assertTrue(String.valueOf(rows.get(0).get("actorUrl")).contains("/wiki/"));
        assertFalse(String.valueOf(rows.get(0).get("timeText")).isEmpty(), "时间已格式化");
    }

    // ==================== 日志类型 / 前端资源 ====================

    @Test
    void logTypeFormatsDetailWithLocalizedMessages() {
        RenameUserSupport support = support();
        RenameUserLogType type = new RenameUserLogType(support);

        assertEquals("renameuser", type.id());
        assertEquals(LogType.TargetKind.USER, type.targetKind());
        assertEquals("新用户名：乙，原因：乱改", type.detailText("rename|乙|乱改"));
        assertEquals("新用户名：乙", type.detailText("rename|乙|"));
        assertEquals("新用户名：", type.detailText("rename|"));
    }

    @Test
    void headProviderInjectsPluginScript() {
        RenameUserHeadProvider provider = new RenameUserHeadProvider(support());
        assertTrue(provider.siteWide());
        List<String> items = provider.headItems(null);
        assertTrue(items.stream().anyMatch(item -> item.contains("/plugins/rename-user/rename-user.js")));
        assertFalse(items.stream().anyMatch(item -> item.contains("/js/rename-user.js")),
                "脚本 URL 已从 core 的 /js/ 迁到插件目录");
    }

    // ==================== 工具 ====================

    private static SpecialPageRequest request(Map<String, String> parameters, WikiUser user) {
        return new SpecialPageRequest("重命名用户", parameters, user);
    }

    private static WikiUser user(long id, String name) {
        WikiUser user = new WikiUser();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object value) {
        return (List<Map<String, Object>>) value;
    }
}