package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.Map;

/**
 * 扩展点：**通用动作钩子**（仿 MediaWiki 滥用过滤器对各个动作的钩子，如 {@code AbortMove} /
 * {@code UploadVerifyFile} / {@code ArticleDelete} / {@code BlockIp}）。
 * <p>
 * 编辑 / 创建页面不走这里（用 {@link EditGuard} + {@link EditContext}）；本钩子覆盖
 * 移动 / 上传 / 删除文件 / 封禁这类**非编辑动作**，core 在动作真正执行之前按 {@link #order()} 升序调用：
 * <ul>
 *     <li>{@link Outcome.Result#ALLOW}：放行，继续问下一个钩子；</li>
 *     <li>{@link Outcome.Result#BLOCK}：拒绝本次动作，{@code message} 回给调用方
 *     （网页表单回到原页面并显示，API 返回 4xx + message）；</li>
 *     <li>{@link Outcome.Result#WARN}：放行但把 {@code message} 回给调用方（网页顶部提示 / API 响应里的 warnings）；</li>
 *     <li>{@link Outcome.Result#HANDLED}：钩子已经自行处理（例如「审核」把上传写进队列而不是落盘），
 *     core 不再执行该动作，并把结果当作**成功**回给调用方（{@code message} 给用户看，
 *     {@link Outcome#data()} 并入响应数据）。</li>
 * </ul>
 * 约定：返回 {@code null} 视为放行；钩子抛异常时 core 记警告并**跳过该钩子**（动作照常执行，不会 500）；
 * 没有任何插件注册时动作行为与迁移前完全一致。
 */
public interface ActionGuard extends Extension {

    /** 执行顺序，值越小越先执行（先执行的钩子可以拦住后面的）。 */
    default int order() {
        return 0;
    }

    /** 动作执行前检查（返回 {@link Outcome#allow()} 表示没意见）。 */
    Outcome beforeAction(ActionContext context);

    /**
     * 钩子结论。
     *
     * @param result  结论（{@code ALLOW} 放行 / {@code BLOCK} 拒绝 / {@code WARN} 放行但提示 /
     *                {@code HANDLED} 钩子已自行处理）
     * @param message 给用户看的说明（{@code BLOCK} / {@code WARN} / {@code HANDLED} 时使用）
     * @param data    {@code HANDLED} 时并入响应数据的附加字段（其它结论忽略；可为空 Map）
     */
    record Outcome(Result result, String message, Map<String, Object> data) {

        /** 结论类型。 */
        public enum Result {
            /** 放行。 */
            ALLOW,
            /** 拒绝本次动作，并把 message 回给调用方。 */
            BLOCK,
            /** 放行但提示（message 回给调用方）。 */
            WARN,
            /** 钩子已自行处理，core 不再执行该动作，并当作成功回给调用方。 */
            HANDLED
        }

        public Outcome {
            result = result == null ? Result.ALLOW : result;
            message = message == null ? "" : message;
            data = data == null ? Map.of() : Map.copyOf(data);
        }

        /** 放行。 */
        public static Outcome allow() {
            return new Outcome(Result.ALLOW, "", Map.of());
        }

        /** 拒绝本次动作（message 显示在网页 / API 响应里）。 */
        public static Outcome block(String message) {
            return new Outcome(Result.BLOCK, message, Map.of());
        }

        /** 放行但提示。 */
        public static Outcome warn(String message) {
            return new Outcome(Result.WARN, message, Map.of());
        }

        /** 钩子已自行处理：core 不执行该动作，把 message 当作成功提示回给调用方。 */
        public static Outcome handled(String message) {
            return new Outcome(Result.HANDLED, message, Map.of());
        }

        /**
         * 钩子已自行处理 + 附加响应数据（如网页上传的 {@code pending=true}：
         * core 原样并入网页响应的 {@code data} / API 响应的动作对象里）。
         */
        public static Outcome handled(String message, Map<String, Object> data) {
            return new Outcome(Result.HANDLED, message, data);
        }
    }
}
