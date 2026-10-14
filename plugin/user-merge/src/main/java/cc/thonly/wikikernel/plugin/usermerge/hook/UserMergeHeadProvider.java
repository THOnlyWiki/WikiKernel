package cc.thonly.wikikernel.plugin.usermerge.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.usermerge.UserMergeSupport;

import java.util.List;

/**
 * 用户合并的前端资源（站点级注入，原 core 的 {@code static/js/user-merge.js}）：
 * {@code /plugins/user-merge/user-merge.js}（表单提交 + 结果显示）。
 */
public final class UserMergeHeadProvider implements HtmlHeadProvider {

    private final UserMergeSupport support;

    public UserMergeHeadProvider(UserMergeSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 666;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        return List.of("<script defer src=\"/plugins/user-merge/user-merge.js\"></script>");
    }
}