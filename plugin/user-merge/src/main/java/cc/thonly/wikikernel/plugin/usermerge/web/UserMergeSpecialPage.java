package cc.thonly.wikikernel.plugin.usermerge.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.usermerge.UserMergeSupport;
import cc.thonly.wikikernel.plugin.usermerge.service.UserMergeStore;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:用户合并} / {@code Special:UserMerge}（仿 MediaWiki UserMerge 扩展；原 core 的
 * {@code special/UserMergeSpecialPage} 迁入，模板随之放到插件 jar 的 {@code templates/} 下）：
 * 核查员专用；表单由 {@code POST /api/user-merge} 提交，前端脚本 {@code /plugins/user-merge/user-merge.js}
 * 负责结果显示。
 * <p>
 * 权限判定委托插件自己的 {@code UserMergeStore#canMerge}（按「核查员」用户组查询，组名与 core 的
 * {@code PermissionService} 一致）；「要合并的用户（源） / 合并到的用户（目标）」
 * 可用 URL 参数 {@code oldUser} / {@code newUser} 预填。
 */
public class UserMergeSpecialPage implements SpecialPage {

    private final UserMergeStore store;
    private final UserMergeSupport support;

    public UserMergeSpecialPage(UserMergeStore store, UserMergeSupport support) {
        this.store = store;
        this.support = support;
    }

    @Override
    public String name() {
        return "用户合并";
    }

    @Override
    public String displayName() {
        return support.message("special.userMerge");
    }

    @Override
    public List<String> aliases() {
        return List.of("UserMerge");
    }

    @Override
    public String template() {
        return "special/user-merge";
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Viewer viewer = toViewer(request.user());
        boolean canMerge = store.canMerge(viewer);
        model.put("canMerge", canMerge);
        if (!canMerge) {
            return;
        }
        model.put("oldUserValue", Strings.trimToEmpty(param(request, "oldUser", "olduser")));
        model.put("newUserValue", Strings.trimToEmpty(param(request, "newUser", "newuser")));
    }

    private static String param(SpecialPageRequest request, String key, String altKey) {
        String value = request.parameters().get(key);
        return value == null ? request.parameters().get(altKey) : value;
    }

    private Viewer toViewer(WikiUser user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return Viewer.ANONYMOUS;
        }
        return Viewer.of(user.getId(), user.getUsername(), Set.of());
    }
}