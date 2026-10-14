package cc.thonly.wikikernel.common.extension;

import java.util.Map;

/**
 * 一次编辑表单（{@code ?action=edit}）渲染的上下文，供 {@link EditFormField} 使用。
 * <p>
 * 与 {@link EditContext} 的区别：这里只是**渲染表单**（还没有提交），所以没有正文 / 摘要，
 * 多了 {@link #preview()}（是否预览请求）。字段里要用到的验证码题面这类状态放 {@link #session()}。
 */
public interface EditFormContext {

    /** 页面标题（含命名空间）。 */
    String title();

    /** 页面是否还不存在（新建）。 */
    boolean newPage();

    /** 当前用户（匿名时为 {@link Viewer#ANONYMOUS}）。 */
    Viewer viewer();

    /** 会话属性读写（验证码题面 / 答案等）。 */
    SessionAttributes session();

    /** 请求参数（查询串 / 表单字段，只读）。 */
    Map<String, String> parameters();

    /** 是否预览请求（{@code ?action=submit} 之类的预览分支；普通编辑页为 false）。 */
    boolean preview();
}
