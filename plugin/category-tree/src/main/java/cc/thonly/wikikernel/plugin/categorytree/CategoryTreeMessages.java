package cc.thonly.wikikernel.plugin.categorytree;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeHtml;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 插件消息：分类树的界面文案（挑自原扩展 {@code i18n/} 的关键条目，中文为主、英文兜底）。
 * <p>
 * 文案在插件自己的资源文件里（不再写成 Java 里的 {@code Map} 字面量）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 本类同时是注册给 core 的 {@code MessageBundle}，所以 {@code plugin.category-tree.*}、
 * {@code categorytree*}、{@code setup.category-tree.*} 这些键在 core 的消息源里也能查到。
 * <p>
 * 与 MediaWiki 的差异：消息里的 {@code $1}{@code $2} 占位符与 MediaWiki 写法保持一致，方便对照原版翻译；
 * 界面文案按 JVM 默认语言选中文 / 英文（与迁移前一致），缺键时先回落另一种语言、再显示键名。
 */
public final class CategoryTreeMessages extends ResourceMessageBundle {

    /** 中文表的语言标签（与 {@link ResourceMessageBundle#DEFAULT_LANGUAGE} 一致）。 */
    private static final String CHINESE = DEFAULT_LANGUAGE;

    private final Locale locale;
    private final Map<String, String> table;
    private final Map<String, String> fallback;

    /** 默认构造：按 JVM 默认语言（{@code context.register(MessageBundle.class, new CategoryTreeMessages())} 用）。 */
    public CategoryTreeMessages() {
        this(Locale.getDefault());
    }

    public CategoryTreeMessages(Locale locale) {
        this.locale = locale == null ? Locale.getDefault() : locale;
        Map<String, Map<String, String>> all = messages();
        this.table = all.getOrDefault(isChinese() ? CHINESE : "en", Map.of());
        this.fallback = all.getOrDefault(isChinese() ? "en" : CHINESE, Map.of());
    }

    /** 按 JVM 默认语言选中文 / 英文。 */
    public static CategoryTreeMessages system() {
        return new CategoryTreeMessages(Locale.getDefault());
    }

    public Locale locale() {
        return this.locale;
    }

    /**
     * 取消息并把参数当作纯文本转义后代入（消息文本本身不含 HTML，除 {@code $} 占位符外原样输出）。
     */
    public String text(String key, Object... params) {
        String message = raw(key);
        for (int i = 0; i < params.length; i++) {
            message = message.replace("$" + (i + 1), CategoryTreeHtml.escapeText(String.valueOf(params[i])));
        }
        return message;
    }

    /** 取消息但不转义参数（内部拼接用，调用方负责最终转义）。 */
    public String textRaw(String key, Object... params) {
        String message = raw(key);
        for (int i = 0; i < params.length; i++) {
            message = message.replace("$" + (i + 1), String.valueOf(params[i]));
        }
        return message;
    }

    /** 消息是否存在（未定义时 {@link #text} 返回键名，便于发现漏翻）。 */
    public boolean has(String key) {
        return table.containsKey(key) || fallback.containsKey(key);
    }

    /** 列表连接符（对应 MediaWiki 的 {@code commaList}）：中文用「、」，英文用「, 」。 */
    public String joinList(List<String> items) {
        String separator = isChinese() ? "、" : ", ";
        return String.join(separator, items);
    }

    /** 「 | 」分隔符（对应 MediaWiki 的 {@code pipe-separator}）。 */
    public String pipeSeparator() {
        return " | ";
    }

    /** 供前端 {@code window.CategoryTreeMessages} 使用的 JSON（键与 JS 里的常量一一对应）。 */
    public String json() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("expand", raw("categorytree-expand"));
        values.put("collapse", raw("categorytree-collapse"));
        values.put("loading", raw("categorytree-loading"));
        values.put("error", raw("categorytree-error"));
        values.put("retry", raw("categorytree-retry"));
        values.put("no-subcategories", raw("categorytree-no-subcategories"));
        values.put("no-pages", raw("categorytree-no-pages"));
        values.put("no-parent-categories", raw("categorytree-no-parent-categories"));
        values.put("nothing-found", raw("categorytree-nothing-found"));
        return CategoryTreeJson.stringify(values);
    }

    private boolean isChinese() {
        return "zh".equalsIgnoreCase(this.locale.getLanguage());
    }

    private String raw(String key) {
        String value = table.get(key);
        if (value != null) {
            return value;
        }
        value = fallback.get(key);
        return value == null ? key : value;
    }
}
