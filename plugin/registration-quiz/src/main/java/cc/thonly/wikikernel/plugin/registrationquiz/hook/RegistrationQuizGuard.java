package cc.thonly.wikikernel.plugin.registrationquiz.hook;

import cc.thonly.wikikernel.common.extension.RegistrationContext;
import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizSettings;
import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizSupport;

/**
 * 注册前校验钩子（{@link RegistrationGuard}）：按下标校验注册问答的答案，
 * 答错 / 没带下标 / 下标越界都返回 {@code BLOCK}（core 以 400 + 说明拒绝注册）。
 * <p>
 * 答案不区分大小写、忽略首尾空白（见 {@link RegistrationQuizSettings#matches}）；
 * 功能关闭或题库为空时直接放行（与迁移前「开关关闭 = 不门禁」一致）。
 */
public class RegistrationQuizGuard implements RegistrationGuard {

    private final RegistrationQuizSettings settings;
    private final RegistrationQuizSupport support;

    public RegistrationQuizGuard(RegistrationQuizSettings settings, RegistrationQuizSupport support) {
        this.settings = settings;
        this.support = support;
    }

    @Override
    public Outcome beforeRegister(RegistrationContext context) {
        if (!settings.enabled()) {
            return Outcome.allow();
        }
        Integer index = parseIndex(context == null ? null : context.parameters().get("registerQuizIndex"));
        String answer = context == null ? null : context.parameters().get("registerAnswer");
        if (settings.accepts(index, answer)) {
            return Outcome.allow();
        }
        return Outcome.block(support.text("auth.registerAnswerWrong"));
    }

    /** 题目下标参数（空 / 非法 → null，按答错处理）。 */
    private static Integer parseIndex(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
