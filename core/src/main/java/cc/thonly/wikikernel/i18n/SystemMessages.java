package cc.thonly.wikikernel.i18n;

import cc.thonly.wikikernel.sidebar.SidebarService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.web.WikiTitles;
import org.springframework.stereotype.Service;
import java.util.*;

/** Installed message catalogue; no synthetic MediaWiki extension messages. */
@Service
public class SystemMessages {
    public record Entry(String name, String defaultText, String currentText, String title, boolean modified) { }
    private final PluginMessageSource source;
    private final InterfaceMessageOverrides overrides;
    private final SidebarService sidebar;
    private final cc.thonly.wikikernel.sidebar.SidebarProperties sidebarProperties;

    public SystemMessages(PluginMessageSource source, InterfaceMessageOverrides overrides, SidebarService sidebar,
                          cc.thonly.wikikernel.sidebar.SidebarProperties sidebarProperties) {
        this.source = source; this.overrides = overrides; this.sidebar = sidebar; this.sidebarProperties = sidebarProperties;
    }

    public SortedMap<String, String> defaults(Locale language) {
        SortedMap<String, String> defaults = new TreeMap<>(source.defaults(language));
        // These pages are already consumed by SidebarService / SystemInterfaceService.
        defaults.putIfAbsent("sidebar", sidebar.defaultSource(language));
        for (String key : List.of("sitenotice", "anonnotice", "tagline", "editnotice")) defaults.putIfAbsent(key, "");
        return defaults;
    }

    public List<Entry> entries(Locale language) {
        var values = overrides.visible();
        List<Entry> result = new ArrayList<>();
        defaults(language).forEach((key, text) -> {
            var custom = key.equals("sidebar") ? values.get(WikiTitles.normalize(sidebarProperties.getPage()))
                    : overrides.find(values, key, language, Set.of("sitenotice", "anonnotice", "tagline", "editnotice").contains(key));
            result.add(new Entry(key, text, custom == null ? text : custom.text(),
                    custom != null ? custom.title() : key.equals("sidebar") ? WikiTitles.normalize(sidebarProperties.getPage())
                            : InterfaceMessageOverrides.title(key, language), custom != null));
        });
        return result;
    }

    public Optional<String> editDefault(String title) {
        if (WikiTitles.normalize(title).equals(WikiTitles.normalize(sidebarProperties.getPage()))) {
            return Optional.of(sidebar.defaultSource(org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        }
        if (!WikiNamespaces.isWikiKernel(title) && !WikiNamespaces.isMediaWiki(title)) return Optional.empty();
        String normalized = WikiTitles.normalize(title);
        String name = normalized.substring(normalized.indexOf(':') + 1);
        Locale language = Locale.SIMPLIFIED_CHINESE;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            String code = name.substring(slash + 1);
            InterfaceLanguage selected = InterfaceLanguage.parse(code);
            if (selected == null) return Optional.empty();
            language = selected.getLocale();
            name = name.substring(0, slash);
        }
        String target = WikiTitles.normalize("WikiKernel:" + name);
        return defaults(language).entrySet().stream()
                .filter(e -> WikiTitles.normalize("WikiKernel:" + e.getKey()).equals(target))
                .map(Map.Entry::getValue).findFirst();
    }
}
