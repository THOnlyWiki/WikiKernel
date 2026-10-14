package cc.thonly.wikikernel.common.extension;

import java.util.List;

/**
 * 安装向导里的一个表单字段（由插件通过 {@link SetupStep#fields()} 声明，core 负责渲染）。
 *
 * @param key          提交时的参数名
 * @param label        显示标签
 * @param type         {@code text} / {@code password} / {@code checkbox} / {@code select}
 * @param defaultValue 默认值（checkbox 用 {@code "true"} / {@code "false"}）
 * @param options      {@code select} 的选项
 */
public record SetupField(String key, String label, String type, String defaultValue, List<String> options) {

    public static SetupField text(String key, String label, String defaultValue) {
        return new SetupField(key, label, "text", defaultValue, List.of());
    }

    public static SetupField password(String key, String label, String defaultValue) {
        return new SetupField(key, label, "password", defaultValue, List.of());
    }

    public static SetupField checkbox(String key, String label, boolean defaultValue) {
        return new SetupField(key, label, "checkbox", Boolean.toString(defaultValue), List.of());
    }

    public static SetupField select(String key, String label, String defaultValue, List<String> options) {
        return new SetupField(key, label, "select", defaultValue, List.copyOf(options));
    }

    /** 多行文本（{@code textarea}）：向导里需要换行分隔的列表（问答题目 / 图片池…）时用它。 */
    public static SetupField textArea(String key, String label, String defaultValue) {
        return new SetupField(key, label, "textarea", defaultValue, List.of());
    }
}
