package cc.thonly.wikikernel.plugin.gadgets.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.plugin.gadgets.GadgetsPlugin;
import cc.thonly.wikikernel.plugin.gadgets.service.GadgetService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:Gadgets} / {@code 特殊:小部件管理}（仿 MediaWiki 的 {@code Special:Gadgets}）：
 * 列出全部 Gadget 定义、资源与启用状态，并给出个人设置 / 定义页入口。
 * <p>
 * 「小部件管理员」可编辑定义页与资源页（编辑权限仍由 core 的访问控制规则决定，
 * 这里只决定是否显示「编辑 Gadgets-definition」链接）。
 */
public class GadgetsSpecialPage implements SpecialPage {

    private final GadgetService gadgets;
    private final CurrentUser currentUser;

    public GadgetsSpecialPage(GadgetService gadgets, CurrentUser currentUser) {
        this.gadgets = gadgets;
        this.currentUser = currentUser;
    }

    @Override
    public String name() {
        return "Gadgets";
    }

    @Override
    public String displayName() {
        return "Gadgets";
    }

    @Override
    public List<String> aliases() {
        return List.of("小部件管理");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/gadgets";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        model.put("enabled", gadgets.enabled());
        model.put("canManage", currentUser != null && currentUser.inGroup(GadgetsPlugin.GROUP_GADGET_ADMIN));
        model.put("definitionUrl", gadgets.definitionPageUrl());
        model.put("preferencesUrl", "/wiki/特殊:参数设置");
        model.put("gadgets", gadgets.visibleDefinitions().stream().map(definition -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", definition.name());
            row.put("description", definition.description());
            row.put("defaultEnabled", definition.defaultEnabled());
            row.put("scripts", definition.scripts());
            row.put("styles", definition.styles());
            row.put("dependencies", definition.dependencies());
            row.put("enabled", gadgets.isEnabledForCurrentUser(definition.name()));
            return row;
        }).toList());
    }
}
