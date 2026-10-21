package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.WikiKernelInfo;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.Skin;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.i18n.PluginMessageSource;
import cc.thonly.wikikernel.i18n.PluginI18n;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.LoadedPlugin;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.skin.ActiveSkin;
import cc.thonly.wikikernel.skin.SkinRegistry;
import org.springframework.boot.SpringBootVersion;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:版本} / {@code Special:Version}：仿 MediaWiki Special:Version。
 * <p>
 * 展示：已安装的软件（WikiKernel / Spring Boot / Java / 数据库 / 应用服务器）、入口点 URL、
 * 已安装的皮肤与客户端库、已安装的插件（{@link PluginManager}）以及插件扩展点（SPI）。
 * <p>
 * 「已安装的扩展」表 5 列：**扩展 / 插件 ID / 版本 / 说明 / JAR 包名**。
 * 扩展名是纯文本（不外链）；「说明」列是**原版 MediaWiki 扩展的官方说明**
 * （{@code version.extension.<插件 id>.description}，中英各一份），末尾附「（原插件）」
 * 链接指向该扩展官方页（{@code version.extension.<插件 id>.url}）；
 * 没有对应原扩展的插件（自研 / 皮肤）只显示插件自身描述、不带链接。
 */
@Component
public class VersionSpecialPage implements SpecialPage {

    /** 插件扩展点（接口简单名；说明文案走 {@code version.ext.<简单名>} 消息键）。 */
    private static final List<String> EXTENSION_POINTS = List.of(
            "PageRenderHook", "SearchProvider", "LogType", "SpecialPage", "Skin", "WikiPlugin",
            "ParserFunction", "MagicWord", "BehaviorSwitch", "ParserTag",
            "HtmlHeadProvider", "PageViewHook", "ActionApiModule", "CoreAopHook");

    /** 内置客户端库：名称 / 版本 / 说明消息键 / 来源消息键。 */
    private static final String CODEMIRROR_VERSION = "5.65.16";
    /** 本地内置的 Vega 绘图库版本（vega / vega-lite / vega-embed）。 */
    private static final String VEGA_VERSION = "5.33.1";
    private static final String VEGA_LITE_VERSION = "5.23.0";
    private static final String VEGA_EMBED_VERSION = "6.29.0";
    /** 本地内置的 Wikimedia Codex 样式 / 图标版本。 */
    private static final String CODEX_VERSION = "2.7.0";
    /** 本地内置的 jQuery / Axios / KaTeX / Vue / Ruffle 版本。 */
    private static final String JQUERY_VERSION = "3.7.1";
    private static final String AXIOS_VERSION = "1.20.0";
    private static final String KATEX_VERSION = "0.19.0";
    private static final String VUE_VERSION = "3.5.13";
    private static final String RUFFLE_VERSION = "0.7.1";

    private final PluginManager pluginManager;
    private final SkinRegistry skinRegistry;
    private final DataSource dataSource;
    private final PluginMessageSource messageSource;
    private final PluginI18n pluginI18n;

    public VersionSpecialPage(PluginManager pluginManager,
                              SkinRegistry skinRegistry,
                              DataSource dataSource,
                              PluginMessageSource messageSource,
                              PluginI18n pluginI18n) {
        this.pluginManager = pluginManager;
        this.skinRegistry = skinRegistry;
        this.dataSource = dataSource;
        this.messageSource = messageSource;
        this.pluginI18n = pluginI18n;
    }

    @Override
    public String name() {
        return "版本";
    }

    @Override
    public String displayName() {
        return message("special.version");
    }

    @Override
    public List<String> aliases() {
        return List.of("Version");
    }

    @Override
    public String template() {
        return "special/version";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        Skin activeSkin = skinRegistry.active(request.parameters().get("useskin"), user);
        model.put("wikiKernelVersion", WikiKernelInfo.VERSION);
        model.put("software", software());
        model.put("entryPoints", entryPoints());
        model.put("skins", skinRegistry.available().stream()
                .map(skin -> {
                    ActiveSkin view = skinRegistry.view(skin);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", view.name());
                    row.put("version", view.version());
                    row.put("description", view.description());
                    row.put("source", skinRegistry.sourceOf(skin));
                    row.put("active", skin.id().equals(activeSkin.id()));
                    return row;
                })
                .toList());
        model.put("clientLibraries", List.of(
                row4("jQuery", JQUERY_VERSION,
                        message("version.library.jquery.desc"),
                        message("version.library.jquery.source")),
                row4("Axios", AXIOS_VERSION,
                        message("version.library.axios.desc"),
                        message("version.library.axios.source")),
                row4("CodeMirror", CODEMIRROR_VERSION,
                        message("version.library.codemirror.desc"),
                        message("version.library.codemirror.source")),
                row4("Vega", VEGA_VERSION + " / vega-lite " + VEGA_LITE_VERSION
                                + " / vega-embed " + VEGA_EMBED_VERSION,
                        message("version.library.vega.desc"),
                        message("version.library.vega.source")),
                row4("KaTeX", KATEX_VERSION,
                        message("version.library.katex.desc"),
                        message("version.library.katex.source")),
                row4("Vue", VUE_VERSION,
                        message("version.library.vue.desc"),
                        message("version.library.vue.source")),
                row4("Ruffle", RUFFLE_VERSION,
                        message("version.library.ruffle.desc"),
                        message("version.library.ruffle.source")),
                // Wikimedia Codex（CSS + icons；本站组件外观的基准）
                row4("Codex", CODEX_VERSION,
                        message("version.library.codex.desc"),
                        message("version.library.codex.source")),
                row4("Codex Icons", CODEX_VERSION,
                        message("version.library.codexIcons.desc"),
                        message("version.library.codexIcons.source"))));
        model.put("plugins", plugins());
        model.put("parserTags", parserTags());
        model.put("extensionPoints", EXTENSION_POINTS.stream()
                .map(name -> Map.<String, Object>of(
                        "name", name,
                        "description", message("version.ext." + name, name)))
                .toList());
    }

    /** 已安装的软件：产品 + 版本。 */
    private List<Map<String, Object>> software() {
        List<Map<String, Object>> software = new ArrayList<>();
        software.add(row(WikiKernelInfo.NAME, WikiKernelInfo.VERSION));
        software.add(row("Spring Boot", SpringBootVersion.getVersion()));
        software.add(row("Java", System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")"));
        String database = databaseInfo();
        if (database != null) {
            software.add(row(message("version.product.database"), database));
        }
        String server = serverInfo();
        if (server != null) {
            software.add(row(message("version.product.server"), server));
        }
        return software;
    }

    /** 入口点 URL（仿 MediaWiki：入口点名称 + 路径；相对路径，link 表示可点击）。 */
    private List<Map<String, Object>> entryPoints() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(entry(message("version.entry.home"), "/", true));
        rows.add(entry(message("version.entry.articlePath"), WikiUrls.pagePrefix() + "$1", false));
        rows.add(entry(message("version.entry.specialPages"), WikiUrls.pagePath("特殊:特殊页面"), true));
        rows.add(entry(message("version.entry.api"), "/api/version", true));
        return rows;
    }

    /**
     * 已安装的插件（来自 PluginManager 的已加载列表），每行 5 个字段：
     * <ul>
     *     <li>{@code name}：扩展名（插件消息键 {@code plugin.<id>.name}，本地化；纯文本不外链）；</li>
     *     <li>{@code pluginId}：运行期插件 id；</li>
     *     <li>{@code version}：{@code plugin.version}；</li>
     *     <li>{@code description}：**原版 MediaWiki 扩展的官方说明**（core 的
     *     {@code version.extension.<id>.description}）；没有对应原扩展时退回插件自身描述；</li>
     *     <li>{@code originalUrl}：该扩展官方页地址（{@code version.extension.<id>.url}），
     *     模板把它渲染成说明末尾的「（原插件）」链接，为空则不带链接；</li>
     *     <li>{@code jar}：JAR 包名（{@link LoadedPlugin#source()}）。</li>
     * </ul>
     */
    /**
     * 解析器标签：**一张表**（core 内置在前、插件在后）——多个来源共享「解析器标签」这一个大板块，
     * 来源只是每行的一列，不再按来源拆成多张表 / 多个小标题。
     * <p>
     * core 行只列 {@code WikitextRenderer} 直接实现的标签（ref / references / nowiki / pre / poem /
     * syntaxhighlight / markdown）；math / timeline / graph / flash-player 等都由插件注册，走同一个循环。
     * 来源判定：插件注册的 {@code ParserTag} 实现类包名形如
     * {@code cc.thonly.wikikernel.plugin.<插件 id 去掉 - _>.…}，据此归到对应插件；
     * 归不上的一律算「其它插件」；同一个标签名只列一次（例如 category-tree 注册了展开前 / 展开后两个实现）。
     * 用法 / 参数说明走消息键 {@code version.tag.<标签名>.usage} / {@code …description}，没写则留空。
     */
    private List<Map<String, Object>> parserTags() {
        String core = message("version.parserTags.source.core");
        Map<String, List<String>> coreAliases = Map.of(
                "references", List.of("reference"),
                "syntaxhighlight", List.of("source"));
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : List.of("nowiki", "pre", "poem", "gallery",
                "syntaxhighlight", "markdown", "indicator")) {
            rows.add(tagRow(name, coreAliases.getOrDefault(name, List.of()), core));
            seen.add(name);
        }
        for (ParserTag tag : pluginManager.extensions(ParserTag.class)) {
            if (!seen.add(tag.name())) {
                continue;
            }
            List<String> aliases = new ArrayList<>(tag.names());
            aliases.remove(tag.name());
            rows.add(tagRow(tag.name(), aliases, sourceOf(tag.getClass().getName())));
        }
        return rows;
    }

    /** 标签行（名称 / 别名 / 用法 / 参数说明 / 来源）。 */
    private Map<String, Object> tagRow(String name, List<String> aliases, String source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("aliases", aliases == null || aliases.isEmpty() ? List.of() : List.copyOf(aliases));
        row.put("usage", Strings.trimToEmpty(messageSource.resolveOrNull("version.tag." + name + ".usage",
                LocaleContextHolder.getLocale())));
        row.put("description", Strings.trimToEmpty(messageSource.resolveOrNull(
                "version.tag." + name + ".description", LocaleContextHolder.getLocale())));
        row.put("source", source);
        return row;
    }

    /** 由实现类包名反查插件 id（包名 = 插件 id 去掉 - 和 _）；认不出来算其它插件。 */
    private String sourceOf(String className) {
        String prefix = "cc.thonly.wikikernel.plugin.";
        if (className == null || !className.startsWith(prefix)) {
            return message("version.parserTags.source.other");
        }
        String rest = className.substring(prefix.length());
        int dot = rest.indexOf('.');
        String packagePart = dot < 0 ? rest : rest.substring(0, dot);
        for (LoadedPlugin loaded : pluginManager.loadedPlugins()) {
            String id = loaded.descriptor().id();
            String sanitized = id.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
            if (sanitized.equals(packagePart.toLowerCase(Locale.ROOT))) {
                return loaded.descriptor().name() + "（" + id + "）";
            }
        }
        return message("version.parserTags.source.other") + "：" + packagePart;
    }

    private List<Map<String, Object>> plugins() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LoadedPlugin loaded : pluginManager.loadedPlugins()) {
            PluginDescriptor descriptor = loaded.descriptor();
            String id = descriptor.id();
            Map<String, Object> row = new LinkedHashMap<>();
            // 扩展名（本地化插件名，纯文本；不再指向原扩展页）
            row.put("name", pluginI18n.name(descriptor));
            // 插件 ID（运行期 plugin.id）
            row.put("pluginId", Strings.trimToEmpty(id));
            row.put("version", Strings.trimToEmpty(descriptor.version()));
            // 说明：优先「原版 MediaWiki 扩展的官方说明」（core 的 version.extension.<id>.description），
            // 没有对应原扩展（自研插件、皮肤）时退回插件自己的描述
            String original = extensionText(id, "description");
            row.put("description", original != null ? original : pluginI18n.description(descriptor));
            // （原插件）链接：指向该扩展的官方页面；没有就渲染成纯文本
            row.put("originalUrl", extensionText(id, "url"));
            // JAR 包名（加载来源，例如 abuse-filter-0.0.1-SNAPSHOT.jar）
            row.put("jar", Strings.trimToEmpty(loaded.source()));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 取「原版扩展」的元信息（core 的 {@code version.extension.<插件 id>.<字段>} 消息键）。
     *
     * @return 本地化后的文本；没有这个键时返回 {@code null}（不是键名）
     */
    private String extensionText(String pluginId, String field) {
        if (Strings.isBlank(pluginId)) {
            return null;
        }
        String key = "version.extension." + Strings.trimToEmpty(pluginId) + "." + Strings.trimToEmpty(field);
        String value = messageSource.resolveOrNull(key, LocaleContextHolder.getLocale());
        return Strings.isBlank(value) ? null : value;
    }

    private String databaseInfo() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName()
                    + " " + connection.getMetaData().getDatabaseProductVersion();
        } catch (SQLException exception) {
            return null;
        }
    }

    /** 应用服务器（Tomcat 等）通过反射读取，换容器时不会因缺类报错。 */
    private String serverInfo() {
        try {
            Class<?> serverInfo = Class.forName("org.apache.catalina.util.ServerInfo");
            Method getServerInfo = serverInfo.getMethod("getServerInfo");
            return String.valueOf(getServerInfo.invoke(null));
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private Map<String, Object> row(String product, String version) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("product", product);
        row.put("version", version);
        return row;
    }

    private Map<String, Object> row4(String name, String version, String description, String source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("version", version);
        row.put("description", description);
        row.put("source", source);
        return row;
    }

    private Map<String, Object> entry(String name, String path, boolean link) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("path", path);
        row.put("link", link);
        return row;
    }

    /** 本地化文案（只用键，不写中文兜底：缺键时消息源会显示键本身）。 */
    private String message(String key) {
        return messageSource.getMessage(key, null, null, LocaleContextHolder.getLocale());
    }

    /** 本地化文案 + 兜底值：兜底只允许**数据**（扩展点名字这类），不允许中文文案。 */
    private String message(String key, String fallback) {
        return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
