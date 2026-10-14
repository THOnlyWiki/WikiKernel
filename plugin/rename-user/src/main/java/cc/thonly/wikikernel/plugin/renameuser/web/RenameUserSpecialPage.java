package cc.thonly.wikikernel.plugin.renameuser.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.renameuser.RenameUserSupport;
import cc.thonly.wikikernel.plugin.renameuser.service.UserAdminStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:重命名用户} / {@code Special:RenameUser}（仿 MediaWiki Renameuser 扩展；原 core 的
 * {@code special/RenameUserSpecialPage} 迁入，模板随之放到插件 jar 的 {@code templates/} 下）：
 * 需要「重命名用户」权限组；显示改名表单与「最近的重命名」（{@code renameuser} 类型日志）。
 * <p>
 * 权限判定委托插件自己的 {@code UserAdminStore#canRename}（按「重命名用户」组查询，组名与 core 的
 * {@code PermissionService} 一致）；表单由 {@code POST /api/user/rename} 提交，
 * 前端脚本 {@code /plugins/rename-user/rename-user.js} 负责确认与成功跳转。
 */
public class RenameUserSpecialPage implements SpecialPage {

    /** 最近的重命名最多显示多少条。 */
    private static final int LOG_LIMIT = 20;

    private final UserAdminStore store;
    private final RenameUserSupport support;

    public RenameUserSpecialPage(UserAdminStore store, RenameUserSupport support) {
        this.store = store;
        this.support = support;
    }

    @Override
    public String name() {
        return "重命名用户";
    }

    @Override
    public String displayName() {
        return support.message("special.renameUser");
    }

    @Override
    public List<String> aliases() {
        return List.of("RenameUser");
    }

    @Override
    public String template() {
        return "special/rename-user";
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Viewer viewer = toViewer(request.user());
        boolean canRename = store.canRename(viewer);
        model.put("canRename", canRename);
        if (!canRename) {
            return;
        }
        model.put("logRows", logRows());
    }

    private Viewer toViewer(WikiUser user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return Viewer.ANONYMOUS;
        }
        return Viewer.of(user.getId(), user.getUsername(), Set.of());
    }

    /** 最近的重命名日志行（已格式化时间，模板直接渲染）。 */
    private List<Map<String, Object>> logRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UserAdminStore.RenameLogRow row : store.recentRenames(LOG_LIMIT)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timeText", support.formatTime(row.time()));
            map.put("actorName", Strings.trimToEmpty(row.actorName()));
            map.put("actorUrl", Strings.isBlank(row.actorName()) ? null
                    : RenameUserSupport.pagePath("用户:" + row.actorName()));
            map.put("actionBefore", Strings.trimToEmpty(row.actionBefore()));
            map.put("targetUrl", Strings.isBlank(row.targetUrl()) ? null : row.targetUrl());
            map.put("targetText", Strings.trimToEmpty(row.targetText()));
            map.put("actionAfter", Strings.trimToEmpty(row.actionAfter()));
            map.put("detailText", Strings.trimToEmpty(row.detailText()));
            rows.add(map);
        }
        return rows;
    }
}