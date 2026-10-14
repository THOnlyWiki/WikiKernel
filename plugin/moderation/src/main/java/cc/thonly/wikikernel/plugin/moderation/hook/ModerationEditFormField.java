package cc.thonly.wikikernel.plugin.moderation.hook;

import cc.thonly.wikikernel.common.extension.EditFormContext;
import cc.thonly.wikikernel.common.extension.EditFormField;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationService;

/**
 * 审核的**编辑页提示**（{@code EditFormField.notice}）：当前用户没有豁免权时，
 * 在保存按钮附近提示「保存后更改将进入审核队列」（原 core 的 {@code wiki/edit.html} 提示块）。
 * <p>
 * 不插入表单字段（{@link #html} 返回 {@code null}），只给提示；开关关闭 / 已登录且豁免 / 匿名时都不提示。
 */
public class ModerationEditFormField implements EditFormField {

    private final ModerationService service;
    private final ModerationSupport support;

    public ModerationEditFormField(ModerationService service, ModerationSupport support) {
        this.service = service;
        this.support = support;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public String html(EditFormContext context) {
        return null;
    }

    @Override
    public String notice(EditFormContext context) {
        if (!service.enabled() || !service.shouldModerate(context.viewer())) {
            return null;
        }
        return "<span class=\"moderation-edit-notice\">"
                + ModerationSupport.escape(support.message("moderation.editNotice"))
                + "</span>";
    }
}
