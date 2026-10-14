package cc.thonly.wikikernel.plugin.usermerge;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.usermerge.hook.UserMergeHeadProvider;
import cc.thonly.wikikernel.plugin.usermerge.hook.UserMergeLogType;
import cc.thonly.wikikernel.plugin.usermerge.web.UserMergeApiController;
import cc.thonly.wikikernel.plugin.usermerge.web.UserMergeSpecialPage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户合并插件的装配与行为（不启动 Spring / core，用假 {@code UserMergeStore} 桥）。
 * 覆盖：扩展点注册、消息键存在性、接口权限与结果映射、特殊页模型、日志类型详情与前端资源。
 * 注意：不真合并用户——桥替身只记录请求并返回可配置结果。
 */
class UserMergePluginTest {

    private static final String CHECKUSER = "核查员甲";
    private static final String PLAIN = "普通用户甲";

    private UserMergeFakes.FakeUserMergeStore store() {
        return new UserMergeFakes.FakeUserMergeStore();
    }

    private UserMergeSupport support() {
        return new UserMergeSupport(UserMergeFakes.pluginMessages());
    }

    private UserMergeApiController api(UserMergeFakes.FakeUserMergeStore store,
                                       UserMergeSupport support, Viewer viewer) {
        return new UserMergeApiController(store, support, UserMergeFakes.currentUser(viewer));
    }

    private UserMergePlugin plugin(UserMergeFakes.FakeUserMergeStore store, UserMergeSupport support) {
        UserMergePlugin plugin = new UserMergePlugin();
        PluginContextFake.inject(plugin, "store", store);
        PluginContextFake.inject(plugin, "support", support);
        return plugin;
    }

    // ==================== 装配 ====================

    @Test
    void pluginRegistersEveryExtensionPoint() {
        UserMergeFakes.FakeUserMergeStore store = store();
        UserMergeSupport support = support();
        PluginContextFake context = new PluginContextFake(UserMergePlugin.ID);
        plugin(store, support).onStart(context);

        assertEquals("user-merge", plugin(store, support).descriptor().id());
        assertTrue(context.has(MessageBundle.class), "文案包");
        assertTrue(context.has(SpecialPage.class), "特殊:用户合并");
        assertTrue(context.has(LogType.class), "usermerge 日志类型");
        assertTrue(context.has(HtmlHeadProvider.class), "前端脚本");
        assertEquals(List.of("usermerge"),
                context.extensions(LogType.class).stream().map(LogType::id).toList());
        assertTrue(context.logs.stream().anyMatch(line -> line.contains("已启用用户合并")));
    }

    // ==================== 消息键 ====================

    @Test
    void messagesBundleKeepsTheOriginalKeys() {
        Map<String, Map<String, String>> messages = new UserMergeMessages().messages();
        Map<String, String> zh = messages.get("zh");
        assertNotNull(zh);
        assertEquals("用户合并", zh.get("plugin.user-merge.name"));
        assertEquals("用户合并", zh.get("special.userMerge"));
        assertEquals("用户合并", zh.get("log.typeName.usermerge"));
        assertEquals("合并用户到 {0}", zh.get("log.entry.usermerge"));
        assertTrue(zh.containsKey("userMerge.forbidden"));
        assertTrue(zh.containsKey("userMerge.done"));
        assertTrue(zh.containsKey("userMerge.sourceNotFound"));
        assertTrue(zh.containsKey("userMerge.targetNotFound"));
        assertTrue(zh.containsKey("userMerge.sameUser"));
        assertTrue(zh.containsKey("log.usermerge.source"));
        assertTrue(zh.containsKey("log.usermerge.deleted"));
        assertTrue(zh.containsKey("log.usermerge.kept"));

        Map<String, String> en = messages.get("en");
        assertNotNull(en);
        assertEquals("User merge", en.get("plugin.user-merge.name"));
        assertEquals("User merge", en.get("special.userMerge"));
        assertTrue(en.containsKey("plugin.user-merge.description"));
    }

    // ==================== 接口行为 ====================

    @Test
    void apiRequiresLoginAndCheckuserPermission() {
        UserMergeFakes.FakeUserMergeStore store = store();
        UserMergeSupport support = support();
        WikiUser source = store.addUser("甲");
        WikiUser target = store.addUser("乙");

        // 未登录 → 401
        AjaxResult<?> anon = api(store, support, Viewer.ANONYMOUS)
                .merge(source.getUsername(), target.getUsername(), "true");
        assertEquals(401, anon.getCode());

        // 普通用户（无核查员组）→ 403
        store.permitted = false;
        AjaxResult<?> denied = api(store, support, Viewer.of(9L, PLAIN, Set.of()))
                .merge(source.getUsername(), target.getUsername(), "true");
        assertEquals(403, denied.getCode());
        assertTrue(denied.getMessage().contains("只有核查员"));

        // 空参数 → 400
        store.permitted = true;
        AjaxResult<?> blank = api(store, support, Viewer.of(1L, CHECKUSER, Set.of("核查员")))
                .merge(" ", " ", "true");
        assertEquals(400, blank.getCode());
        assertEquals(0, store.mergeRequests.size(), "空参数不进桥");
    }

    @Test
    void apiMapsMergeResultsAndPassesParams() {
        UserMergeFakes.FakeUserMergeStore store = store();
        UserMergeSupport support = support();
        WikiUser source = store.addUser("甲");
        WikiUser target = store.addUser("乙");
        Viewer checkuser = Viewer.of(1L, CHECKUSER, Set.of("核查员"));

        // 成功（删除源账号）
        store.nextResult = UserMergeFakes.FakeUserMergeStore.Result.OK;
        AjaxResult<?> ok = api(store, support, checkuser)
                .merge(source.getUsername(), target.getUsername(), "true");
        assertEquals(200, ok.getCode());
        assertTrue(ok.getMessage().contains("已把用户"));
        assertEquals(1, store.mergeRequests.size());
        assertEquals("甲", store.mergeRequests.get(0).sourceName());
        assertEquals("乙", store.mergeRequests.get(0).targetName());
        assertTrue(store.mergeRequests.get(0).deleteSource());

        // 找不到源 → 404
        store.nextResult = UserMergeFakes.FakeUserMergeStore.Result.SOURCE_NOT_FOUND;
        AjaxResult<?> src = api(store, support, checkuser).merge("不存在", "乙", "true");
        assertEquals(404, src.getCode());
        assertTrue(src.getMessage().contains("找不到源用户"));

        // 找不到目标 → 404
        store.nextResult = UserMergeFakes.FakeUserMergeStore.Result.TARGET_NOT_FOUND;
        AjaxResult<?> tgt = api(store, support, checkuser).merge("甲", "不存在", "true");
        assertEquals(404, tgt.getCode());
        assertTrue(tgt.getMessage().contains("找不到目标用户"));

        // 源 = 目标 → 400
        store.nextResult = UserMergeFakes.FakeUserMergeStore.Result.SAME_USER;
        AjaxResult<?> same = api(store, support, checkuser).merge("甲", "甲", "true");
        assertEquals(400, same.getCode());
        assertTrue(same.getMessage().contains("不能相同"));
    }

    // ==================== 特殊页 ====================

    @Test
    void specialPageHidesFormWithoutPermission() {
        UserMergeFakes.FakeUserMergeStore store = store();
        UserMergeSupport support = support();
        UserMergeSpecialPage page = new UserMergeSpecialPage(store, support);

        assertEquals("用户合并", page.name());
        assertEquals(List.of("UserMerge"), page.aliases());
        assertEquals("special/user-merge", page.template());

        store.permitted = false;
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request(Map.of(), user(9L, PLAIN)), model);
        assertEquals(Boolean.FALSE, model.get("canMerge"));
        assertNull(model.get("oldUserValue"), "无权限不填源用户");
    }

    @Test
    void specialPageShowsFormAndPrefillsForCheckuser() {
        UserMergeFakes.FakeUserMergeStore store = store();
        UserMergeSupport support = support();
        UserMergeSpecialPage page = new UserMergeSpecialPage(store, support);

        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request(Map.of("oldUser", "甲", "newUser", "乙"), user(1L, CHECKUSER)), model);
        assertEquals(Boolean.TRUE, model.get("canMerge"));
        assertEquals("甲", model.get("oldUserValue"));
        assertEquals("乙", model.get("newUserValue"));
    }

    // ==================== 日志类型 / 前端资源 ====================

    @Test
    void logTypeFormatsDetailWithLocalizedMessages() {
        UserMergeSupport support = support();
        UserMergeLogType type = new UserMergeLogType(support);

        assertEquals("usermerge", type.id());
        assertEquals(LogType.TargetKind.USER, type.targetKind());
        assertEquals("源账号：甲（已删除）", type.detailText("merge|甲|deleted"));
        assertEquals("源账号：甲（已保留）", type.detailText("merge|甲|kept"));
        assertEquals("源账号：甲", type.detailText("merge|甲|"));
    }

    @Test
    void headProviderInjectsPluginScript() {
        UserMergeHeadProvider provider = new UserMergeHeadProvider(support());
        assertTrue(provider.siteWide());
        List<String> items = provider.headItems(null);
        assertTrue(items.stream().anyMatch(item -> item.contains("/plugins/user-merge/user-merge.js")));
        assertFalse(items.stream().anyMatch(item -> item.contains("/js/user-merge.js")),
                "脚本 URL 已从 core 的 /js/ 迁到插件目录");
    }

    // ==================== 工具 ====================

    private static SpecialPageRequest request(Map<String, String> parameters, WikiUser user) {
        return new SpecialPageRequest("用户合并", parameters, user);
    }

    private static WikiUser user(long id, String name) {
        WikiUser user = new WikiUser();
        user.setId(id);
        user.setUsername(name);
        return user;
    }
}