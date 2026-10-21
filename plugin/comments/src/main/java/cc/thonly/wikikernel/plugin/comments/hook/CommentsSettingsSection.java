package cc.thonly.wikikernel.plugin.comments.hook;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.plugin.comments.CommentsPlugin;
import cc.thonly.wikikernel.plugin.comments.service.CommentSection;

import java.util.List;

/**
 * 站点设置里的评论分区：总开关 + 自动显示（默认开启）。
 * 使用 {@code id=comments} 后会替代 core 自动生成的功能开关，所以这里同时包含总开关。
 */
public final class CommentsSettingsSection implements SiteSettingsSection {

    private final CommentSection section;

    public CommentsSettingsSection(CommentSection section) {
        this.section = section;
    }

    @Override
    public String id() {
        return "comments";
    }

    @Override
    public String title() {
        return section.message("comments.settings.title");
    }

    @Override
    public String description() {
        return section.message("comments.settings.description");
    }

    @Override
    public int order() {
        return 250;
    }

    @Override
    public List<SettingField> fields() {
        return List.of(
                SettingField.checkbox(CommentsPlugin.ENABLED_KEY,
                        section.message("comments.settings.enabled"), true),
                SettingField.checkbox(CommentsPlugin.AUTO_DISPLAY_KEY,
                        section.message("comments.settings.autoDisplay"), true));
    }
}
