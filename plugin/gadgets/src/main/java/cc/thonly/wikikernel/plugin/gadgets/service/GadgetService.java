package cc.thonly.wikikernel.plugin.gadgets.service;

import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.gadgets.model.GadgetDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gadgets 定义解析与「哪些 Gadget 对当前用户生效」（原 core {@code GadgetServiceImpl} 迁入插件）。
 * <p>
 * 定义页：{@code WikiKernel:Gadgets-definition}，资源页在 {@code 小部件:} / {@code Gadget:} 命名空间。
 * 解析只统计 {@code <pre>} 之外的 {@code * 名称 | …} 行，避免文档示例被当成真实 Gadget；
 * 定义按页面修订号缓存（修订号不可用时退回比较正文）。
 */
@Component
public class GadgetService {

    /** 定义页标题（与 MediaWiki 的 MediaWiki:Gadgets-definition 对应）。 */
    public static final String DEFINITION_PAGE = "WikiKernel:Gadgets-definition";

    /** 资源相对名的首选命名空间前缀（另一个前缀作为兼容回退）。 */
    private static final List<String> ASSET_PREFIXES = List.of("Gadget:", "小部件:");

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）。 */
    public static final String ENABLED_KEY = "features.gadgets";

    private static final Logger log = LoggerFactory.getLogger(GadgetService.class);

    private final WikiPageStore pages;
    private final SiteConfig siteConfig;
    private final UserSettings userSettings;
    private final CurrentUser currentUser;

    private volatile Long cachedRevisionId;
    private volatile String cachedContent;
    private volatile List<GadgetDefinition> cachedDefinitions = List.of();
    private volatile Boolean cachedTypeScriptEnabled;

    public GadgetService(WikiPageStore pages, SiteConfig siteConfig, UserSettings userSettings,
                         CurrentUser currentUser) {
        this.pages = pages;
        this.siteConfig = siteConfig;
        this.userSettings = userSettings;
        this.currentUser = currentUser;
    }

    /** Gadgets 功能是否开启（站点设置 {@code features.gadgets}，默认开启）。 */
    public boolean enabled() {
        return siteConfig == null || siteConfig.getBoolean(ENABLED_KEY, true);
    }

    /** 全部 Gadget 定义（定义页不存在 / 不可读时为空）。 */
    public List<GadgetDefinition> definitions() {
        Optional<PageSnapshot> page = pages.visibleByTitle(DEFINITION_PAGE);
        if (page.isEmpty()) {
            cachedRevisionId = null;
            cachedContent = null;
            cachedDefinitions = List.of();
            return List.of();
        }
        long pageId = page.get().id();
        Long revisionId = pages.latestRevisionId(pageId).orElse(null);
        boolean typeScriptEnabled = typeScriptEnabled();
        if (revisionId != null && Objects.equals(cachedRevisionId, revisionId)
                && Objects.equals(cachedTypeScriptEnabled, typeScriptEnabled)) {
            return cachedDefinitions;
        }
        synchronized (this) {
            boolean current = typeScriptEnabled();
            if (revisionId != null && Objects.equals(cachedRevisionId, revisionId)
                    && Objects.equals(cachedTypeScriptEnabled, current)) {
                return cachedDefinitions;
            }
            String content = pages.content(pageId).orElse("");
            if (revisionId == null && content.equals(cachedContent)
                    && Objects.equals(cachedTypeScriptEnabled, current)) {
                return cachedDefinitions;
            }
            List<GadgetDefinition> parsed = parse(content, current);
            cachedDefinitions = parsed;
            cachedRevisionId = revisionId;
            cachedContent = content;
            cachedTypeScriptEnabled = current;
            return parsed;
        }
    }

    /** 可见定义（不含 {@code hidden}）。 */
    public List<GadgetDefinition> visibleDefinitions() {
        return definitions().stream().filter(definition -> !definition.hidden()).toList();
    }

    /** 对**当前请求者**启用 / 可见的 Gadget 名单。 */
    public Set<String> enabledForCurrentUser() {
        return enabledFor(currentUserGroupNames(), userSettings == null || !currentUser.loggedIn()
                ? "" : userSettings.get(currentUser.id(), "gadgets", ""));
    }

    /** 对给定用户组与偏好值启用的 Gadget（纯函数，便于测试）。 */
    public Set<String> enabledFor(Set<String> groups, String configuredNames) {
        if (!enabled()) {
            return Set.of();
        }
        Set<String> userGroups = groups == null ? Set.of() : groups;
        String configured = configuredNames == null ? "" : configuredNames.trim();
        boolean explicitNone = "!".equals(configured);
        Set<String> chosen = configured.isEmpty()
                ? Set.of() : new LinkedHashSet<>(Arrays.asList(configured.split(",")));
        Set<String> enabled = new LinkedHashSet<>();
        for (GadgetDefinition definition : visibleDefinitions()) {
            if (!definition.rights().isEmpty() && Collections.disjoint(definition.rights(), userGroups)) {
                continue;
            }
            boolean active = !explicitNone
                    && (configured.isEmpty() ? definition.defaultEnabled() : chosen.contains(definition.name()));
            if (active) {
                enabled.add(definition.name());
            }
        }
        return Collections.unmodifiableSet(enabled);
    }

    /** 当前用户是否启用了某个 Gadget。 */
    public boolean isEnabledForCurrentUser(String gadgetName) {
        return enabledForCurrentUser().contains(gadgetName);
    }

    /** 当前用户要加载的脚本资源页标题（去重；不存在的资源页跳过，避免注入 404）。 */
    public List<String> scriptTitles() {
        return assetTitles(true);
    }

    /** 当前用户要加载的样式资源页标题。 */
    public List<String> styleTitles() {
        return assetTitles(false);
    }

    /** 当前用户已启用且声明了模块依赖的 Gadget（供前端给出「未内置模块」告警）。 */
    public List<GadgetDefinition> enabledDefinitions() {
        Set<String> enabled = enabledForCurrentUser();
        if (enabled.isEmpty()) {
            return List.of();
        }
        return definitions().stream().filter(definition -> enabled.contains(definition.name())).toList();
    }

    /** 定义页地址（特殊页里给「编辑定义页」链接用）。 */
    public String definitionPageUrl() {
        return WikiUrls.pagePrefix() + DEFINITION_PAGE.replace(' ', '_');
    }

    private List<String> assetTitles(boolean scripts) {
        if (!enabled()) {
            return List.of();
        }
        Set<String> enabled = enabledForCurrentUser();
        if (enabled.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        for (GadgetDefinition definition : definitions()) {
            if (!enabled.contains(definition.name())) {
                continue;
            }
            for (String asset : scripts ? definition.scripts() : definition.styles()) {
                String title = resolveAsset(asset);
                if (title != null) {
                    titles.add(title);
                }
            }
        }
        return List.copyOf(titles);
    }

    /** 资源名 → 实际存在的页面标题：完整标题直接查；相对名依次尝试 {@code Gadget:} / {@code 小部件:}。 */
    private String resolveAsset(String asset) {
        String raw = asset == null ? "" : asset.trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.indexOf(':') >= 0) {
            return pages.visibleByTitle(raw).map(PageSnapshot::title).orElse(null);
        }
        for (String prefix : ASSET_PREFIXES) {
            Optional<PageSnapshot> page = pages.visibleByTitle(prefix + raw);
            if (page.isPresent()) {
                return page.get().title();
            }
        }
        return null;
    }

    private Set<String> currentUserGroupNames() {
        return currentUser == null ? Set.of() : currentUser.viewer().groups();
    }

    /** TypeScript 服务端编译是否开启（{@code .ts} / {@code .tsx} 资源只在可用时收作脚本）。 */
    private boolean typeScriptEnabled() {
        return siteConfig != null && siteConfig.getBoolean("features.typescript", false);
    }

    /**
     * 解析定义页（只统计 {@code <pre>} 之外的顶层 {@code *} 行；{@code **} 与 {@code #} 开头忽略）。
     *
     * @param typeScriptEnabled TypeScript 编译是否可用（{@code .ts} / {@code .tsx} 资源只在可用时收作脚本）
     */
    static List<GadgetDefinition> parse(String content, boolean typeScriptEnabled) {
        List<GadgetDefinition> result = new ArrayList<>();
        boolean insidePre = false;
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("<pre")) {
                insidePre = true;
            }
            if (insidePre) {
                if (lower.contains("</pre>")) {
                    insidePre = false;
                }
                continue;
            }
            if (!line.startsWith("*") || line.startsWith("**")) {
                continue;
            }
            String body = line.substring(1).trim();
            if (body.isEmpty() || body.startsWith("#") || body.startsWith("<!--")) {
                continue;
            }
            List<String> columns = Arrays.stream(body.split("\\|"))
                    .map(String::trim).filter(value -> !value.isEmpty()).toList();
            if (columns.isEmpty()) {
                continue;
            }
            String name = columns.get(0);
            String description = "";
            List<String> scripts = new ArrayList<>();
            List<String> styles = new ArrayList<>();
            List<String> dependencies = new ArrayList<>();
            List<String> rights = new ArrayList<>();
            boolean hidden = false;
            boolean defaultEnabled = false;
            for (String column : columns.subList(1, columns.size())) {
                String lowerColumn = column.toLowerCase(Locale.ROOT);
                if (lowerColumn.equals("hidden")) {
                    hidden = true;
                } else if (lowerColumn.equals("default")) {
                    defaultEnabled = true;
                } else if (lowerColumn.startsWith("description=")) {
                    description = column.substring("description=".length()).trim();
                } else if (lowerColumn.startsWith("dependencies=")) {
                    // 与 MediaWiki 一致：dependencies=oojs-ui-core,mediawiki.api
                    dependencies = Arrays.stream(column.substring("dependencies=".length()).split(","))
                            .map(String::trim).filter(value -> !value.isEmpty()).toList();
                } else if (lowerColumn.startsWith("rights=")) {
                    rights = Arrays.stream(column.substring("rights=".length()).split(","))
                            .map(String::trim).filter(value -> !value.isEmpty()).toList();
                } else if (lowerColumn.endsWith(".css")) {
                    styles.add(column);
                } else if (lowerColumn.endsWith(".js")) {
                    scripts.add(column);
                } else if (lowerColumn.endsWith(".ts") || lowerColumn.endsWith(".tsx")) {
                    // TypeScript 脚本：由 core 的服务端编译（?action=raw&ctype=text/javascript 返回编译产物）
                    if (typeScriptEnabled) {
                        scripts.add(column);
                    } else {
                        log.warn("Gadget「{}」引用了 TypeScript 脚本 {}，但 TypeScript 编译未开启，已跳过",
                                name, column);
                    }
                } else if (description.isEmpty()) {
                    description = column;
                }
            }
            result.add(new GadgetDefinition(name, description, List.copyOf(scripts),
                    List.copyOf(styles), List.copyOf(dependencies), rights, hidden, defaultEnabled));
        }
        return List.copyOf(result);
    }
}
