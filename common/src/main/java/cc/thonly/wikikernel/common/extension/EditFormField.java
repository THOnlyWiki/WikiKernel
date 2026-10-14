package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：**编辑页表单字段**（仿 MediaWiki 的 {@code EditPage::showEditForm:fields} 钩子）。
 * <p>
 * core 在编辑框下方（摘要栏之后、保存按钮之前）按 {@link #order()} 升序插入 {@link #html(EditFormContext)}
 * 返回的 HTML 片段（例如验证码的题目 + 输入框），并在保存按钮附近显示 {@link #notice(EditFormContext)}。
 * 片段里的表单字段随编辑表单一起提交，保存前钩子（{@link EditGuard}）能通过
 * {@link EditContext#parameters()} 拿到它们。
 * <p>
 * 约定：返回 {@code null} / 空串表示这次不显示（例如功能关闭、用户命中豁免组）；抛异常时 core
 * 记警告并跳过该字段（编辑页照常可用）。
 */
public interface EditFormField extends Extension {

    /** 执行顺序，值越小越先插入。 */
    default int order() {
        return 0;
    }

    /** 编辑框下方的 HTML 片段（可以是多个字段；core 用 {@code th:utext} 原样插入）。 */
    String html(EditFormContext context);

    /** 保存按钮附近的提示（如「保存后进入审核队列」）；默认不显示。 */
    default String notice(EditFormContext context) {
        return null;
    }
}
