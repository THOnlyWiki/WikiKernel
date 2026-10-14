package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：**注册前校验钩子**（仿 MediaWiki 的 {@code AbortNewAccount} / QuestyCaptcha 的注册校验）。
 * <p>
 * core 在真正创建账号之前按 {@link #order()} 升序调用：
 * <ul>
 *     <li>{@link Outcome.Result#ALLOW}：放行，继续问下一个钩子；</li>
 *     <li>{@link Outcome.Result#BLOCK}：拒绝注册，{@code message} 以 400 返回给页面
 *     （注册页的 {@code /js/auth.js} 会显示在表单里）。</li>
 * </ul>
 * 约定：返回 {@code null} 视为放行；钩子抛异常时 core 记警告并**跳过该钩子**（注册照常可用）。
 */
public interface RegistrationGuard extends Extension {

    /** 执行顺序，值越小越先执行。 */
    default int order() {
        return 0;
    }

    /** 注册前校验。 */
    Outcome beforeRegister(RegistrationContext context);

    /**
     * 钩子结论。
     *
     * @param result  结论类型
     * @param message 给用户看的说明（{@code BLOCK} 时使用）
     */
    record Outcome(Result result, String message) {

        /** 结论类型。 */
        public enum Result {
            /** 放行。 */
            ALLOW,
            /** 拒绝注册，并把 message 返回给用户。 */
            BLOCK
        }

        public Outcome {
            result = result == null ? Result.ALLOW : result;
            message = message == null ? "" : message;
        }

        /** 放行。 */
        public static Outcome allow() {
            return new Outcome(Result.ALLOW, "");
        }

        /** 拒绝注册（message 显示在注册表单里）。 */
        public static Outcome block(String message) {
            return new Outcome(Result.BLOCK, message);
        }
    }
}
