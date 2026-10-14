package cc.thonly.wikikernel.common.service;

import java.util.List;
import java.util.Map;

/**
 * 「像一次编辑那样提交」的桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：
 * **先跑平台保存前钩子**（{@code EditGuard}：插件版滥用过滤器、验证码等），再保存页面。
 * <p>
 * 与 {@link PageWriter} 的区别：
 * <ul>
 *     <li>{@link PageWriter}：直接写页面（审核通过待审更改这类「已经审过、只是落盘」用）；</li>
 *     <li>{@code EditSubmitter}：带钩子与乐观锁的「一次正常提交」（AI 代理确认提交、将来的
 *     其它自动化编辑入口用）——钩子可以拒绝（{@code blocked}）、可以接管（{@code handled}，
 *     例如审核把编辑写进队列）、也可以只给警告（{@code warnings}，仍然保存）。</li>
 * </ul>
 */
public interface EditSubmitter {

    /** 来源：AI 代理提交（与网页 / API 一样过钩子；只拦网页的钩子会放行）。 */
    String SOURCE_AI = "ai";
    /** 来源：网页编辑表单。 */
    String SOURCE_WEB = "web";
    /** 来源：Action API。 */
    String SOURCE_API = "api";

    /**
     * 一次提交。
     *
     * @param actorUserId        以谁的身份提交（必须存在）
     * @param title              页面标题
     * @param content            新正文
     * @param summary            编辑摘要
     * @param minor              小编辑
     * @param baseRevisionId     期望的当前版本（乐观锁；null = 不检查）
     * @param aiAssisted         是否 AI 协助的版本（打隐藏水印）
     * @param aiModel            AI 厂商 / 模型
     * @param source             {@link #SOURCE_AI} / {@link #SOURCE_WEB} / {@link #SOURCE_API}
     * @param clientIp           客户端 IP（滥用过滤器变量用；可空）
     * @param userAgent          客户端 UA（可空）
     * @param parameters         动作参数（钩子可见；可空）
     */
    record Request(long actorUserId, String title, String content, String summary, boolean minor,
                   Long baseRevisionId, boolean aiAssisted, String aiModel, String source,
                   String clientIp, String userAgent, Map<String, String> parameters) {

        public Request {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }

        /** AI 代理确认提交（带乐观锁与 AI 标记）。 */
        public static Request aiAssisted(long actorUserId, String title, String content, String summary,
                                         Long baseRevisionId, String aiModel) {
            return new Request(actorUserId, title, content, summary, false, baseRevisionId, true, aiModel,
                    SOURCE_AI, "", "", Map.of());
        }

        /** 普通提交（不做版本检查、非 AI）。 */
        public static Request of(long actorUserId, String title, String content, String summary) {
            return new Request(actorUserId, title, content, summary, false, null, false, null,
                    SOURCE_AI, "", "", Map.of());
        }
    }

    /**
     * 提交结果。
     *
     * @param saved      是否写入（{@code handled} 时也是 false：钩子接管了，不算 core 保存）
     * @param error      失败原因：{@code blocked}（钩子拒绝）/ {@code handled}（钩子接管，如进入审核队列）/
     *                   {@code conflict}（版本冲突）/ {@code userMissing} / {@code saveFailed}；成功为 null
     * @param message    给用户看的说明（{@code blocked} / {@code handled} 时的原因；成功时为空串）
     * @param warnings   钩子给的警告（{@code warn}，保存成功时也返回）
     * @param pageId     页面 ID（保存成功时）
     * @param revisionId 新版本 ID（保存成功时）
     */
    record Result(boolean saved, String error, String message, List<String> warnings,
                  Long pageId, Long revisionId) {

        public Result {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            message = message == null ? "" : message;
        }

        public static Result ok(long pageId, long revisionId, List<String> warnings) {
            return new Result(true, null, "", warnings, pageId, revisionId);
        }

        public static Result failure(String error, String message) {
            return new Result(false, error, message, List.of(), null, null);
        }

        /** 钩子已接管（例如审核把编辑写进队列）：没有保存，但也不算失败。 */
        public static Result handled(String message) {
            return new Result(false, "handled", message, List.of(), null, null);
        }
    }

    /** 提交（先跑钩子，再保存）。 */
    Result submit(Request request);
}
