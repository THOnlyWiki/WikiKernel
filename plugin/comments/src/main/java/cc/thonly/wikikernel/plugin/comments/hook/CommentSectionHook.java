package cc.thonly.wikikernel.plugin.comments.hook;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.plugin.comments.service.CommentSection;

/**
 * 正文下方的评论区（{@code <comment />} 的落地位置）：
 * 页面属性里有 {@link CommentTag#PROPERTY} 且「评论」功能开启时，把评论区 HTML 插到正文之后。
 */
public class CommentSectionHook implements PageDisplayHook {

    private final CommentSection section;

    public CommentSectionHook(CommentSection section) {
        this.section = section;
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String afterContent(PageDisplayContext context) {
        if (!section.enabled() || !"1".equals(context.properties().get(CommentTag.PROPERTY))) {
            return null;
        }
        return section.render(context.title(), context.viewer());
    }
}
