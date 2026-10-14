package cc.thonly.wikikernel.common.service;

import cc.thonly.wikikernel.common.data.WikiKv;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Optional;

/**
 * 一条 KV 记录（{@code wiki_kv} 的一行）：{@code (id, name, type, value)}。
 * <p>
 * 只读快照：{@code rawValue} 是数据库里原样的文本，按 {@code type} 解释；
 * 想按类型取值用 {@link #asString()} / {@link #asLong()} / {@link #asJson()}，
 * 想按名字直接取值用 {@link KvStore#getString(String, String)} 这类便捷方法。
 *
 * @param id       行 ID
 * @param name     键
 * @param type     值类型（{@link WikiKv#TYPE_STRING} / {@link WikiKv#TYPE_NUMBER} / {@link WikiKv#TYPE_JSON}）
 * @param rawValue 原始值（文本形式；允许为 null，按空串处理）
 */
public record KvValue(long id, String name, String type, String rawValue) {

    /** 解析 {@link #asJson()} 用的解析器（无状态、线程安全；core 写库时用的是同一个 Jackson 3）。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 按字符串取值（原始文本；写 JSON / 数值时拿到的是它们的文本形式，如 {@code {"a":1}}、{@code 42}）。
     * {@code rawValue} 为 null 时返回空串。
     */
    public String asString() {
        return rawValue == null ? "" : rawValue;
    }

    /**
     * 按数值取值（{@link #asString()} 解析为 {@code double}）。
     * <p>
     * 空 / 非数值（如 {@code type=json}）返回 0；想区分「没有这个键」用 {@link KvStore#number(String)}。
     */
    public double asDouble() {
        try {
            return Double.parseDouble(asString().trim());
        } catch (NumberFormatException e) {
            return 0.0d;
        }
    }

    /** 按整数取值（{@link #asDouble()} 截断；空 / 非数值返回 0）。 */
    public long asLong() {
        return (long) asDouble();
    }

    /**
     * 按 JSON 取值：{@code type=json} 时把 {@link #asString()} 解析成 Jackson 3 的 {@link JsonNode}。
     * <p>
     * 其它类型的值也会尽力转换：字符串 → 文本节点，数值 → 数值节点；解析不了返回空。
     */
    public Optional<JsonNode> asJson() {
        String raw = asString();
        if (WikiKv.TYPE_JSON.equalsIgnoreCase(type)) {
            try {
                return Optional.ofNullable(JSON.readTree(raw));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        if (WikiKv.TYPE_NUMBER.equalsIgnoreCase(type)) {
            try {
                return Optional.of(JsonNodeFactory.instance.numberNode(Double.parseDouble(raw.trim())));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.of(JsonNodeFactory.instance.stringNode(raw));
    }

    /** 按布尔取值（{@code true} / {@code 1} / {@code yes} 为真，忽略大小写与空白）。 */
    public boolean asBoolean() {
        String value = asString().trim();
        return value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes");
    }
}
