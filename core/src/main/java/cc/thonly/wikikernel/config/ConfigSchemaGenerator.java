package cc.thonly.wikikernel.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 由配置全集目录（{@link ConfigCatalog}）生成 JSON Schema（draft-07）。
 * <p>
 * 生成的 Schema 用于 IDEA Ultimate / VS Code 的 YAML 补全与校验：生成的
 * {@code LocalSettings.yaml} 顶部带 {@code # yaml-language-server: $schema=…} 指令，
 * YAML 插件读到后就按本 Schema 给出键补全、类型校验、默认值与中文说明。
 * <p>
 * 输出是确定性文本（固定 2 空格缩进、LF 换行、键顺序跟随目录），
 * 因此「提交的 schema 文件」可以直接与「现场重新生成的文本」做逐字节比对。
 */
public final class ConfigSchemaGenerator {

    /** Schema 的 {@code $id}（相对路径，便于仓库内引用）。 */
    public static final String SCHEMA_ID = "wikikernel-config-schema.json";

    private ConfigSchemaGenerator() {
    }

    /** 命令行重新生成提交在仓库里的 Schema。 */
    public static void main(String[] args) throws java.io.IOException {
        java.nio.file.Files.writeString(java.nio.file.Path.of(ConfigCatalog.SCHEMA_REPO_PATH), generate());
    }

    /** 生成完整 Schema 文本（以 LF 结尾）。 */
    public static String generate() {
        return generate(ConfigCatalog.get());
    }

    /** 用给定目录生成完整 Schema 文本。 */
    public static String generate(ConfigCatalog catalog) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (ConfigCatalogEntry entry : catalog.entries()) {
            putProperty(properties, entry.segments(), entry);
        }
        // 顶层是「项目自有配置」的子集：wikikernel.* 与 spring.data.redis.*。
        // 其余 spring.* / logging.* 等属于 Spring Boot 自身，交给它自带的元数据，
        // 这里显式允许（additionalProperties: true），避免把用户写别的 Spring 键判成错误。
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> child) {
                allowAdditional(child);
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", "http://json-schema.org/draft-07/schema#");
        root.put("$id", SCHEMA_ID);
        root.put("title", "WikiKernel 本地配置（LocalSettings.yaml）");
        root.put("description", "由 core/src/main/resources/config-catalog.yaml 生成；"
                + "请勿手工编辑，改配置目录后运行 ConfigCatalogConsistencyTest 重新生成。");
        root.put("type", "object");
        root.put("additionalProperties", Boolean.TRUE);
        root.put("properties", properties);

        StringBuilder out = new StringBuilder();
        write(out, root, 0);
        out.append('\n');
        return out.toString();
    }

    /** 给一个 object 节点补上 {@code additionalProperties: true}。 */
    @SuppressWarnings("unchecked")
    private static void allowAdditional(Map<?, ?> node) {
        ((Map<String, Object>) node).putIfAbsent("additionalProperties", Boolean.TRUE);
    }

    /** 把一个配置项写进 props 树（沿途创建 object 节点）。 */
    @SuppressWarnings("unchecked")
    private static void putProperty(Map<String, Object> props, List<String> segments,
                                    ConfigCatalogEntry entry) {
        Map<String, Object> current = props;
        for (int index = 0; index < segments.size() - 1; index++) {
            String segment = segments.get(index);
            Object child = current.get(segment);
            if (!(child instanceof Map)) {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("type", "object");
                created.put("additionalProperties", Boolean.TRUE);
                created.put("properties", new LinkedHashMap<String, Object>());
                current.put(segment, created);
                child = created;
            }
            Map<String, Object> node = (Map<String, Object>) child;
            Object nested = node.get("properties");
            if (!(nested instanceof Map)) {
                nested = new LinkedHashMap<String, Object>();
                node.put("properties", nested);
            }
            current = (Map<String, Object>) nested;
        }
        String leaf = segments.get(segments.size() - 1);
        Map<String, Object> descriptor = new LinkedHashMap<>();
        switch (entry.type()) {
            case "boolean" -> descriptor.put("type", "boolean");
            case "integer", "long" -> {
                descriptor.put("type", "integer");
                if ("long".equals(entry.type())) {
                    descriptor.put("format", "int64");
                }
            }
            case "list" -> {
                descriptor.put("type", "array");
                Map<String, Object> items = new LinkedHashMap<>();
                items.put("type", "string");
                descriptor.put("items", items);
            }
            case "map" -> {
                descriptor.put("type", "object");
                Map<String, Object> additional = new LinkedHashMap<>();
                additional.put("type", "string");
                descriptor.put("additionalProperties", additional);
            }
            case "object" -> {
                descriptor.put("type", "object");
                descriptor.put("additionalProperties", Boolean.TRUE);
            }
            default -> descriptor.put("type", "string");
        }
        if (!entry.enumValues().isEmpty()) {
            descriptor.put("enum", new ArrayList<>(entry.enumValues()));
        }
        if (entry.secret()) {
            // 敏感项不给出 default：避免 IDEA / VS Code 补全时插入一个空值占位符
            descriptor.put("description", entry.description().isEmpty()
                    ? "敏感项：不要在配置里写明文，建议走环境变量。"
                    : entry.description() + "（敏感项：建议走环境变量，本项不提供默认值）");
            descriptor.put("writeOnly", Boolean.TRUE);
        } else {
            if (entry.defaultValue() != null && !"list".equals(entry.type())
                    && !"map".equals(entry.type()) && !"object".equals(entry.type())) {
                descriptor.put("default", entry.defaultValue());
            }
            if (!entry.description().isEmpty()) {
                descriptor.put("description", entry.description());
            }
        }
        current.put(leaf, descriptor);
    }

    /** 手写 JSON 序列化（不引第三方依赖；输出格式固定，便于逐字节比对）。 */
    private static void write(StringBuilder out, Object value, int indent) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(out, indent + 1);
                writeString(out, String.valueOf(entry.getKey()));
                out.append(": ");
                write(out, entry.getValue(), indent + 1);
                out.append(++index < map.size() ? ",\n" : "\n");
            }
            indent(out, indent);
            out.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int index = 0; index < list.size(); index++) {
                indent(out, indent + 1);
                write(out, list.get(index), indent + 1);
                out.append(index + 1 < list.size() ? ",\n" : "\n");
            }
            indent(out, indent);
            out.append(']');
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
            return;
        }
        writeString(out, String.valueOf(value));
    }

    private static void indent(StringBuilder out, int level) {
        out.append("  ".repeat(level));
    }

    /** JSON 字符串转义（含非 ASCII 原样输出，保持中文可读）。 */
    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            switch (current) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (current < 0x20) {
                        out.append(String.format("\\u%04x", (int) current));
                    } else {
                        out.append(current);
                    }
                }
            }
        }
        out.append('"');
    }
}
