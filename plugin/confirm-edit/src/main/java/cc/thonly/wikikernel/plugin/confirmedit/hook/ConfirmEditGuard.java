package cc.thonly.wikikernel.plugin.confirmedit.hook;

import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditSupport;
import cc.thonly.wikikernel.plugin.confirmedit.service.CaptchaChallenge;

/**
 * 保存前校验钩子（{@link EditGuard}）：未豁免用户（含匿名）提交编辑 / 创建页面时，
 * 必须答对当前会话里的那道加法题，否则 {@code BLOCK}（core 回到编辑表单并保留已填内容与摘要，
 * 插件的表单字段会因此换一道新题）。
 * <p>
 * **只拦网页编辑表单**：{@code api.php?action=edit} 的来源是 {@code api}，直接放行
 * ——与迁移前 core 的行为一致（ConfirmEdit 不影响 Action API）。
 */
public class ConfirmEditGuard implements EditGuard {

    private final ConfirmEditSupport support;

    public ConfirmEditGuard(ConfirmEditSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Outcome beforeSave(EditContext context) {
        if (context == null || !context.web()) {
            // Action API（source=api）不受验证码影响（迁移前 api.php 同样不校验）
            return Outcome.allow();
        }
        if (!support.appliesTo(context.viewer())) {
            return Outcome.allow();
        }
        String answer = context.parameters().get("captchaAnswer");
        if (support.validate(context.session(), answer) == CaptchaChallenge.Result.OK) {
            return Outcome.allow();
        }
        return Outcome.block(support.text("confirmEdit.wrong"));
    }
}
