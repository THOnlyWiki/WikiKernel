package cc.thonly.wikikernel.plugin.templatedata.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TemplateData 规范化器（移植 {@code TemplateDataNormalizer}，REL1_43）。
 * <p>
 * 规范化只做四件事（与原版一一对应）：
 * <ol>
 *     <li>补齐根与参数的可选键（{@code description} / {@code sets} / {@code maps} / {@code format} /
 *     {@code params}，以及每个参数的 {@code label} / {@code description} / {@code required} /
 *     {@code suggested} / {@code deprecated} / {@code aliases} / {@code type} / {@code autovalue} /
 *     {@code default} / {@code suggestedvalues} / {@code example}）；</li>
 *     <li>{@code inherits}：把父参数中本参数没有的键复制过来（本参数已有的键优先），然后删掉 {@code inherits}；</li>
 *     <li>接口文本：纯字符串转成 {@code {"<内容语言>": "…"}} 对象，多语言对象原样保留；</li>
 *     <li>{@code aliases} 里的整数转字符串；{@code string/line} 等废弃类型映射成新类型。</li>
 * </ol>
 * 说明：原版<b>不会</b>给参数名做 trim、<b>不会</b>把 aliases 合并成独立参数、也<b>不会</b>生成
 * {@code paramOrder}（{@code paramOrder} 只在用户写了时保留并校验，渲染时缺省按参数出现顺序回退）。
 * 本移植以 REL1_43 源码为准；如需规范化时补 {@code paramOrder}，可打开
 * {@code generate-param-order} 开关（非原版行为，见 README）。
 */
public final class TemplateDataNormalizer {

    /** 废弃参数类型 → 现行类型（原版 {@code DEPRECATED_PARAMETER_TYPES}）。 */
    public static final Map<String, String> DEPRECATED_PARAMETER_TYPES = Map.of(
            "string/line", "line",
            "string/wiki-page-name", "wiki-page-name",
            "string/wiki-user-name", "wiki-user-name",
            "string/wiki-file-name", "wiki-file-name");

    private final String contentLanguageCode;
    private final boolean generateParamOrder;

    public TemplateDataNormalizer(String contentLanguageCode) {
        this(contentLanguageCode, false);
    }

    /**
     * @param contentLanguageCode 站点内容语言（接口文本的键）
     * @param generateParamOrder 是否在缺少 {@code paramOrder} 时按参数顺序补一个（原版没有的行为，默认关闭）
     */
    public TemplateDataNormalizer(String contentLanguageCode, boolean generateParamOrder) {
        this.contentLanguageCode = contentLanguageCode == null || contentLanguageCode.isBlank()
                ? "en" : contentLanguageCode;
        this.generateParamOrder = generateParamOrder;
    }

    /** 原地规范化（调用前必须已经通过 {@link TemplateDataValidator}）。 */
    public void normalize(Map<String, Object> data) {
        Values.putIfAbsent(data, "description", null);
        Values.putIfAbsent(data, "sets", Values.newList());
        Values.putIfAbsent(data, "maps", Values.newMap());
        Values.putIfAbsent(data, "format", null);
        Values.putIfAbsent(data, "params", Values.newMap());

        normalizeInterfaceText(data, "description");
        List<Object> sets = Values.asList(data.get("sets"));
        if (sets != null) {
            for (Object set : sets) {
                Map<String, Object> setObject = Values.asMap(set);
                if (setObject != null) {
                    normalizeInterfaceText(setObject, "label");
                }
            }
        }

        Map<String, Object> params = Values.asMap(data.get("params"));
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                Map<String, Object> param = Values.asMap(entry.getValue());
                if (param == null) {
                    continue;
                }
                applyInheritance(params, param);
                normalizeParameter(param);
            }
            if (generateParamOrder && !Values.isset(data, "paramOrder") && !params.isEmpty()) {
                // 非原版行为（默认关闭）：按参数出现顺序补一个 paramOrder
                data.put("paramOrder", Values.newList());
                List<Object> order = Values.asList(data.get("paramOrder"));
                if (order != null) {
                    order.addAll(params.keySet());
                }
            }
        }
    }

    /** {@code inherits}：复制父参数里本参数没有的键（对象 / 数组深拷贝），再删掉 {@code inherits}。 */
    private void applyInheritance(Map<String, Object> params, Map<String, Object> param) {
        if (!Values.isset(param, "inherits")) {
            return;
        }
        String parentName = Values.phpString(param.get("inherits"));
        Map<String, Object> parent = parentName == null ? null : Values.asMap(params.get(parentName));
        if (parent == null) {
            return;
        }
        // 复制一份键表：父子是同一个对象（自继承）时也要能安全遍历
        for (Map.Entry<String, Object> inherited : new ArrayList<>(parent.entrySet())) {
            if (!Values.isset(param, inherited.getKey())) {
                param.put(inherited.getKey(), Values.deepCopy(inherited.getValue()));
            }
        }
        param.remove("inherits");
    }

    /** 单个参数的默认值补齐（顺序与原版一致）。 */
    private void normalizeParameter(Map<String, Object> param) {
        Values.putIfAbsent(param, "label", null);
        Values.putIfAbsent(param, "description", null);
        Values.putIfAbsent(param, "required", Boolean.FALSE);
        Values.putIfAbsent(param, "suggested", Boolean.FALSE);
        Values.putIfAbsent(param, "deprecated", Boolean.FALSE);
        Values.putIfAbsent(param, "aliases", Values.newList());
        Values.putIfAbsent(param, "type", "unknown");
        Values.putIfAbsent(param, "autovalue", null);
        Values.putIfAbsent(param, "default", null);
        Values.putIfAbsent(param, "suggestedvalues", Values.newList());
        Values.putIfAbsent(param, "example", null);

        for (String key : List.of("label", "description", "default", "example")) {
            normalizeInterfaceText(param, key);
        }

        List<Object> aliases = Values.asList(param.get("aliases"));
        if (aliases != null) {
            for (int i = 0; i < aliases.size(); i++) {
                Object alias = aliases.get(i);
                String text = alias instanceof Long || alias instanceof Integer ? String.valueOf(alias) : null;
                if (text != null) {
                    aliases.set(i, text);
                }
            }
        }

        // 废弃类型映射成新类型
        Object type = param.get("type");
        if (type instanceof String text) {
            String mapped = DEPRECATED_PARAMETER_TYPES.get(text);
            if (mapped != null) {
                param.put("type", mapped);
            }
        }
    }

    /** 接口文本：纯字符串 → {@code {"<内容语言>": 字符串}}。 */
    private void normalizeInterfaceText(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value instanceof String text) {
            Map<String, Object> interfaceText = Values.newMap();
            interfaceText.put(contentLanguageCode, text);
            object.put(key, interfaceText);
        }
    }
}
