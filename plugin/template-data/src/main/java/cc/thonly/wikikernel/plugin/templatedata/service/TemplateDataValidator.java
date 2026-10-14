package cc.thonly.wikikernel.plugin.templatedata.service;

import cc.thonly.wikikernel.plugin.templatedata.TemplateDataMessages;
import cc.thonly.wikikernel.plugin.templatedata.model.TemplateDataStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TemplateData 校验器（移植 {@code TemplateDataValidator}，REL1_43）。
 * <p>
 * 校验规则逐条对齐原版（含 {@code isset()} 语义：值为 {@code null} 等同于没写），
 * 错误用 {@link TemplateDataStatus} 返回消息键 + 参数，本地化见 {@link TemplateDataMessages}。
 * 与原版的差异：
 * <ul>
 *     <li>{@code params} 名 / {@code paramOrder} 项的类型错误在原版会触发 PHP 的类型错误（500），
 *     这里统一返回 {@code templatedata-invalid-type}；</li>
 *     <li>原版的 MySQL gzip 长度上限（{@code templatedata-invalid-length}）不适用（不压缩存储）。</li>
 * </ul>
 */
public final class TemplateDataValidator {

    /** 内置格式字符串（{@code format} 可直接写这两个名字）。 */
    public static final Map<String, String> PREDEFINED_FORMATS = Map.of(
            "block", "{{_\n| _ = _\n}}",
            "inline", "{{_|_=_}}");

    private static final List<String> VALID_ROOT_KEYS =
            List.of("description", "params", "paramOrder", "sets", "maps", "format");

    private static final List<String> VALID_PARAM_KEYS = List.of(
            "label", "required", "suggested", "description", "example", "deprecated",
            "aliases", "autovalue", "default", "inherits", "type", "suggestedvalues");

    private static final List<String> VALID_TYPES = List.of(
            "content", "line", "number", "boolean", "string", "date", "unbalanced-wikitext",
            "unknown", "url", "wiki-page-name", "wiki-user-name", "wiki-file-name", "wiki-template-name");

    /** 自定义格式字符串（规范 3.7 的语法）。 */
    private static final Pattern CUSTOM_FORMAT =
            Pattern.compile("\n?\\{\\{ *_+\\n? *\\|\\n? *_+ *= *_+\\n? *\\}\\}\\n?");

    private final List<String> validParameterTypes;

    public TemplateDataValidator(List<String> additionalParameterTypes) {
        List<String> types = new ArrayList<>(VALID_TYPES);
        if (additionalParameterTypes != null) {
            types.addAll(additionalParameterTypes);
        }
        this.validParameterTypes = List.copyOf(types);
    }

    /** 标准校验器：额外接受 {@link TemplateDataNormalizer} 里的 4 个废弃类型（{@code string/line} 等）。 */
    public static TemplateDataValidator standard() {
        return new TemplateDataValidator(List.copyOf(TemplateDataNormalizer.DEPRECATED_PARAMETER_TYPES.keySet()));
    }

    /**
     * 校验解析出来的 JSON 根值。
     *
     * @param data {@code json_decode} 的结果（{@code LinkedHashMap} / {@code List} / 标量 / null）
     * @return 通过时携带根对象，否则是第一个错误
     */
    public TemplateDataStatus validate(Object data) {
        if (data == null) {
            return TemplateDataStatus.error("templatedata-invalid-parse");
        }
        Map<String, Object> root = Values.asMap(data);
        if (root == null) {
            return TemplateDataStatus.error("templatedata-invalid-type", "templatedata", "object");
        }

        for (String key : root.keySet()) {
            if (!VALID_ROOT_KEYS.contains(key)) {
                return TemplateDataStatus.error("templatedata-invalid-unknown", key);
            }
        }

        // 根.description
        if (Values.isset(root, "description") && !isValidInterfaceText(root.get("description"))) {
            return TemplateDataStatus.error("templatedata-invalid-type", "description", "string|object");
        }

        // 根.format
        if (Values.isset(root, "format")) {
            Object format = root.get("format");
            if (!(format instanceof String text)
                    || !(PREDEFINED_FORMATS.containsKey(text) || isValidCustomFormatString(text))) {
                return TemplateDataStatus.error("templatedata-invalid-format", "format");
            }
        }

        // 根.params
        if (!Values.isset(root, "params")) {
            return TemplateDataStatus.error("templatedata-invalid-missing", "params", "object");
        }
        Map<String, Object> params = Values.asMap(root.get("params"));
        if (params == null) {
            return TemplateDataStatus.error("templatedata-invalid-type", "params", "object");
        }

        TemplateDataStatus status = validateParameters(params);
        if (status == null) {
            status = validateParameterOrder(Values.isset(root, "paramOrder") ? root.get("paramOrder") : null, params);
        }
        if (status == null) {
            status = validateSets(Values.isset(root, "sets") ? root.get("sets") : Values.newList(), params);
        }
        if (status == null) {
            status = validateMaps(Values.isset(root, "maps") ? root.get("maps") : Values.newMap(), params);
        }
        return status == null ? TemplateDataStatus.ok(root) : status;
    }

    private TemplateDataStatus validateParameters(Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String paramName = entry.getKey();
            if (Values.trimPhp(paramName).isEmpty()) {
                return TemplateDataStatus.error("templatedata-invalid-unnamed-parameter");
            }
            Map<String, Object> param = Values.asMap(entry.getValue());
            if (param == null) {
                return TemplateDataStatus.error("templatedata-invalid-type", "params." + paramName, "object");
            }
            TemplateDataStatus status = validateParameter(paramName, param);
            if (status != null) {
                return status;
            }
            if (Values.isset(param, "inherits")) {
                String inherited = Values.phpString(param.get("inherits"));
                if (inherited == null) {
                    return TemplateDataStatus.error("templatedata-invalid-type",
                            "params." + paramName + ".inherits", "string");
                }
                if (!Values.isset(params, inherited)) {
                    return TemplateDataStatus.error("templatedata-invalid-missing", "params." + inherited);
                }
            }
        }
        return null;
    }

    private TemplateDataStatus validateParameter(String paramName, Map<String, Object> param) {
        for (String key : param.keySet()) {
            if (!VALID_PARAM_KEYS.contains(key)) {
                return TemplateDataStatus.error("templatedata-invalid-unknown",
                        "params." + paramName + "." + key);
            }
        }

        String path = "params." + paramName;

        // Param.label：字符串或接口文本对象
        if (Values.isset(param, "label") && !isValidInterfaceText(param.get("label"))) {
            return TemplateDataStatus.error("templatedata-invalid-type", path + ".label", "string|object");
        }

        // Param.required / Param.suggested：布尔
        for (String key : List.of("required", "suggested")) {
            if (Values.isset(param, key) && !(param.get(key) instanceof Boolean)) {
                return TemplateDataStatus.error("templatedata-invalid-type", path + "." + key, "boolean");
            }
        }

        // Param.description / Param.example：字符串或接口文本对象
        for (String key : List.of("description", "example")) {
            if (Values.isset(param, key) && !isValidInterfaceText(param.get(key))) {
                return TemplateDataStatus.error("templatedata-invalid-type", path + "." + key, "string|object");
            }
        }

        // Param.deprecated：布尔或字符串
        if (Values.isset(param, "deprecated")
                && !(param.get("deprecated") instanceof Boolean)
                && !(param.get("deprecated") instanceof String)) {
            return TemplateDataStatus.error("templatedata-invalid-type", path + ".deprecated", "boolean|string");
        }

        // Param.aliases：int|string 数组
        if (Values.isset(param, "aliases")) {
            List<Object> aliases = Values.asList(param.get("aliases"));
            if (aliases == null) {
                return TemplateDataStatus.error("templatedata-invalid-type", path + ".aliases", "array");
            }
            for (int i = 0; i < aliases.size(); i++) {
                Object alias = aliases.get(i);
                if (!(alias instanceof Long) && !(alias instanceof Integer) && !(alias instanceof String)) {
                    return TemplateDataStatus.error("templatedata-invalid-type", path + ".aliases[" + i + "]", "int|string");
                }
            }
        }

        // Param.autovalue：字符串
        if (Values.isset(param, "autovalue") && !(param.get("autovalue") instanceof String)) {
            return TemplateDataStatus.error("templatedata-invalid-type", path + ".autovalue", "string");
        }

        // Param.default：字符串或接口文本对象
        if (Values.isset(param, "default") && !isValidInterfaceText(param.get("default"))) {
            return TemplateDataStatus.error("templatedata-invalid-type", path + ".default", "string|object");
        }

        // Param.type：已知类型之一
        if (Values.isset(param, "type")) {
            Object type = param.get("type");
            if (!(type instanceof String text)) {
                return TemplateDataStatus.error("templatedata-invalid-type", path + ".type", "string");
            }
            if (!validParameterTypes.contains(text)) {
                return TemplateDataStatus.error("templatedata-invalid-value", path + ".type");
            }
        }

        // Param.suggestedvalues：字符串数组
        if (Values.isset(param, "suggestedvalues")) {
            List<Object> values = Values.asList(param.get("suggestedvalues"));
            if (values == null) {
                return TemplateDataStatus.error("templatedata-invalid-type", path + ".suggestedvalues", "array");
            }
            for (int i = 0; i < values.size(); i++) {
                if (!(values.get(i) instanceof String)) {
                    return TemplateDataStatus.error("templatedata-invalid-type", path + ".suggestedvalues[" + i + "]", "string");
                }
            }
        }

        return null;
    }

    private TemplateDataStatus validateParameterOrder(Object paramOrder, Map<String, Object> params) {
        if (paramOrder == null) {
            return null;
        }
        List<Object> order = Values.asList(paramOrder);
        if (order == null) {
            return TemplateDataStatus.error("templatedata-invalid-type", "paramOrder", "array");
        }
        if (order.size() < params.size()) {
            List<String> missing = new ArrayList<>();
            for (String name : params.keySet()) {
                boolean found = false;
                for (Object item : order) {
                    if (name.equals(Values.phpString(item))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    missing.add(name);
                }
            }
            return TemplateDataStatus.error("templatedata-invalid-missing",
                    "paramOrder[ \"" + String.join("\", \"", missing) + "\" ]");
        }

        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            String param = Values.phpString(order.get(i));
            if (param == null) {
                return TemplateDataStatus.error("templatedata-invalid-type", "paramOrder[" + i + "]", "string");
            }
            if (!Values.isset(params, param)) {
                return TemplateDataStatus.error("templatedata-invalid-value", "paramOrder[ \"" + param + "\" ]");
            }
            if (seen.containsKey(param)) {
                return TemplateDataStatus.error("templatedata-invalid-duplicate-value",
                        "paramOrder[" + i + "]", "paramOrder[" + seen.get(param) + "]", param);
            }
            seen.put(param, i);
        }
        return null;
    }

    private TemplateDataStatus validateSets(Object sets, Map<String, Object> params) {
        List<Object> list = Values.asList(sets);
        if (list == null) {
            return TemplateDataStatus.error("templatedata-invalid-type", "sets", "array");
        }
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> set = Values.asMap(list.get(i));
            if (set == null) {
                return TemplateDataStatus.error("templatedata-invalid-value", "sets." + i);
            }
            if (!Values.isset(set, "label")) {
                return TemplateDataStatus.error("templatedata-invalid-missing", "sets." + i + ".label", "string|object");
            }
            if (!isValidInterfaceText(set.get("label"))) {
                return TemplateDataStatus.error("templatedata-invalid-type", "sets." + i + ".label", "string|object");
            }
            if (!Values.isset(set, "params")) {
                return TemplateDataStatus.error("templatedata-invalid-missing", "sets." + i + ".params", "array");
            }
            List<Object> setParams = Values.asList(set.get("params"));
            if (setParams == null) {
                return TemplateDataStatus.error("templatedata-invalid-type", "sets." + i + ".params", "array");
            }
            if (setParams.isEmpty()) {
                return TemplateDataStatus.error("templatedata-invalid-empty-array", "sets." + i + ".params");
            }
            for (int j = 0; j < setParams.size(); j++) {
                String name = Values.phpString(setParams.get(j));
                if (name == null || !Values.isset(params, name)) {
                    return TemplateDataStatus.error("templatedata-invalid-value", "sets." + i + ".params[" + j + "]");
                }
            }
        }
        return null;
    }

    private TemplateDataStatus validateMaps(Object maps, Map<String, Object> params) {
        Map<String, Object> consumers = Values.asMap(maps);
        if (consumers == null) {
            return TemplateDataStatus.error("templatedata-invalid-type", "maps", "object");
        }
        for (Map.Entry<String, Object> consumer : consumers.entrySet()) {
            String consumerId = consumer.getKey();
            Map<String, Object> map = Values.asMap(consumer.getValue());
            if (map == null) {
                return TemplateDataStatus.error("templatedata-invalid-type", "maps." + consumerId, "object");
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                List<Object> list = Values.asList(value);
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        Object item = list.get(i);
                        List<Object> nested = Values.asList(item);
                        if (nested != null) {
                            for (int j = 0; j < nested.size(); j++) {
                                Object leaf = nested.get(j);
                                String path = "maps." + consumerId + "." + key + "[" + i + "][" + j + "]";
                                if (!(leaf instanceof String text)) {
                                    return TemplateDataStatus.error("templatedata-invalid-type", path, "string");
                                }
                                if (!Values.isset(params, text)) {
                                    return TemplateDataStatus.error("templatedata-invalid-param", text, path);
                                }
                            }
                        } else if (item instanceof String text) {
                            String path = "maps." + consumerId + "." + key + "[" + i + "]";
                            if (!Values.isset(params, text)) {
                                return TemplateDataStatus.error("templatedata-invalid-param", text, path);
                            }
                        } else {
                            return TemplateDataStatus.error("templatedata-invalid-type",
                                    "maps." + consumerId + "." + key + "[" + i + "]", "string|array");
                        }
                    }
                } else if (value instanceof String text) {
                    String path = "maps." + consumerId + "." + key;
                    if (!Values.isset(params, text)) {
                        return TemplateDataStatus.error("templatedata-invalid-param", text, path);
                    }
                } else {
                    return TemplateDataStatus.error("templatedata-invalid-type",
                            "maps." + consumerId + "." + key, "string|array");
                }
            }
        }
        return null;
    }

    /** 自定义格式字符串校验（对应原版的私有方法，测试也直接用它）。 */
    static boolean isValidCustomFormatString(String format) {
        return format != null && !format.isEmpty() && CUSTOM_FORMAT.matcher(format).matches();
    }

    /**
     * 接口文本校验：字符串，或「语言代码 → 字符串」的非空对象
     * （语言代码不能是空白、值必须是字符串；空对象不算合法）。
     */
    static boolean isValidInterfaceText(Object text) {
        Map<String, Object> object = Values.asMap(text);
        if (object != null) {
            if (object.isEmpty()) {
                return false;
            }
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                if (Values.ltrimPhp(entry.getKey()).isEmpty() || !(entry.getValue() instanceof String)) {
                    return false;
                }
            }
            return true;
        }
        return text instanceof String;
    }
}
