package cc.thonly.wikikernel.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置全集目录（配置项的单一事实来源）：读取 {@code core/src/main/resources/config-catalog.yaml}。
 * <p>
 * 三个用途：
 * <ol>
 *     <li>{@code /init} 安装向导按它生成「全字段 + 默认值 + 中文注释」的 {@code LocalSettings.yaml}
 *     （见 {@link #defaultConfig()} 与 {@link #renderDocumentationYaml()}）；</li>
 *     <li>据它生成 JSON Schema（见 {@link ConfigSchemaGenerator}），供 IDEA / VS Code 的 YAML 补全；</li>
 *     <li>一致性测试用它比对 {@code spring-configuration-metadata.json}，新增配置项漏同步会被测试抓住。</li>
 * </ol>
 * 目录文件缺失或损坏时退化为空目录（不影响程序启动，只是生成的配置文件不含注释）。
 */
public final class ConfigCatalog {

    /** 目录资源的 classpath 路径。 */
    public static final String RESOURCE = "/config-catalog.yaml";

    /** 生成的 JSON Schema 资源路径（同时会被复制到配置目录旁的 {@link #SCHEMA_RUNTIME_PATH}）。 */
    public static final String SCHEMA_RESOURCE = "/static/schemas/wikikernel-config-schema.json";

    /** 生成的 JSON Schema 在仓库里的规范位置（相对仓库根；/init 生成的文件在仓库根时能直接解析）。 */
    public static final String SCHEMA_REPO_PATH =
            "core/src/main/resources/static/schemas/wikikernel-config-schema.json";

    /** 运行时把 Schema 复制到配置目录下的相对路径（保证 {@code $schema} 在任意部署目录都能解析）。 */
    public static final String SCHEMA_RUNTIME_PATH = "data/schemas/wikikernel-config-schema.json";

    /** YAML 语言服务器指令：IDEA Ultimate / VS Code 的 YAML 插件据此补全与校验。 */
    public static final String SCHEMA_DIRECTIVE = "# yaml-language-server: $schema=";

    /** Schema 的静态资源访问路径（Spring Boot 默认把 {@code static/} 挂在根路径下）。 */
    public static final String SCHEMA_URL_PATH = "/schemas/wikikernel-config-schema.json";

    /**
     * 完整配置模板里敏感项（密码 / 密钥）的值：空串。
     * <p>
     * 模板是从配置目录生成的静态文本，本来就不读本机的真实配置；这里再显式留空，
     * 保证「即使以后有人给敏感项写了默认值」也不会把密码 / 密钥写进下载的文件。
     */
    public static final String SECRET_PLACEHOLDER = "";

    /** 完整配置模板（{@code GET /api/setup/config-template}）的文件头说明。 */
    public static final String TEMPLATE_HEADER = """
            ============================================================
            WikiKernel 完整配置模板（由 /init 的「下载完整配置」生成）
            列出全部配置项 + 默认值 + 中文说明；可另存为 LocalSettings.yaml 后按需增删。
            优先级：命令行参数 > 环境变量 > LocalSettings.yaml > 内置默认。
            敏感项（密码 / 密钥）一律留空，请自行填写或改用环境变量；注释掉某行 = 该项不写盘。
            ============================================================""";

    /** 生成的 LocalSettings.yaml 的文件头（{@code $schema} 指令行由调用方放在最前面）。 */
    public static final String HEADER_LINES = """
            # ============================================================
            # WikiKernel 本地配置（类似 MediaWiki 的 LocalSettings.php）
            #
            # 本文件由 /init 安装向导生成：列出的每一项都带默认值与中文说明，
            # 你可以直接编辑；改动后重启生效（部分项在「特殊:权限配置」里可即时修改）。
            # 注意：本文件里「未注释」的值会在每次启动时覆盖数据库里的对应站点设置。
            #
            # 优先级：命令行参数 > 环境变量 > LocalSettings.yaml > 内置默认
            # ============================================================""";

    /** 生成的 JSON Schema 里说明「本段是 Spring Boot 自身配置、可自由扩展」时的提示。 */
    public static final String SPRING_SECTION_HINT =
            "Spring Boot 自身的配置（如 spring.datasource.*、logging.*）："
                    + "本项目只用到这里列出的 Redis 键，其余照 Spring Boot 官方文档写即可。";

    private static final ConfigCatalog INSTANCE = new ConfigCatalog(loadResource());

    /** 配置项（按目录文件里的顺序）。 */
    private final List<ConfigCatalogEntry> entries;

    /** 配置项按完整键索引。 */
    private final Map<String, ConfigCatalogEntry> byKey;

    /** 分组注释（前缀 → 该组的中文说明）。 */
    private final Map<String, String> groupComments;

    /** 分组顺序（前缀，按目录文件里的顺序）。 */
    private final List<String> groupOrder;

    private ConfigCatalog(Document document) {
        this.entries = List.copyOf(document.entries());
        Map<String, ConfigCatalogEntry> index = new LinkedHashMap<>();
        for (ConfigCatalogEntry entry : this.entries) {
            index.put(entry.key(), entry);
        }
        this.byKey = Collections.unmodifiableMap(index);
        this.groupComments = Collections.unmodifiableMap(document.groupComments());
        this.groupOrder = List.copyOf(document.groupOrder());
    }

    /** 全局目录实例。 */
    public static ConfigCatalog get() {
        return INSTANCE;
    }

    /** 全部配置项（按目录顺序）。 */
    public List<ConfigCatalogEntry> entries() {
        return entries;
    }

    /** 全部配置键（含敏感项与不写盘项）。 */
    public Set<String> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byKey.keySet()));
    }

    /** 按键取配置项（不存在返回 null）。 */
    public ConfigCatalogEntry entry(String key) {
        return byKey.get(key);
    }

    /** 按前缀取配置项（保持目录顺序）。 */
    public List<ConfigCatalogEntry> entriesOf(String prefix) {
        List<ConfigCatalogEntry> result = new ArrayList<>();
        for (ConfigCatalogEntry entry : entries) {
            if (entry.belongsTo(prefix)) {
                result.add(entry);
            }
        }
        return result;
    }

    /** 分组顺序（前缀）。 */
    public List<String> groupOrder() {
        return groupOrder;
    }

    /** 某分组的中文说明（无则空串）。 */
    public String groupComment(String prefix) {
        return groupComments.getOrDefault(prefix, "");
    }

    /**
     * 「写盘项」的默认值嵌套结构（{@code wikikernel.*} / {@code spring.*} 分段展开）。
     * 只包含 {@code write != false} 且非敏感的项。
     */
    public Map<String, Object> defaultConfig() {
        Map<String, Object> root = new LinkedHashMap<>();
        for (ConfigCatalogEntry entry : entries) {
            if (!entry.writable() || entry.secret()) {
                continue;
            }
            putPath(root, entry.segments(), entry.defaultValue());
        }
        return root;
    }

    /** 把一段路径写进嵌套 map（同名前缀复用已建的子 map）。 */
    @SuppressWarnings("unchecked")
    private static void putPath(Map<String, Object> root, List<String> segments, Object value) {
        Map<String, Object> current = root;
        for (int index = 0; index < segments.size() - 1; index++) {
            Object child = current.get(segments.get(index));
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                current.put(segments.get(index), child);
            }
            current = (Map<String, Object>) child;
        }
        current.put(segments.get(segments.size() - 1), value);
    }

    /**
     * 生成带完整注释的配置文档（用目录默认值）。
     */
    public String renderDocumentationYaml() {
        return renderDocumentationYaml(Map.of());
    }

    /**
     * 生成带完整注释的配置文件：每个配置项输出「一段中文注释 + 键: 值」，按目录顺序分组。
     * <p>
     * {@code config} 是需要写进文件的实际配置（向导提交的值、已有配置、插件写入的值…）。
     * 每一项按下面的规则决定怎么写：
     * <ol>
     *     <li>配置里有这个键 → 写出它的值（含嵌套映射 / 列表）；</li>
     *     <li>配置里没有，且是敏感项（密码 / 密钥）→ 只写注释与「注释掉的键」，绝不落明文也不写空值；</li>
     *     <li>配置里没有，且目录标记为不主动写入（{@code write: false}：用户数据、结构复杂、
     *     由别处维护）→ 只写注释与「注释掉的键」，避免空值把用户自己的配置覆盖掉；</li>
     *     <li>其余 → 写出目录里的默认值（这样「没有配置」时也能看到全部可配置项）。</li>
     * </ol>
     * 输出是单个嵌套文档、顺序稳定，并且可被 SnakeYAML 重新解析。
     */
    public String renderDocumentationYaml(Map<String, Object> config) {
        Map<String, Node> roots = buildRenderTree();
        // 配置里存在但目录没登记的键（插件写入的额外配置等）也要落盘
        absorbExtras(roots, config, "");
        StringBuilder out = new StringBuilder();
        for (Node root : roots.values()) {
            writeNode(out, root, "", true, out.length() == 0);
        }
        return out.toString();
    }

    /**
     * 生成「完整配置模板」（/init 页面的「下载完整配置」）：
     * 目录里的<b>每一项</b>都写成真实键 + 中文说明 + 默认值，按分组前缀排布。
     * <p>
     * 与 {@link #renderDocumentationYaml()} 的区别（模板是给人照着改的，不是本机生效的配置）：
     * <ol>
     *     <li>敏感项（{@code secret: true}）也写成真实键，但值固定为 {@link #SECRET_PLACEHOLDER}
     *     —— 空串，绝不写明文；</li>
     *     <li>目录标记 {@code write: false} 的项（用户数据 / 结构复杂 / 由别处维护）同样写出真实键
     *     + 默认值，方便用户按需打开；</li>
     *     <li>只读配置目录（classpath 资源）与静态常量，<b>不读取本机现有配置</b>，
     *     因此不会把真实密码 / 密钥带进下载的文件。</li>
     * </ol>
     *
     * @param schemaUrl 写进首行 {@code # yaml-language-server: $schema=} 的 Schema 地址
     * @return 完整模板文本（合法 YAML：注释 + 嵌套映射，LF 结尾）
     */
    public String renderTemplateYaml(String schemaUrl) {
        StringBuilder out = new StringBuilder();
        out.append(SCHEMA_DIRECTIVE)
                .append(schemaUrl == null || schemaUrl.isBlank() ? SCHEMA_URL_PATH : schemaUrl.trim())
                .append('\n');
        appendComment(out, TEMPLATE_HEADER);
        out.append('\n');
        out.append(renderDocumentationYaml(templateConfig()));
        return out.toString();
    }

    /** 完整配置模板（{@code $schema} 用相对路径，供测试与离线场景）。 */
    public String renderTemplateYaml() {
        return renderTemplateYaml(SCHEMA_URL_PATH);
    }

    /**
     * 生成「精简配置」的 YAML：只输出 {@code config} 里真实存在的键，
     * 但保留配置目录里的中文分组注释与嵌套层级，全部使用块状写法
     * （不再出现 {@code database: { type: sqlite, … }} 这类流式映射）。
     * <p>
     * 与 {@link #renderDocumentationYaml(Map)} 的区别：不把没配置过的默认项写进文件，
     * 文件仍然只包含「向导里实际配置过的项」；但每段带说明、按层级缩进，手工修改更直观。
     */
    public String renderConfigYaml(Map<String, Object> config) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (!first) {
                out.append('\n');
            }
            writeConfigNode(out, key, entry.getValue(), key, "", true);
            first = false;
        }
        return out.toString();
    }

    /**
     * 输出精简配置的一个节点：映射递归展开成块状，列表 / 标量交给 {@link #writeValue}。
     * 只在「分组映射」上输出目录注释，叶子键不逐行附默认值说明，保持文件精简可读。
     */
    private void writeConfigNode(StringBuilder out, String key, Object value, String path,
                                 String padding, boolean topLevel) {
        if (value instanceof Map<?, ?> map) {
            String comment = groupComment(path);
            if (comment == null || comment.isBlank()) {
                ConfigCatalogEntry entry = byKey.get(path);
                if (entry != null) {
                    comment = entry.commentText(null);
                }
            }
            if (comment != null && !comment.isBlank()) {
                appendComment(out, comment, padding);
            }
            if (map.isEmpty()) {
                out.append(padding).append(key).append(": {}\n");
                return;
            }
            out.append(padding).append(key).append(":\n");
            boolean firstChild = true;
            for (Map.Entry<?, ?> child : map.entrySet()) {
                String childKey = String.valueOf(child.getKey());
                // 分组之间空一行，读起来层次更清楚；标量叶子保持紧凑
                if (!firstChild && child.getValue() instanceof Map<?, ?>) {
                    out.append('\n');
                }
                writeConfigNode(out, childKey, child.getValue(), path + "." + childKey,
                        padding + "  ", false);
                firstChild = false;
            }
            return;
        }
        writeValue(out, padding, key, value, 1);
    }

    /**
     * 完整模板用的「配置」：每一项都给值 —— 敏感项留空，其余用目录默认值
     * （目录没写默认值时按类型给出空值，保证模板里不缺键）。
     */
    private Map<String, Object> templateConfig() {
        Map<String, Object> template = new LinkedHashMap<>();
        for (ConfigCatalogEntry entry : entries) {
            Object value;
            if (entry.secret()) {
                value = SECRET_PLACEHOLDER;
            } else {
                value = entry.defaultValue() == null ? fallbackValueFor(entry) : entry.defaultValue();
            }
            putPath(template, entry.segments(), value);
        }
        return template;
    }

    /** 目录没给默认值时的兜底空值（按类型给，保证模板里的键都有合法 YAML 值）。 */
    private static Object fallbackValueFor(ConfigCatalogEntry entry) {
        return switch (entry.type()) {
            case "list" -> new ArrayList<>();
            case "map", "object" -> new LinkedHashMap<String, Object>();
            case "boolean" -> Boolean.FALSE;
            case "integer", "long" -> 0;
            default -> "";
        };
    }

    /**
     * 实际会被写成「真实 YAML 键」的完整键集合（不含只以注释列出的敏感项 / 不写盘项）。
     * 一致性测试用本方法核对生成结果。
     */
    public Set<String> renderedKeys() {
        return renderedKeys(Map.of());
    }

    /** 给定配置时会被写成真实 YAML 键的完整键集合。 */
    public Set<String> renderedKeys(Map<String, Object> config) {
        Map<String, Node> roots = buildRenderTree();
        absorbExtras(roots, config, "");
        Set<String> keys = new LinkedHashSet<>();
        for (Node root : roots.values()) {
            collectDataKeys(root, keys);
        }
        return Collections.unmodifiableSet(keys);
    }

    /**
     * 收集「会写成真实 YAML 键」的节点；返回值表示该节点自身是否是一个被写出的键。
     * <p>
     * 与 {@link #writeNode} 的判断保持一致：分组段只有在真正写出 {@code key:} 时才算
     * （敏感项子节点全是注释、自己又没值的段会被整段略去）。
     */
    private static boolean collectDataKeys(Node node, Set<String> keys) {
        boolean written = writtenAsKey(node);
        if (written) {
            keys.add(node.path);
        }
        for (Node child : node.children.values()) {
            collectDataKeys(child, keys);
        }
        return written;
    }

    /**
     * 该节点自身是否会被写成真实的 YAML 键（与 {@link #writeNode} 的判断一一对应）。
     */
    private static boolean writtenAsKey(Node node) {
        if (node.value != null) {
            // 有值：标量一定写；映射只有在「不是配置项的容器 / 是目录外的键」时才写成块
            return !(node.value instanceof Map<?, ?>) || node.entry == null || node.extra;
        }
        if (node.children.isEmpty()) {
            // 叶子：只有普通目录项会写默认值（敏感项 / 不写盘项只剩注释）
            return node.entry != null && node.entry.writable() && !node.entry.secret();
        }
        // 分组 / 映射：只有子节点里有真实键时才写
        for (Node child : node.children.values()) {
            if (writtenAsKey(child)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 渲染树 ====================

    /** 按目录顺序建立渲染树：先按分组建段，再把每个配置项挂到对应路径上。 */
    private Map<String, Node> buildRenderTree() {
        Map<String, Node> roots = new LinkedHashMap<>();
        for (String prefix : groupOrder) {
            List<ConfigCatalogEntry> group = entriesOf(prefix);
            if (group.isEmpty()) {
                continue;
            }
            Node prefixNode = nodeOf(roots, prefix, "");
            if (prefixNode.comment.isEmpty()) {
                prefixNode.comment = groupComment(prefix);
            }
            for (ConfigCatalogEntry entry : group) {
                Node node = nodeOf(roots, entry.key(), "");
                node.entry = entry;
                // 敏感项（密码 / 密钥）与目录标记为「不主动写入」的项，默认值不写进文件：
                // 它们要么等配置里给出真实值，要么只以注释形式列出键名。
                node.value = entry.secret() || !entry.writable() ? null : entry.defaultValue();
            }
        }
        return roots;
    }

    /** 找到（或建立）某个点分路径对应的节点，并记录键名 / 完整路径 / 父节点。 */
    private static Node nodeOf(Map<String, Node> roots, String path, String ignored) {
        Node current = null;
        Map<String, Node> level = roots;
        StringBuilder full = new StringBuilder();
        for (String segment : path.split("\\.")) {
            if (full.length() > 0) {
                full.append('.');
            }
            full.append(segment);
            current = level.computeIfAbsent(segment, key -> new Node());
            current.key = segment;
            current.path = full.toString();
            current.parent = null;
            level = current.children;
        }
        // 记录父节点，便于附加目录外的键
        Node parent = null;
        level = roots;
        for (String segment : path.split("\\.")) {
            Node node = level.get(segment);
            node.parent = parent;
            parent = node;
            level = node.children;
        }
        return current;
    }

    /**
     * 把配置里「目录没登记」的键吸收进渲染树（附加到对应分组下），
     * 保证插件通过 {@code SetupContext.putGlobal} 写的额外配置也能落盘。
     * <p>
     * 注意：不能直接改配置里的 Map —— 向导传进来的是 {@code Map.of(...)} 这类不可变映射。
     */
    @SuppressWarnings("unchecked")
    private static void absorbExtras(Map<String, Node> roots, Map<String, Object> config,
                                     String prefix) {
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Node node = roots.get(key);
            Object value = entry.getValue();
            if (node == null) {
                // 目录外的键：按其所在层级附加（值与后代一起保留）
                roots.put(key, buildExtraNode(key, path, value));
                continue;
            }
            if (value instanceof Map<?, ?> map) {
                // 同时把「映射本身」记下来：目录里的标量项也可能被写成映射（如 email.types）
                node.value = value;
                absorbExtras(node.children, (Map<String, Object>) map, path);
            } else {
                node.value = value;
            }
        }
    }

    /** 用配置里的值建立「目录外」的节点子树。 */
    @SuppressWarnings("unchecked")
    private static Node buildExtraNode(String key, String path, Object value) {
        Node node = new Node();
        node.key = key;
        node.path = path;
        node.extra = true;
        if (value instanceof Map<?, ?> map) {
            node.value = map;
            for (Map.Entry<String, Object> child : ((Map<String, Object>) map).entrySet()) {
                node.children.put(String.valueOf(child.getKey()),
                        buildExtraNode(String.valueOf(child.getKey()),
                                path + "." + child.getKey(), child.getValue()));
            }
        } else {
            node.value = value;
        }
        return node;
    }

    // ==================== 渲染 ====================

    /**
     * 输出一个节点（及其子树）。
     *
     * @param padding 本级缩进（每级 2 空格）
     * @param topLevel 顶层分组（额外输出一条分隔注释）
     * @param first 是否是父节点的第一个子节点（不是就先插空行分隔）
     */
    /** 输出一个节点（及其子树）；返回是否真的写出了内容。 */
    private static boolean writeNode(StringBuilder out, Node node, String padding, boolean topLevel,
                                     boolean first) {
        ConfigCatalogEntry entry = node.entry;
        if (node.children.isEmpty()) {
            // 叶子：有值写值；敏感项 / 不写盘项只剩一行注释
            if (node.value == null && !suppressed(node)) {
                // 理论上不会发生（普通目录项的默认值一定在 value 里），保守起见不输出
                return false;
            }
            beginBlock(out, node, padding, topLevel, first);
            if (node.value != null) {
                writeValue(out, padding, node.key, node.value, 1);
            } else {
                out.append(padding).append("# ").append(node.key).append(": ")
                        .append(emptyValueFor(entry)).append('\n');
            }
            return true;
        }
        // 分组 / 映射：先收集要输出的子节点（只以注释列出的敏感项 / 不写盘项也要保留）
        List<Node> visible = new ArrayList<>();
        for (Node child : node.children.values()) {
            if (emitsContent(child) || emitsComment(child)) {
                visible.add(child);
            }
        }
        boolean anyRealContent = node.value != null
                && (!(node.value instanceof Map<?, ?>) || node.extra || node.entry == null);
        for (Node child : visible) {
            anyRealContent |= emitsContent(child);
        }
        if (!anyRealContent) {
            // 子节点全会降级成注释（比如整段都是「不写盘项」或者全是敏感项）：
            // 这一层自己的键不能写出去 —— 写成 `key:`（空值）会在下次启动时把用户手写的配置
            // 覆盖成 null（这正是 bootstrap-admins / rules 被清空的成因）；但注释必须照旧输出，
            // 否则用户看不到这些键（如 wikikernel.site.name）。
            for (Node child : visible) {
                writeNode(out, child, padding, false, false);
            }
            return false;
        }
        beginBlock(out, node, padding, topLevel, first);
        if (node.value != null && !(node.value instanceof Map<?, ?>)) {
            // 自身是标量（例如目录里是普通项，却被配置写成映射）：值 + 子节点一起写
            writeValue(out, padding, node.key, node.value, 1);
            for (Node child : visible) {
                writeNode(out, child, padding + "  ", false, true);
            }
            return true;
        }
        out.append(padding).append(node.key).append(":\n");
        boolean firstChild = true;
        for (Node child : visible) {
            writeNode(out, child, padding + "  ", false, firstChild);
            firstChild = false;
        }
        return true;
    }

    /** 输出节点前面的空行分隔、中文注释与顶层分组标题。 */
    private static void beginBlock(StringBuilder out, Node node, String padding, boolean topLevel,
                                   boolean first) {
        if (!first && out.length() > 0) {
            out.append('\n');
        }
        appendComment(out, node.entry != null ? node.entry.commentText(node.value) : node.comment,
                padding);
        if (topLevel) {
            out.append(padding).append("# ---------------- ").append(node.key)
                    .append(" ----------------\n");
        }
    }

    /**
     * 该节点是否会输出「内容」（真实键或一行「注释 + 键」），与 {@link #writeNode} 的判断一一对应。
     * <ol>
     *     <li>有值：是（值就是内容）；</li>
     *     <li>有子节点：递归看，只要有一个子节点有内容就写父键；</li>
     *     <li>都没有：敏感项 / 不写盘项会输出一行注释 → 不是内容，但它所在的分组段仍会输出。</li>
     * </ol>
     */
    private static boolean emitsContent(Node node) {
        if (node.value != null) {
            return true;
        }
        for (Node child : node.children.values()) {
            if (emitsContent(child)) {
                return true;
            }
        }
        return false;
    }

    /** 该节点是否要降级成「注释 + 键」（敏感项 / 不写盘项没有值时）。 */
    private static boolean suppressed(Node node) {
        return node.value == null && node.entry != null
                && (node.entry.secret() || !node.entry.writable());
    }

    /**
     * 该节点（自身或后代）是否会输出「注释 + 键」行。
     * <p>
     * 用来决定整段要不要渲染：像 {@code wikikernel.site}（只有 {@code site.name} 一个不写盘项）
     * 或者 {@code wikikernel.permissions}（全是用户数据）这类分组，自身没有值、也没有真实键，
     * 但必须把注释输出出来 —— 否则用户根本看不到这些可配置项。
     */
    private static boolean emitsComment(Node node) {
        if (suppressed(node)) {
            return true;
        }
        for (Node child : node.children.values()) {
            if (emitsComment(child)) {
                return true;
            }
        }
        return false;
    }

    /** 只列出键名（不给值）时，注释行里显示的占位空值。 */
    private static String emptyValueFor(ConfigCatalogEntry entry) {
        return switch (entry.type()) {
            case "list" -> "[]";
            case "map", "object" -> "{}";
            case "boolean" -> "false";
            case "integer", "long" -> "0";
            default -> "\"\"";
        };
    }

    /** 输出 {@code 键: 值}；列表 / 映射用块状写法（嵌套合法、便于人读）。 */
    private static void writeValue(StringBuilder out, String padding, String key, Object value,
                                   int depth) {
        String nested = padding + "  ".repeat(depth);
        if (value instanceof List<?> list && !list.isEmpty()) {
            out.append(padding).append(key).append(":\n");
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                    // 列表里的映射（如 initial-accounts）：第一个键跟短横线同行，其余键对齐
                    boolean firstInItem = true;
                    for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
                        writeValue(out, firstInItem ? nested + "- " : nested + "  ",
                                String.valueOf(mapEntry.getKey()), mapEntry.getValue(), depth + 2);
                        firstInItem = false;
                    }
                } else {
                    out.append(nested).append("- ").append(formatScalar(item)).append('\n');
                }
            }
            return;
        }
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            out.append(padding).append(key).append(":\n");
            for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
                writeValue(out, nested, String.valueOf(mapEntry.getKey()), mapEntry.getValue(),
                        depth + 1);
            }
            return;
        }
        out.append(padding).append(key).append(": ").append(formatScalar(value)).append('\n');
    }

    /** 渲染树的一个节点：对应一个配置项，或一个分组段，或配置里目录外的键。 */
    private static final class Node {
        /** 本节点的键名。 */
        private String key;
        /** 本节点的完整点分路径。 */
        private String path;
        /** 本节点对应的目录项（分组段 / 目录外的键为 null）。 */
        private ConfigCatalogEntry entry;
        /** 要写出的值（null = 只留注释）。 */
        private Object value;
        /** 是否是配置里目录外的键。 */
        private boolean extra;
        /** 分组注释。 */
        private String comment = "";
        /** 父节点（用于附加目录外的键）。 */
        private Node parent;
        /** 子节点。 */
        private final Map<String, Node> children = new LinkedHashMap<>();
    }


    /** 把注释按行加上 {@code #} 前缀。 */
    private static void appendComment(StringBuilder out, String text) {
        appendComment(out, text, "");
    }

    private static void appendComment(StringBuilder out, String text, String padding) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            out.append(padding).append("# ").append(trimmed).append('\n');
        }
    }

    /** 把一个默认值渲染成 YAML 标量（列表 / 映射用流式写法，保持单行、稳定）。 */
    public static String formatScalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                items.add(formatScalar(item));
            }
            return "[" + String.join(", ", items) + "]";
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return "{}";
            }
            List<String> items = new ArrayList<>();
            for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
                items.add(mapEntry.getKey() + ": " + formatScalar(mapEntry.getValue()));
            }
            return "{" + String.join(", ", items) + "}";
        }
        return formatText(String.valueOf(value));
    }

    /** 渲染一个字符串标量：需要时加双引号，避免被 YAML 误读（如 {@code wikikernel:pagecache:}）。 */
    private static String formatText(String text) {
        if (text.isEmpty()) {
            // 空串必须显式加引号，否则会被解析成 null
            return "\"\"";
        }
        return needsQuoting(text) ? "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : text;
    }

    /**
     * 判断字符串标量是否需要加引号：
     * 含 {@code ": "} / 结尾冒号 / 首尾空白 / 以 YAML 指示符开头，或本身就是
     * YAML 的布尔 / null / 数字字面量（如 {@code true}、{@code null}、{@code 123}）时都必须加。
     */
    private static boolean needsQuoting(String text) {
        if (text.contains(": ") || text.endsWith(":")) {
            return true;
        }
        if (!text.equals(text.strip())) {
            return true;
        }
        if ("-?".contains(String.valueOf(text.charAt(0)))) {
            return text.length() == 1 || text.charAt(1) == ' ';
        }
        if ("!&*|>%@`\"'#{}[],".indexOf(text.charAt(0)) >= 0) {
            return true;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(lower) || "false".equals(lower) || "null".equals(lower)
                || "yes".equals(lower) || "no".equals(lower) || "~".equals(lower)) {
            return true;
        }
        // 数字 / 日期样式的标量：加引号避免被解析成 int / double（本方法只用于字符串类型的值）
        return text.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")
                || text.matches("\\d{4}-\\d{2}-\\d{2}.*");
    }

    /** 注释里描述默认值的可读形式。 */
    public static String describeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "[]" : formatList(list);
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty() ? "{}" : String.valueOf(map);
        }
        if (value instanceof String text && text.isEmpty()) {
            return "空";
        }
        return String.valueOf(value);
    }

    private static String formatList(List<?> list) {
        List<String> items = new ArrayList<>();
        for (Object item : list) {
            items.add(String.valueOf(item));
        }
        return String.join(", ", items);
    }

    /** 渲染树的一个节点：要么对应一个配置项（{@code entry != null}），要么是一个分组段。 */
    private static final class Scope {
        /** 本节点自身的键名（如 {@code wikikernel}、{@code cache}、{@code page}）。 */
        private String ownKey;
        /** 本节点的完整点分路径（如 {@code wikikernel.cache.page}）。 */
        private String path;
        /** 本节点对应的配置项（分组段为 null）。 */
        private ConfigCatalogEntry entry;
        /** 需要写进文件的实际值（null = 用目录默认值）。 */
        private Object value;
        /** 是否目录外附加的节点（插件写入的额外配置）。 */
        private boolean added;
        /** 分组注释。 */
        private String comment = "";
        /** 子节点。 */
        private final Map<String, Scope> children = new LinkedHashMap<>();
    }

    /** SnakeYAML 反序列化的中间结构。 */
    private record Document(List<ConfigCatalogEntry> entries,
                            Map<String, String> groupComments,
                            List<String> groupOrder) {
    }

    /** 读取 classpath 上的目录资源；失败时返回空目录。 */
    private static Document loadResource() {
        try (InputStream stream = ConfigCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return new Document(List.of(), Map.of(), List.of());
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return parse(text);
        } catch (IOException exception) {
            throw new UncheckedIOException("读取配置目录 " + RESOURCE + " 失败", exception);
        }
    }

    /** 解析目录文本（测试里也用来解析临时目录文件）。 */
    @SuppressWarnings("unchecked")
    public static Document parse(String text) {
        Object loaded = new Yaml().load(text);
        if (!(loaded instanceof Map<?, ?> root)) {
            return new Document(List.of(), Map.of(), List.of());
        }
        List<ConfigCatalogEntry> entries = new ArrayList<>();
        Map<String, String> comments = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        Object groups = root.get("groups");
        if (!(groups instanceof List<?> groupList)) {
            return new Document(List.of(), Map.of(), List.of());
        }
        for (Object groupObject : groupList) {
            if (!(groupObject instanceof Map<?, ?> group)) {
                continue;
            }
            String prefix = String.valueOf(group.get("prefix"));
            order.add(prefix);
            Object comment = group.get("comment");
            if (comment != null) {
                comments.put(prefix, String.valueOf(comment));
            }
            Object items = group.get("items");
            if (!(items instanceof List<?> itemList)) {
                continue;
            }
            for (Object itemObject : itemList) {
                if (!(itemObject instanceof Map<?, ?> item)) {
                    continue;
                }
                entries.add(new ConfigCatalogEntry(
                        String.valueOf(item.get("key")),
                        String.valueOf(item.get("type")),
                        item.get("default"),
                        toStringList(item.get("enum")),
                        !Boolean.FALSE.equals(item.get("write")),
                        Boolean.TRUE.equals(item.get("secret")),
                        item.get("description") == null ? "" : String.valueOf(item.get("description"))));
            }
        }
        return new Document(entries, comments, order);
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    /** 供调用方写文件时用的 SnakeYAML 配置（块状列表，稳定输出）。 */
    public static DumperOptions dumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        return options;
    }

    /** 供调用方写文件时用的 Yaml 实例。 */
    public static Yaml yaml() {
        return new Yaml(dumperOptions());
    }

    /**
     * 解析 Spring 生成的 {@code spring-configuration-metadata.json}，取出全部配置键。
     * <p>
     * 这里不用 SnakeYAML：那份文件是严格 JSON，可能含 {@code \/} 这类 YAML 不认的转义，
     * 用 YAML 解析会在某些条目上报错。故自带一个极简 JSON 读取器（不引第三方依赖）。
     *
     * @param json metadata 文件全文
     * @return 全部配置键（保持文件里的顺序；解析失败返回空集合）
     */
    public static Set<String> metadataKeys(String json) {
        Set<String> keys = new LinkedHashSet<>();
        try {
            Object root = new JsonReader(json).readValue();
            if (root instanceof Map<?, ?> map && map.get("properties") instanceof List<?> properties) {
                for (Object item : properties) {
                    if (item instanceof Map<?, ?> property && property.get("name") != null) {
                        keys.add(String.valueOf(property.get("name")));
                    }
                }
            }
        } catch (RuntimeException exception) {
            // 解析失败视为「没有 metadata」：一致性测试会因此失败并暴露出问题
            return Set.of();
        }
        return Collections.unmodifiableSet(keys);
    }

    /** 极简 JSON 读取器（只覆盖 metadata / schema 用到的结构，避免新增依赖）。 */
    public static final class JsonReader {

        private final String text;
        private int position;

        public JsonReader(String text) {
            this.text = text;
        }

        /** 读取一个 JSON 值（对象 / 数组 / 字符串 / 布尔 / 数字 / null）。 */
        public Object readValue() {
            skipWhitespace();
            char current = text.charAt(position);
            return switch (current) {
                case '{' -> readObject();
                case '[' -> readArray();
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

        /** 读取一个 JSON 对象。 */
        public Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
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
                require(text.charAt(position) == ':', "JSON 对象里应为冒号");
                position++;
                map.put(key, readValue());
                skipWhitespace();
                char next = text.charAt(position++);
                if (next == '}') {
                    return map;
                }
                require(next == ',', "JSON 对象里应为逗号或右花括号");
            }
        }

        private List<Object> readArray() {
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
                require(next == ',', "JSON 数组里应为逗号或右方括号");
            }
        }

        private String readString() {
            require(text.charAt(position) == '"', "JSON 字符串应以引号开始");
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
                    // JSON 允许对 / 做转义（如 https:\/\/…），YAML 不认，所以这里必须自己解
                    case '/' -> out.append('/');
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
            require(position > start, "JSON 里的非法数字");
            String raw = text.substring(start, position);
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.valueOf(raw);
            }
            long value = Long.parseLong(raw);
            return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (Object) (int) value
                    : (Object) value;
        }

        private void expect(String literal) {
            require(text.startsWith(literal, position), "期望字面量 " + literal);
            position += literal.length();
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalStateException("JSON 解析失败：" + message);
            }
        }
    }

    /** 读取文本文件（保留原样，供测试比对）。 */
    public static String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 读取打包进 classpath 的 JSON Schema 字节（原样复制，避免文本转换引入差异）。 */
    public static byte[] schemaBytes() {
        try (InputStream stream = ConfigCatalog.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            return stream == null ? null : stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("读取配置 Schema " + SCHEMA_RESOURCE + " 失败", exception);
        }
    }

    /**
     * 给生成的 {@code LocalSettings.yaml} 准备 {@code $schema} 指令行。
     * <p>
     * 先把 Schema 复制到配置文件所在目录下的 {@link #SCHEMA_RUNTIME_PATH}
     * （部署目录里也能正确解析）；复制失败（磁盘只读等）时退回仓库内的规范位置
     * {@link #SCHEMA_REPO_PATH}。两处路径都是相对配置文件目录的，IDEA / VS Code
     * 按此解析；都找不到时 IDE 只会忽略该指令，不影响程序运行。
     */
    public String schemaDirective(Path configPath) {
        Path parent = configPath.toAbsolutePath().getParent();
        if (parent != null) {
            byte[] bytes = schemaBytes();
            if (bytes != null) {
                try {
                    Path target = parent.resolve(SCHEMA_RUNTIME_PATH);
                    Path targetParent = target.getParent();
                    if (targetParent != null) {
                        Files.createDirectories(targetParent);
                    }
                    Files.write(target, bytes);
                    return SCHEMA_DIRECTIVE + SCHEMA_RUNTIME_PATH;
                } catch (IOException exception) {
                    // 复制失败不影响配置生成，退回仓库内的规范路径
                }
            }
        }
        return SCHEMA_DIRECTIVE + SCHEMA_REPO_PATH;
    }

    /** 用 SnakeYAML 读取文件为 map（不存在或解析失败返回空 map）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object loaded = yaml().load(reader);
            return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
    }
}
