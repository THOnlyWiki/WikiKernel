package cc.thonly.wikikernel.common.extension;

import java.util.Map;

/**
 * 一次「非编辑动作」的上下文，供 {@link ActionGuard}（动作前钩子）使用
 * （仿 MediaWiki 滥用过滤器对 {@code move} / {@code delete} / {@code upload} / {@code block} 等动作的钩子参数）。
 * <p>
 * 编辑 / 创建页面不走这里，用 {@link EditContext} + {@link EditGuard}。
 * 来源常量与 {@link EditContext} 保持一致（{@code web} / {@code api}），钩子可以据此只拦网页操作。
 */
public interface ActionContext {

    /** 来源：网页表单（如 {@code 特殊:移动页面} 提交到 {@code POST /api/pages/move}）。 */
    String SOURCE_WEB = EditContext.SOURCE_WEB;
    /** 来源：外部 API 调用。 */
    String SOURCE_API = EditContext.SOURCE_API;

    /** 提交者（匿名时为 {@link Viewer#ANONYMOUS}）。 */
    Viewer viewer();

    /** 动作名：{@code move} / {@code delete} / {@code upload} / {@code protect} / {@code block} / {@code purge} … */
    String action();

    /** 目标（页面标题 / 文件名 / 用户名等；没有单一目标时为空串）。 */
    String target();

    /** 原因 / 摘要（可空串）。 */
    String summary();

    /**
     * 动作相关数据（只读）：
     * <ul>
     *     <li>{@code move}：{@code from} / {@code to}；</li>
     *     <li>{@code upload}：{@code name} / {@code size} / {@code contentType} / {@code description}；</li>
     *     <li>{@code delete}：{@code name} / {@code id} / {@code hard}；</li>
     *     <li>{@code block}：{@code target} / {@code expiry} / {@code reason} …</li>
     * </ul>
     */
    Map<String, String> data();

    /** 表单 / API 参数（只读；与 {@link #data()} 的区别是这里保留调用方的原始参数）。 */
    Map<String, String> parameters();

    /** 会话属性读写：钩子可以在这里放「再次确认」这类一次性的状态。 */
    SessionAttributes session();

    /** 客户端 IP（取不到为空串）。 */
    String clientIp();

    /** 客户端 User-Agent（取不到为空串）。 */
    String userAgent();

    /** 来源：{@link #SOURCE_WEB} / {@link #SOURCE_API}。 */
    String source();

    /**
     * 本次动作的**二进制载荷**（没有则为 {@code null}）：
     * 目前只有上传（{@code action=upload}）会带上文件内容——「审核」这类钩子需要把文件
     * **暂存**起来而不是拒绝（移动 / 删除 / 封禁等动作没有载荷）。
     * <p>
     * 默认 {@code null}：不关心载荷的 {@link ActionContext} 实现（以及测试替身）无需实现它。
     */
    default byte[] payload() {
        return null;
    }

    /** 是否来自网页表单（等价于 {@code SOURCE_WEB.equals(source())}）。 */
    default boolean web() {
        return SOURCE_WEB.equals(source());
    }

    /** 是否来自外部 API（等价于 {@code SOURCE_API.equals(source())}）。 */
    default boolean api() {
        return SOURCE_API.equals(source());
    }
}
