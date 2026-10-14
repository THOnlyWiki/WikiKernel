package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：**注册页表单字段**（仿 MediaWiki 的 {@code AuthChangeFormFields} 钩子）。
 * <p>
 * core 在注册表单里（电子邮件之后、提交按钮之前）按 {@link #order()} 升序插入
 * {@link #html(RegistrationContext)} 返回的 HTML 片段（例如注册问答的题目 + 隐藏下标 + 答案输入框）。
 * 片段里的字段随注册表单一起提交（{@code /js/auth.js} 用 {@code FormData} 全量提交，插件无需自带脚本），
 * 注册前钩子（{@link RegistrationGuard}）能从 {@link RegistrationContext#parameters()} 拿到它们。
 * <p>
 * 约定：返回 {@code null} / 空串表示这次不显示（例如功能关闭、题库为空）；抛异常时 core 记警告并跳过。
 */
public interface RegistrationFormField extends Extension {

    /** 执行顺序，值越小越先插入。 */
    default int order() {
        return 0;
    }

    /** 注册表单里的 HTML 片段（core 用 {@code th:utext} 原样插入）。 */
    String html(RegistrationContext context);
}
