package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：**保存前钩子**（仿 MediaWiki 的 {@code EditFilterMergedContent}）。
 * <p>
 * core 在**每一次要写入页面正文之前**按 {@link #order()} 升序调用（网页编辑表单与
 * {@code api.php?action=edit} 都会走，来源见 {@link EditContext#source()}）：
 * <ul>
 *     <li>{@link Outcome.Result#ALLOW}：放行，继续问下一个钩子；</li>
 *     <li>{@link Outcome.Result#BLOCK}：拒绝保存，{@code message} 显示给用户（网页回到编辑表单并保留已填内容，
 *     API 返回 400 类错误）；</li>
 *     <li>{@link Outcome.Result#WARN}：放行并继续，但 {@code message} 会在保存成功后提示给用户；</li>
 *     <li>{@link Outcome.Result#HANDLED}：钩子已经自行处理（例如「审核」把编辑写进队列），core 不再保存，
 *     并按 {@link Outcome#redirect()} 跳转（网页）/ 返回已处理结果（API）。</li>
 * </ul>
 * 约定：返回 {@code null} 视为放行；钩子抛异常时 core 记警告并**跳过该钩子**（页面不会 500）。
 */
public interface EditGuard extends Extension {

    /** 执行顺序，值越小越先执行（先执行的钩子可以拦住后面的）。 */
    default int order() {
        return 0;
    }

    /** 保存前检查（不允许返回 {@code null} 之外的“没意见”表达：返回 {@link Outcome#allow()}）。 */
    Outcome beforeSave(EditContext context);

    /**
     * 钩子结论。
     *
     * @param result   结论类型
     * @param message  给用户看的说明（{@code BLOCK} / {@code WARN} 时使用；{@code ALLOW} 可留空）
     * @param redirect {@code HANDLED} 时的跳转目标（站内路径；空 = 跳回页面本身）
     */
    record Outcome(Result result, String message, String redirect) {

        /** 结论类型。 */
        public enum Result {
            /** 放行。 */
            ALLOW,
            /** 拒绝保存，并把 message 显示给用户。 */
            BLOCK,
            /** 放行但提示（保存成功后显示 message）。 */
            WARN,
            /** 钩子已自行处理，core 不再保存，按 redirect 跳转。 */
            HANDLED
        }

        public Outcome {
            result = result == null ? Result.ALLOW : result;
            message = message == null ? "" : message;
            redirect = redirect == null ? "" : redirect;
        }

        /** 放行。 */
        public static Outcome allow() {
            return new Outcome(Result.ALLOW, "", "");
        }

        /** 拒绝保存（message 显示在编辑页 / API 错误里）。 */
        public static Outcome block(String message) {
            return new Outcome(Result.BLOCK, message, "");
        }

        /** 放行但提示。 */
        public static Outcome warn(String message) {
            return new Outcome(Result.WARN, message, "");
        }

        /** 钩子已自行处理：core 不保存，跳到站内路径 {@code redirect}（空 = 跳回页面本身）。 */
        public static Outcome handled(String redirect) {
            return new Outcome(Result.HANDLED, "", redirect);
        }

        /** 钩子已自行处理 + 说明。 */
        public static Outcome handled(String redirect, String message) {
            return new Outcome(Result.HANDLED, message, redirect);
        }
    }
}
