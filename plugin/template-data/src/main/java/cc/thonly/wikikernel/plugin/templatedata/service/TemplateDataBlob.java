package cc.thonly.wikikernel.plugin.templatedata.service;

import cc.thonly.wikikernel.plugin.templatedata.TemplateDataMessages;
import cc.thonly.wikikernel.plugin.templatedata.model.TemplateDataStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一段 {@code <templatedata>} 的 JSON（移植 {@code TemplateDataBlob}，REL1_43）。
 * <p>
 * 构造流程与原版一致：{@code json_decode} → {@link TemplateDataValidator 校验} →
 * （失败则退化为最小合法数据 {@code {"params":{}}}）→ {@link TemplateDataNormalizer 规范化} →
 * 得到稳定的、可直接存库的 JSON。校验失败时 {@link #status()} 里是首个错误，
 * 页面渲染显示错误框、不写页面属性。
 * <p>
 * 与原版的差异：不移植 MySQL 专用的 gzip 压缩 blob（{@code TemplateDataCompressedBlob}），
 * 页面属性存明文 JSON；规范化后的 JSON 保留非 ASCII 原文字符（PHP 会写成 {@code &#92;uXXXX}）。
 */
public final class TemplateDataBlob {

    /** 页面属性名（对应 MediaWiki 的 {@code page_props.pp_propname = templatedata}）。 */
    public static final String PROPERTY_NAME = "templatedata";

    private final String json;
    private final TemplateDataStatus status;
    private final String contentLanguage;

    /**
     * 解析并校验 / 规范化 JSON。
     *
     * @param json            {@code <templatedata>} 标签内的原始 JSON
     * @param contentLanguage 站点内容语言（接口文本的键，如 {@code zh} / {@code en}）
     * @param generateParamOrder 是否在缺少 {@code paramOrder} 时补一个（非原版行为，默认关闭）
     */
    public static TemplateDataBlob parse(String json, String contentLanguage, boolean generateParamOrder) {
        return new TemplateDataBlob(json, contentLanguage, generateParamOrder);
    }

    /** 解析（默认内容语言由 {@link TemplateDataMessages#defaultContentLanguage()} 给出）。 */
    public static TemplateDataBlob parse(String json, String contentLanguage) {
        return new TemplateDataBlob(json, contentLanguage, false);
    }

    /** 解析（内容语言跟随系统语言）。 */
    public static TemplateDataBlob parse(String json) {
        return new TemplateDataBlob(json, TemplateDataMessages.defaultContentLanguage(), false);
    }

    /**
     * 从页面属性里存的 JSON 还原（对应原版 {@code newFromDatabase}）。
     * 原版要处理 MySQL 的 gzip 头，WikiKernel 的属性表存明文，这里等同 {@link #parse}。
     */
    public static TemplateDataBlob fromDatabase(String json, String contentLanguage) {
        return new TemplateDataBlob(json, contentLanguage, false);
    }

    private TemplateDataBlob(String json, String contentLanguage, boolean generateParamOrder) {
        this.contentLanguage = normalizeLanguage(contentLanguage);
        Object parsed = Json.parseOrNull(json == null ? "" : json);
        TemplateDataStatus validated = TemplateDataValidator.standard().validate(parsed);
        this.status = validated;

        // 校验失败时退化为最小合法数据：保证后续忘记检查状态也不会存进非法数据（对齐原版）
        Map<String, Object> value = validated.isOk() ? Values.asMap(validated.value()) : null;
        if (value == null) {
            value = Values.newMap();
            value.put("params", Values.newMap());
        }
        new TemplateDataNormalizer(this.contentLanguage, generateParamOrder).normalize(value);
        this.json = Json.stringify(value);
    }

    private static String normalizeLanguage(String languageCode) {
        return languageCode == null || languageCode.isBlank()
                ? "en" : languageCode.trim().toLowerCase(Locale.ROOT);
    }

    /** 校验结果（失败时是首个错误）。 */
    public TemplateDataStatus status() {
        return status;
    }

    /** 站点内容语言。 */
    public String contentLanguage() {
        return contentLanguage;
    }

    /** 规范化后的数据（每次返回深拷贝，调用方随便改）。 */
    public Map<String, Object> getData() {
        Map<String, Object> data = Values.deepCopyMap(Values.asMap(Json.parseOrNull(json)));
        return data == null ? Values.newMap() : data;
    }

    /**
     * 把接口文本（{@code {"zh": "…", "en": "…"}}）解析成指定语言的单个字符串。
     * <p>
     * 查找顺序（对应原版的 {@code LanguageFallback::getAllIncludingSiteLanguage}）：
     * 请求语言 → 其主语言 → 站点内容语言 → 其主语言 → {@code en}；
     * 可选属性找不到合适语言时返回 {@code null}，{@code sets[].label} 回退到对象里的第一个值。
     */
    public Map<String, Object> getDataInLanguage(String languageCode) {
        Map<String, Object> data = getData();
        String language = languageCode == null || languageCode.isBlank() ? contentLanguage : languageCode.trim();

        if (data.get("description") != null) {
            data.put("description", resolveInterfaceText(data.get("description"), language));
        }

        Map<String, Object> params = Values.asMap(data.get("params"));
        if (params != null) {
            for (Object value : params.values()) {
                Map<String, Object> param = Values.asMap(value);
                if (param == null) {
                    continue;
                }
                for (String key : List.of("label", "description", "default", "example")) {
                    if (param.get(key) != null) {
                        param.put(key, resolveInterfaceText(param.get(key), language));
                    }
                }
            }
        }

        List<Object> sets = Values.asList(data.get("sets"));
        if (sets != null) {
            for (Object value : sets) {
                Map<String, Object> set = Values.asMap(value);
                if (set == null) {
                    continue;
                }
                String label = resolveInterfaceText(set.get("label"), language);
                if (label == null) {
                    // 与其它接口文本不同，set 的 label 不是可选的：找不到就退化成对象里的第一个值
                    label = firstInterfaceText(set.get("label"));
                }
                set.put("label", label);
            }
        }

        return data;
    }

    /** 存库 / 写页面属性用的 JSON 字符串。 */
    public String getJsonForDatabase() {
        return json;
    }

    /** 语言回退链（去重）：请求语言、其主语言、站点内容语言、其主语言、最后的 {@code en}。 */
    public static List<String> languageFallbackChain(String languageCode, String contentLanguage) {
        Set<String> chain = new LinkedHashSet<>();
        addLanguage(chain, languageCode);
        addLanguage(chain, contentLanguage);
        chain.add("en");
        return List.copyOf(chain);
    }

    private static void addLanguage(Set<String> chain, String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return;
        }
        String normalized = languageCode.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        chain.add(normalized);
        int dash = normalized.indexOf('-');
        if (dash > 0) {
            chain.add(normalized.substring(0, dash));
        }
    }

    private String resolveInterfaceText(Object text, String languageCode) {
        Map<String, Object> interfaceText = Values.asMap(text);
        if (interfaceText == null) {
            // 防御：规范化后一定是对象，字符串（未规范化的旧数据）原样返回
            return text instanceof String string ? string : null;
        }
        for (String candidate : languageFallbackChain(languageCode, contentLanguage)) {
            Object value = interfaceText.get(candidate);
            if (value instanceof String string) {
                return string;
            }
        }
        return null;
    }

    private static String firstInterfaceText(Object text) {
        Map<String, Object> interfaceText = Values.asMap(text);
        if (interfaceText == null) {
            return text instanceof String string ? string : null;
        }
        for (Object value : interfaceText.values()) {
            if (value instanceof String string) {
                return string;
            }
        }
        return null;
    }
}
