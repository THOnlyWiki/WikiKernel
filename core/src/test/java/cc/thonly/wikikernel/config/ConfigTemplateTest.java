package cc.thonly.wikikernel.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「完整配置模板」（{@code /init} 的「下载完整配置」）的一致性测试：
 * <ol>
 *     <li>首行是 {@code # yaml-language-server: $schema=…}；</li>
 *     <li>模板是合法 YAML（SnakeYAML 能解析）；</li>
 *     <li>键集合 = 配置目录全集（外加中间分组段），防止目录新增配置项后模板漂移；</li>
 *     <li>每一项都写出目录默认值；</li>
 *     <li>敏感项（密码 / 密钥）一律留空，且注释里点明要自己填。</li>
 * </ol>
 * 不需要 Spring 上下文：模板由配置目录（classpath 静态资源）生成，与本机配置无关。
 */
class ConfigTemplateTest {

    @Test
    void templateIsValidYamlAndCoversEveryCatalogKey() {
        ConfigCatalog catalog = ConfigCatalog.get();
        String template = catalog.renderTemplateYaml();

        // 首行 Schema 指令：IDE（IDEA Ultimate / VS Code YAML 扩展）据此补全与校验
        String firstLine = template.lines().findFirst().orElse("");
        assertTrue(firstLine.startsWith(ConfigCatalog.SCHEMA_DIRECTIVE),
                "首行应是 Schema 指令，实际：" + firstLine);
        assertTrue(firstLine.endsWith(ConfigCatalog.SCHEMA_URL_PATH),
                "Schema 指令应指向静态资源 " + ConfigCatalog.SCHEMA_URL_PATH + "，实际：" + firstLine);

        // 合法 YAML + 键集合 = 目录全集（含中间分组段）
        Map<String, Object> parsed = parse(template);
        Set<String> expected = expectedKeys(catalog);
        Set<String> actual = flatten(parsed);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        assertTrue(missing.isEmpty(), "模板缺少这些配置键：" + missing);
        assertTrue(extra.isEmpty(), "模板多出这些键（目录里没有）：" + extra);

        // 每一项都写出目录默认值（敏感项除外，见下一个用例）
        for (ConfigCatalogEntry entry : catalog.entries()) {
            if (entry.secret()) {
                continue;
            }
            assertEquals(entry.defaultValue(), valueOf(parsed, entry.key()),
                    "模板里应写出目录默认值：" + entry.key());
        }
        assertTrue(catalog.entries().size() > 100,
                "配置全集应包含全部配置项，实际 " + catalog.entries().size() + " 项");
    }

    @Test
    void templateKeepsEverySecretEmpty() {
        ConfigCatalog catalog = ConfigCatalog.get();
        String template = catalog.renderTemplateYaml();
        Map<String, Object> parsed = parse(template);

        boolean anySecret = false;
        for (ConfigCatalogEntry entry : catalog.entries()) {
            if (!entry.secret()) {
                continue;
            }
            anySecret = true;
            Object value = valueOf(parsed, entry.key());
            assertNotNull(value, "敏感项也应以「空值键」出现（用户才知道有这一项）：" + entry.key());
            assertEquals(ConfigCatalog.SECRET_PLACEHOLDER, value,
                    "敏感项必须留空，不能写任何明文：" + entry.key());
            // 写成 `<键>: ""` 便于用户直接填；注释里点明是敏感项
            String ownKey = entry.key().substring(entry.key().lastIndexOf('.') + 1);
            assertTrue(template.contains(ownKey + ": \"\""),
                    "敏感项应写成空串键（方便直接填值）：" + entry.key());
            assertTrue(template.contains("【敏感项"), "敏感项的注释里应说明要自己填：" + entry.key());
            // 兜底：万一以后给敏感项配了默认值，也不能出现在下载内容里
            String raw = String.valueOf(entry.defaultValue());
            if (!raw.isBlank()) {
                assertFalse(template.contains(raw),
                        "敏感项的默认值不应出现在模板里：" + entry.key());
            }
        }
        assertTrue(anySecret, "配置目录里应有敏感项（密码 / 密钥），否则本用例没有意义");
    }

    /** 解析模板文本（注释行由 SnakeYAML 自动忽略）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        Object loaded = new Yaml().load(text);
        assertTrue(loaded instanceof Map, "模板应是 YAML 映射");
        return (Map<String, Object>) loaded;
    }

    /** 目录全集 + 沿途的中间分组段（如 wikikernel.database.password 会带出 wikikernel、wikikernel.database）。 */
    private static Set<String> expectedKeys(ConfigCatalog catalog) {
        Set<String> keys = new LinkedHashSet<>();
        for (ConfigCatalogEntry entry : catalog.entries()) {
            StringBuilder path = new StringBuilder();
            for (String segment : entry.key().split("\\.")) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(segment);
                keys.add(path.toString());
            }
        }
        return keys;
    }

    /** 展平解析结果：空映射 / 空列表算叶子，只往映射里下钻（列表里的映射是用户数据，不是配置键）。 */
    @SuppressWarnings("unchecked")
    private static Set<String> flatten(Map<String, Object> root) {
        Set<String> keys = new LinkedHashSet<>();
        collect(root, "", keys);
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> node, String prefix, Set<String> keys) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            keys.add(path);
            if (entry.getValue() instanceof Map<?, ?> child) {
                collect((Map<String, Object>) child, path, keys);
            }
        }
    }

    /** 按点分键从嵌套结构里取值（缺失返回 null）。 */
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
}
