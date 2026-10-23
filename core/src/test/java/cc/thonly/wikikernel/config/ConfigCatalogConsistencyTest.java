package cc.thonly.wikikernel.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置全集的「防漂移」测试：把三份东西绑在一起
 * <ol>
 *     <li>{@code spring-configuration-metadata.json}（由 @ConfigurationProperties 自动生成的真实配置面）；</li>
 *     <li>{@code config-catalog.yaml}（人工维护的配置全集目录：默认值 + 中文说明）；</li>
 *     <li>{@code static/schemas/wikikernel-config-schema.json}（提交进仓库、供 IDEA / VS Code 补全的 Schema）。</li>
 * </ol>
 * 任何一方缺键都会失败：新增配置项却忘了补目录 / 重新生成 Schema 会被立即发现。
 * 另外校验「生成的配置注释块」本身是合法 YAML 且覆盖全部配置项。
 */
class ConfigCatalogConsistencyTest {

    /** 配置全集的键数：新增 / 删除配置项时同步更新（配合下面的断言一起防漂移）。 */
    private static final int EXPECTED_CATALOG_KEY_COUNT = 124;

    /** metadata 里由 @ConfigurationProperties 暴露的键（本测试的第二方）。 */
    private static Set<String> metadataKeys() {
        try (InputStream stream = ConfigCatalogConsistencyTest.class
                .getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertNotNull(stream, "应能在 classpath 上找到 spring-configuration-metadata.json");
            // 用共享的 JSON 读取器：该文件是严格 JSON，可能含 \/ 这类 YAML 不认的转义
            Set<String> keys = ConfigCatalog.metadataKeys(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(keys.isEmpty(), "metadata 里应有配置键");
            return keys;
        } catch (IOException exception) {
            throw new IllegalStateException("读取 spring-configuration-metadata.json 失败", exception);
        }
    }

    /** 提交在仓库里的 Schema 文本（从 classpath 读，测试工作目录无关）。 */
    private static String committedSchema() {
        try (InputStream stream = ConfigCatalogConsistencyTest.class
                .getResourceAsStream(ConfigCatalog.SCHEMA_RESOURCE)) {
            assertNotNull(stream, "应能在 classpath 上找到 " + ConfigCatalog.SCHEMA_RESOURCE
                    + "（缺失时由 ConfigSchemaGenerator 生成）");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("读取配置 Schema 失败", exception);
        }
    }

    /** 断言：目录必须覆盖全部 metadata 键（否则新增配置项漏登记）。 */
    @Test
    void catalogCoversEveryMetadataProperty() {
        Set<String> metadata = metadataKeys();
        Set<String> catalog = ConfigCatalog.get().keys();
        Set<String> missing = new TreeSet<>(metadata);
        missing.removeAll(catalog);
        assertTrue(missing.isEmpty(),
                "配置目录 config-catalog.yaml 缺少这些配置项（新增 @ConfigurationProperties 字段后请补目录）："
                        + missing);
        // 反向：目录里属于 wikikernel.* 的键都应来自 metadata（wikikernel.database.* 由
        // LocalSettingsEnvironmentPostProcessor 消费，是唯一不在 metadata 里的 wikikernel 段）
        Set<String> extra = new TreeSet<>();
        for (String key : catalog) {
            if (key.startsWith("wikikernel.") && !key.startsWith("wikikernel.database.")
                    && !metadata.contains(key)) {
                extra.add(key);
            }
        }
        assertTrue(extra.isEmpty(),
                "配置目录里有 metadata 里不存在的 wikikernel.* 键（键名可能写错了）：" + extra);
    }

    /** 断言：项目自有的非 metadata 键都在目录里，且全集键数没有意外变化。 */
    @Test
    void catalogDeclaresTheNonMetadataKeys() {
        Set<String> catalog = ConfigCatalog.get().keys();
        List<String> required = List.of(
                // LocalSettingsEnvironmentPostProcessor 翻译成 spring.datasource.*
                "wikikernel.database.type", "wikikernel.database.file", "wikikernel.database.auto-ddl",
                "wikikernel.database.host", "wikikernel.database.port", "wikikernel.database.name",
                "wikikernel.database.user", "wikikernel.database.password", "wikikernel.database.pool-size",
                // SetupService / 向导会写进生成文件的 Spring 标准键
                "spring.data.redis.host", "spring.data.redis.port",
                "spring.data.redis.database", "spring.data.redis.password");
        for (String key : required) {
            assertTrue(catalog.contains(key), "配置目录应声明非 metadata 键：" + key);
        }
        assertEquals(EXPECTED_CATALOG_KEY_COUNT, catalog.size(),
                "配置全集键数发生变化：新增 / 删除配置项时请同步更新本断言并重新生成 Schema"
                        + "（当前 = " + metadataKeys().size() + " 个 @ConfigurationProperties 键 + "
                        + (catalog.size() - metadataKeys().size()) + " 个项目自有键："
                        + "wikikernel.database.* 与 spring.data.redis.*）");
    }

    /** 断言：提交在仓库里的 Schema 与现场重新生成的文本逐字节一致（跨平台忽略 CRLF 差异）。 */
    @Test
    void committedSchemaMatchesGeneratedSchema() {
        String generated = ConfigSchemaGenerator.generate();
        String committed = committedSchema();
        assertEquals(generated.replace("\r\n", "\n"), committed.replace("\r\n", "\n"),
                "提交的 Schema 与重新生成的内容不一致：请运行 ConfigSchemaGenerator 重新生成 "
                        + ConfigCatalog.SCHEMA_REPO_PATH);
    }

    /** 断言：Schema 的键集合 ⊇ metadata 键集合 ⊇ 目录键集合（IDEA 补全不会漏项）。 */
    @Test
    void schemaCoversEveryMetadataPropertyAndCatalogKey() {
        Map<String, Object> schema = new JsonReader(committedSchema()).readObject();
        assertEquals("http://json-schema.org/draft-07/schema#", schema.get("$schema"),
                "Schema 应声明 draft-07");
        Set<String> schemaKeys = flatten(schema);

        Set<String> missingFromSchema = new TreeSet<>(metadataKeys());
        missingFromSchema.removeAll(schemaKeys);
        assertTrue(missingFromSchema.isEmpty(), "Schema 缺少 metadata 里的键：" + missingFromSchema);

        Set<String> missingCatalog = new TreeSet<>(ConfigCatalog.get().keys());
        missingCatalog.removeAll(schemaKeys);
        assertTrue(missingCatalog.isEmpty(), "Schema 缺少配置目录里的键：" + missingCatalog);
    }

    /** 断言：每个 metadata 键都能在目录里找到（有 type / 说明 / 默认值）。 */
    @Test
    void everyMetadataPropertyHasCatalogEntryWithType() {
        for (String key : metadataKeys()) {
            ConfigCatalogEntry entry = ConfigCatalog.get().entry(key);
            assertNotNull(entry, "目录缺少配置项：" + key);
            assertFalse(entry.type().isBlank(), "配置项缺少 type：" + key);
            assertFalse(entry.description().isBlank(), "配置项缺少中文说明：" + key);
            if (!entry.secret()) {
                assertNotNull(entry.defaultValue(), "配置项缺少默认值：" + key);
            }
        }
    }

    /** 断言：生成的注释块是合法 YAML、覆盖全部渲染项，且敏感项不出现值。 */
    @Test
    void documentationBlockIsValidYamlAndCoversEveryKey() {
        ConfigCatalog catalog = ConfigCatalog.get();
        String documentation = catalog.renderDocumentationYaml();

        // 去掉注释行后剩下的「键骨架」必须是合法 YAML，且键集合与「会写成真实键的集合」完全一致
        // （敏感项与不写盘项只以注释列出，所以不在骨架里）
        Map<String, Object> skeleton = parseSkeleton(documentation);
        Set<String> skeletonKeys = flattenPlain(skeleton);
        Set<String> expected = new TreeSet<>(catalog.renderedKeys());
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(skeletonKeys);
        Set<String> extra = new TreeSet<>(skeletonKeys);
        extra.removeAll(expected);
        assertTrue(missing.isEmpty(), "生成的注释块缺少这些真实键：" + missing);
        assertTrue(extra.isEmpty(), "生成的注释块多出这些真实键（应只以注释列出）：" + extra);

        // 骨架里的值：会写成真实键的项必须有值且等于目录默认值
        for (ConfigCatalogEntry entry : catalog.entries()) {
            if (!expected.contains(entry.key())) {
                continue;
            }
            Object value = valueOf(skeleton, entry.key());
            assertNotNull(value, "会写成真实键的项应有值：" + entry.key());
            assertEquals(entry.defaultValue(), value,
                    "写盘项的默认值应与目录一致：" + entry.key());
        }

        // 敏感项（密码 / 密钥）必须以「注释掉的键」形式出现，且骨架里没有它的真实键
        for (ConfigCatalogEntry entry : catalog.entries()) {
            if (!entry.secret()) {
                continue;
            }
            assertFalse(expected.contains(entry.key()),
                    "敏感项不应写成真实键（会写出空密码占位）：" + entry.key());
            String ownKey = entry.key().substring(entry.key().lastIndexOf('.') + 1);
            assertTrue(documentation.contains("# " + ownKey + ": "),
                    "敏感项应以「注释掉的键」列出，方便用户直接复制填值：" + entry.key());
        }

        // 不写盘项（用户数据 / 结构复杂 / 由别处维护）：只以注释列出，绝不写成真实键，
        // 否则空列表 / 空映射会把用户自己的配置覆盖成空
        for (ConfigCatalogEntry entry : catalog.entries()) {
            if (entry.secret() || entry.writable()) {
                continue;
            }
            assertFalse(expected.contains(entry.key()),
                    "不写盘项不应写成真实键（会覆盖用户自己的配置）：" + entry.key());
            String ownKey = entry.key().substring(entry.key().lastIndexOf('.') + 1);
            assertTrue(documentation.contains("# " + ownKey + ": "),
                    "不写盘项应以「注释掉的键」列出：" + entry.key());
        }
    }

    /** 把生成文本里的非注释行拼回 YAML 并解析（用于验证骨架合法且值正确）。 */
    private static Map<String, Object> parseSkeleton(String documentation) {
        StringBuilder skeleton = new StringBuilder();
        for (String line : documentation.split("\n")) {
            if (!line.stripLeading().startsWith("#")) {
                skeleton.append(line).append('\n');
            }
        }
        Object loaded = new Yaml().load(skeleton.toString());
        assertTrue(loaded instanceof Map, "生成的注释块骨架应是合法 YAML 映射");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) loaded;
        return map;
    }

    /** 按点分键从嵌套结构里取值。 */
    private static Object valueOf(Map<String, Object> root, String key) {
        Object current = root;
        for (String segment : key.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    /** 展平普通嵌套 map（YAML 骨架用）：空 map 视为叶子，其它 map 递归下钻。 */
    @SuppressWarnings("unchecked")
    private static Set<String> flattenPlain(Map<String, Object> root) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            keys.add(entry.getKey());
            if (entry.getValue() instanceof Map<?, ?> map && !map.isEmpty()) {
                for (String child : flattenPlain((Map<String, Object>) map)) {
                    keys.add(entry.getKey() + "." + child);
                }
            }
        }
        return keys;
    }

    /** 展平 Schema：把嵌套 properties 展开成完整点分键。 */
    private static Set<String> flatten(Map<String, Object> schema) {
        Set<String> keys = new LinkedHashSet<>();
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collect(String.valueOf(entry.getKey()), entry.getValue(), keys);
            }
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static void collect(String prefix, Object node, Set<String> keys) {
        keys.add(prefix);
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        Object properties = ((Map<String, Object>) map).get("properties");
        if (!(properties instanceof Map<?, ?> children)) {
            return;
        }
        for (Map.Entry<?, ?> entry : children.entrySet()) {
            collect(prefix + "." + entry.getKey(), entry.getValue(), keys);
        }
    }

    /** 极简 JSON 读取器（避免为一条测试引入依赖）。 */
    private static final class JsonReader {
        private final String text;
        private int position;

        private JsonReader(String text) {
            this.text = text;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> readObject() {
            Object value = readValue();
            assertTrue(value instanceof Map, "顶层应是 JSON 对象");
            return (Map<String, Object>) value;
        }

        private Object readValue() {
            skipWhitespace();
            char current = text.charAt(position);
            return switch (current) {
                case '{' -> readMap();
                case '[' -> readList();
                case '"' -> readString();
                case 't' -> {
                    expect("true");
                    yield Boolean.TRUE;
                }
                case 'f' -> {
                    expect("false");
                    yield Boolean.FALSE;
                }
                case 'n' -> {
                    expect("null");
                    yield null;
                }
                default -> readNumber();
            };
        }

        private Map<String, Object> readMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            position++;
            skipWhitespace();
            if (text.charAt(position) == '}') {
                position++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                assertTrue(text.charAt(position) == ':', "JSON 对象里应是冒号");
                position++;
                map.put(key, readValue());
                skipWhitespace();
                char next = text.charAt(position++);
                if (next == '}') {
                    return map;
                }
                assertTrue(next == ',', "JSON 对象里应是逗号或右花括号");
            }
        }

        private List<Object> readList() {
            List<Object> list = new ArrayList<>();
            position++;
            skipWhitespace();
            if (text.charAt(position) == ']') {
                position++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char next = text.charAt(position++);
                if (next == ']') {
                    return list;
                }
                assertTrue(next == ',', "JSON 数组里应是逗号或右方括号");
            }
        }

        private String readString() {
            assertTrue(text.charAt(position) == '"', "JSON 字符串应以引号开始");
            position++;
            StringBuilder out = new StringBuilder();
            while (true) {
                char current = text.charAt(position++);
                if (current == '"') {
                    return out.toString();
                }
                if (current != '\\') {
                    out.append(current);
                    continue;
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> out.append(escaped);
                }
            }
        }

        private Object readNumber() {
            int start = position;
            while (position < text.length() && "-+.eE0123456789".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            String raw = text.substring(start, position);
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.valueOf(raw);
            }
            long value = Long.parseLong(raw);
            return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (Object) (int) value
                    : (Object) value;
        }

        private void expect(String literal) {
            assertTrue(text.startsWith(literal, position), "期望字面量 " + literal);
            position += literal.length();
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }
    }
}

