package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.config.SpecialPageProperties;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.SpecialPageMatch;
import cc.thonly.wikikernel.service.SpecialWikiPageService;
import cc.thonly.wikikernel.special.WhatLinksHereSpecialPage;
import cc.thonly.wikikernel.web.WikiTitles;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class SpecialWikiPageServiceImpl implements SpecialWikiPageService {

    private final List<SpecialPage> corePages;
    private final PluginManager pluginManager;
    private final SpecialPageProperties properties;

    public SpecialWikiPageServiceImpl(List<SpecialPage> corePages,
                                      PluginManager pluginManager,
                                      SpecialPageProperties properties) {
        this.corePages = corePages;
        this.pluginManager = pluginManager;
        this.properties = properties;
    }

    @Override
    public boolean isSpecialPage(String title) {
        if (title == null) {
            return false;
        }
        String normalized = title.trim().toLowerCase(Locale.ROOT);
        return prefixList().stream().anyMatch(normalized::startsWith);
    }

    @Override
    public Optional<SpecialPageMatch> resolve(String title, Map<String, String> parameters) {
        if (!isSpecialPage(title)) {
            return Optional.empty();
        }
        String bareName = stripPrefix(title);
        SpecialPage page = registry().get(normalizeKey(bareName));
        if (page != null) {
            return Optional.of(new SpecialPageMatch(bareName, page, parameters));
        }
        // MediaWiki 风格子页语法：Special:WhatLinksHere/页面标题、Special:Contributions/用户名 等
        int slash = bareName.indexOf('/');
        if (slash > 0 && slash < bareName.length() - 1) {
            String base = bareName.substring(0, slash).trim();
            String rest = WikiTitles.normalize(bareName.substring(slash + 1));
            SpecialPage basePage = base.isEmpty() ? null : registry().get(normalizeKey(base));
            if (basePage != null && !rest.isEmpty()) {
                Map<String, String> merged = new LinkedHashMap<>(parameters);
                // 不为每个特殊页定制参数名：page（链入/相关更改/页面信息）与 name（贡献/日志）都兜底注入
                merged.putIfAbsent(WhatLinksHereSpecialPage.PARAM_PAGE, rest);
                merged.putIfAbsent("name", rest);
                return Optional.of(new SpecialPageMatch(base, basePage, merged));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<SpecialPage> listRegisteredPages() {
        // registry 的 value 已按名字去重且 core 优先
        return List.copyOf(new LinkedHashSet<>(registry().values()));
    }

    /**
     * 每次解析时合并 core Bean 与插件扩展。
     * 插件在 ApplicationReady 之后才加载，因此这里不能缓存（列表很小，开销可忽略）。
     */
    private Map<String, SpecialPage> registry() {
        Map<String, SpecialPage> registry = new LinkedHashMap<>();
        // core 优先，同名时插件不覆盖
        for (SpecialPage page : corePages) {
            register(registry, page);
        }
        for (SpecialPage page : pluginManager.extensions(SpecialPage.class)) {
            register(registry, page);
        }
        return registry;
    }

    private void register(Map<String, SpecialPage> registry, SpecialPage page) {
        if (page == null) {
            return;
        }
        registry.putIfAbsent(normalizeKey(page.name()), page);
        for (String alias : page.aliases()) {
            registry.putIfAbsent(normalizeKey(alias), page);
        }
    }

    private String stripPrefix(String title) {
        String trimmed = title.trim();
        for (String prefix : prefixList()) {
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return trimmed;
    }

    private List<String> prefixList() {
        return properties.getPrefixes().stream()
                .filter(prefix -> prefix != null && !prefix.isBlank())
                .map(prefix -> prefix.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    private String normalizeKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
