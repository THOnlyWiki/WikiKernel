package cc.thonly.wikikernel.plugin.comments.hook;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.plugin.comments.hook.CommentTag;
import cc.thonly.wikikernel.plugin.comments.service.CommentSection;

import java.util.Locale;
import java.util.Map;

/**
 * 正文下方的评论区：默认自动显示；{@code <comment />} 手动开启，
 * {@code <comment disabled />} 显式关闭本页自动评论区。
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
        if (!section.enabled()) {
            return null;
        }
        Map<String, String> properties = context.properties();
        if ("1".equals(properties.get(CommentTag.DISABLED_PROPERTY))) {
            return null;
        }
        boolean manual = "1".equals(properties.get(CommentTag.PROPERTY));
        if (!manual && !section.autoDisplay()) {
            return null;
        }
        if (!manual && !autoEligible(context)) {
            return null;
        }
        return section.render(context.title(), context.viewer(), manual);
    }

    /** 自动模式不打扰代码页 / 系统页 / 旧版本（显式 {@code <comment />} 不受此限制）。 */
    private static boolean autoEligible(PageDisplayContext context) {
        if (context.oldRevision()) {
            return false;
        }
        String title = context.title() == null ? "" : context.title().trim().toLowerCase(Locale.ROOT);
        if (title.isEmpty()) {
            return false;
        }
        if (title.startsWith("特殊:") || title.startsWith("special:")
                || title.startsWith("模块:") || title.startsWith("module:")
                || title.startsWith("微件:") || title.startsWith("widget:")
                || title.startsWith("wikikernel:") || title.startsWith("mediawiki:")) {
            return false;
        }
        return !(title.endsWith(".js") || title.endsWith(".ts") || title.endsWith(".tsx")
                || title.endsWith(".vue") || title.endsWith(".css") || title.endsWith(".json"));
    }
}
