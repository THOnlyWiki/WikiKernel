package cc.thonly.wikikernel.common.extension;

import java.util.List;
import java.util.Map;

/**
 * 一次「保存编辑」的上下文，供 {@link EditGuard} 使用（仿 MediaWiki 的 {@code EditFilterMergedContent} /
 * {@code ArticleSave} 一类钩子的参数）。
 * <p>
 * 网页编辑表单与 Action API（{@code api.php?action=edit}）都会构造它，用 {@link #source()} 区分来源：
 * 钩子可以据此只拦截网页保存（例如 ConfirmEdit 的验证码不拦 API 编辑）。
 */
public interface EditContext {

    /** 来源：网页编辑表单（{@code POST /wiki/标题}）。 */
    String SOURCE_WEB = "web";
    /** 来源：Action API（{@code api.php?action=edit}）。 */
    String SOURCE_API = "api";
    /** 来源：站内其它写入路径（如 AI 代理确认提交）。 */
    String SOURCE_AI = "ai";

    /** 动作：编辑已有页面（{@code action=edit}）。 */
    String ACTION_EDIT = "edit";
    /** 动作：创建新页面（{@code action=create}）。 */
    String ACTION_CREATE = "create";

    /** 提交者（匿名时为 {@link Viewer#ANONYMOUS}）。 */
    Viewer viewer();

    /** 页面标题（已规范化，含命名空间）。 */
    String title();

    /** 提交时页面是否还不存在（{@code action=create}）。 */
    boolean newPage();

    /** 本次提交的正文。 */
    String content();

    /** 编辑摘要（可能为空串）。 */
    String summary();

    /** 是否标记为小编辑。 */
    boolean minor();

    /** 动作：{@link #ACTION_EDIT} / {@link #ACTION_CREATE}。 */
    String action();

    /** 提交时的基准修订号（没有 = {@code null}）。 */
    Long baseRevisionId();

    /** 表单 / API 参数（只读；网页表单是全部表单字段，API 是全部 action 参数）。 */
    Map<String, String> parameters();

    /**
     * 会话属性读写：钩子可以在这里放验证码题面 / 答案这类「一次一题」的状态。
     * 没有会话上下文时是 {@link SessionAttributes#NONE}。
     */
    SessionAttributes session();

    /** 客户端 IP（取不到为空串）。 */
    String clientIp();

    /** 客户端 User-Agent（取不到为空串）。 */
    String userAgent();

    /** 来源：{@link #SOURCE_WEB} / {@link #SOURCE_API} / {@link #SOURCE_AI}。 */
    String source();

    /**
     * 本次编辑要写入的**变更标签**（仿 MediaWiki 的 {@code ChangeTags}，如滥用过滤器的 {@code tag} 操作）。
     * <p>
     * 钩子在 {@link #addTag(String)} 里登记，core 保存版本时原样写入（逗号分隔）；
     * 默认空列表 = 没有标签，行为与迁移前一致。
     */
    default List<String> tags() {
        return List.of();
    }

    /**
     * 登记一个变更标签（去重、忽略空白）；钩子只能加标签，不能移除 core / 其它钩子登记的标签。
     * <p>
     * 默认什么都不做：不关心标签的 {@link EditContext} 实现（以及只想读上下文的测试替身）无需实现它。
     */
    default void addTag(String tag) {
        // 默认忽略
    }

    /** 是否来自网页编辑表单（等价于 {@code SOURCE_WEB.equals(source())}）。 */
    default boolean web() {
        return SOURCE_WEB.equals(source());
    }

    /** 是否来自 Action API（等价于 {@code SOURCE_API.equals(source())}）。 */
    default boolean api() {
        return SOURCE_API.equals(source());
    }
}
