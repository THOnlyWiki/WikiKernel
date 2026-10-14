package cc.thonly.wikikernel.plugin.renameuser.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.renameuser.RenameUserSupport;

import java.util.List;

/**
 * 重命名用户的前端资源（站点级注入，原 core 的 {@code static/js/rename-user.js}）：
 * {@code /plugins/rename-user/rename-user.js}（表单提交 + 确认 + 成功后跳转）。
 */
public final class RenameUserHeadProvider implements HtmlHeadProvider {

    private final RenameUserSupport support;

    public RenameUserHeadProvider(RenameUserSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 665;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        return List.of("<script defer src=\"/plugins/rename-user/rename-user.js\"></script>");
    }
}