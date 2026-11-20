package cc.thonly.wikikernel.sidebar;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 侧边栏内容：读取 {@code WikiKernel:Sidebar} 页面的最新版本并解析；页面不存在或为空时使用内置默认。
 * <p>
 * 支持按当前页面替换的占位：{@code $title$}（大小写不敏感）与 MediaWiki 风格的 {@code {{PAGENAME}}}。
 */
@Service
public class SidebarService {

    /** 当前页面标题的占位（大小写不敏感）。 */
    public static final String TITLE_TOKEN = "$title$";

    private final WikiPageService wikiPageService;
    private final SidebarProperties properties;
    private final MessageSource messageSource;

    public SidebarService(WikiPageService wikiPageService, SidebarProperties properties, MessageSource messageSource) {
        this.wikiPageService = wikiPageService;
        this.properties = properties;
        this.messageSource = messageSource;
    }

    /** 不带页面上下文（占位保持原样）。 */
    public List<SidebarSection> sidebar() {
        return sidebar("");
    }

    /** 侧栏内容；{@code currentTitle} 非空时把 {@code $title$} / {@code {{PAGENAME}}} 替换为当前页面标题。 */
    public List<SidebarSection> sidebar(String currentTitle) {
        String content = customContent();
        if (!content.isBlank()) {
            content = substituteTitle(content, Strings.trimToEmpty(currentTitle));
            List<SidebarSection> sections = SidebarParser.parse(content);
            if (!sections.isEmpty()) {
                return localizeMediaWikiKeys(sections);
            }
        }
        return defaultSidebar();
    }

    // ==================== MediaWiki:Sidebar 的消息键 ====================

    /** 分组标题的消息键（MediaWiki:Sidebar 约定）→ core 消息键。 */
    private static final Map<String, String> SECTION_KEYS = Map.of(
            "navigation", "sidebar.navigation",
            "help", "sidebar.help",
            "TOOLBOX", "sidebar.tools",
            "LANGUAGES", "sidebar.languages");

    /** 不渲染的分组：MediaWiki 的 {@code SEARCH} 分组用来放搜索框，各皮肤都自带头部搜索。 */
    private static final Set<String> HIDDEN_SECTIONS = Set.of("SEARCH");

    /** 条目**目标**（普通页面）的消息键 → core 消息键。 */
    private static final Map<String, String> ITEM_TARGET_PAGE_KEYS = Map.of(
            "mainpage", "mainPage");

    /** 条目**目标**（特殊页面）的消息键 → 已注册的特殊页面名。 */
    private static final Map<String, String> ITEM_TARGET_SPECIAL_KEYS = Map.of(
            "recentchanges-url", "最近更改",
            "randompage-url", "随机页面");

    /** 条目**显示文字**的消息键 → core 消息键。 */
    private static final Map<String, String> ITEM_LABEL_KEYS = Map.of(
            "mainpage-description", "common.home",
            "recentchanges", "special.recentChanges",
            "randompage", "special.randomPage",
            "listfiles", "files.list",
            "upload", "tools.upload");

    /**
     * MediaWiki:Sidebar 里分组标题与条目（目标 / 显示文字）可以写**消息键**
     * （如 {@code * navigation}、{@code ** mainpage|mainpage-description}）：
     * 从 MediaWiki 迁移过来的侧栏页面直接照抄时，这里把已知的键换成当前语言的本地化文字，
     * 未知的键原样保留（不会比原来更差）。
     */
    private List<SidebarSection> localizeMediaWikiKeys(List<SidebarSection> sections) {
        List<SidebarSection> result = new ArrayList<>(sections.size());
        for (SidebarSection section : sections) {
            if (HIDDEN_SECTIONS.contains(section.getTitle())) {
                continue;
            }
            String title = localize(section.getTitle(), SECTION_KEYS);
            List<SidebarItem> items = new ArrayList<>(section.getItems().size());
            for (SidebarItem item : section.getItems()) {
                items.add(localizeItem(item));
            }
            result.add(new SidebarSection(title, List.copyOf(items)));
        }
        return result;
    }

    /** 按标签表本地化显示文字，特殊页面链接始终指向已注册的页面名。 */
    private SidebarItem localizeItem(SidebarItem item) {
        String label = localize(item.getLabel(), ITEM_LABEL_KEYS);
        String href = item.getHref();
        // 只对「纯目标」（无查询串 / 锚点）做本地化替换，避免把 ?action=... 之类的查询串当标题编码
        if (!item.isExternal() && href != null && href.indexOf('?') < 0 && href.indexOf('#') < 0) {
            String target = WikiUrls.pagePathRest(href);
            String pageKey = ITEM_TARGET_PAGE_KEYS.get(target);
            if (pageKey != null) {
                href = WikiUrls.pagePath(messageOr(pageKey, target));
            } else {
                String specialPage = ITEM_TARGET_SPECIAL_KEYS.get(target);
                if (specialPage != null) {
                    href = WikiUrls.pagePath("特殊:" + specialPage);
                }
            }
        }
        // 原始键保持不变（皮肤用它生成 id="n-<键>"，与 MediaWiki 的侧栏条目 id 一致）
        return new SidebarItem(label, href, item.isExternal(), item.getKey());
    }

    private String localize(String value, Map<String, String> keys) {
        String key = keys.get(value);
        return key == null ? value : messageOr(key, value);
    }

    /** 取消息；键不存在时回退到原值（迁移来的侧栏即使用了未知键也照常显示）。 */
    private String messageOr(String key, String fallback) {
        try {
            String value = messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    /**
     * 只对侧栏定义行（{@code *} 开头）做占位替换；没有页面上下文（标题为空）时，
     * 直接去掉这些依赖当前页面的条目（普通说明行原样保留，解析器本来也会忽略它们）。
     * <p>
     * 支持三种占位：{@code $title$}（完整标题，大小写不敏感，本 wiki 原生写法）、
     * {@code {{FULLPAGENAME}}}（完整标题，MediaWiki 风格）、
     * {@code {{PAGENAME}}}（去掉命名空间的页面名，与正文魔术字语义一致）。
     */
    private static String substituteTitle(String content, String title) {
        String shortTitle = shortPageName(title);
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder(content.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.stripLeading().startsWith("*")) {
                if (title.isEmpty()) {
                    if (containsTitleToken(line)) {
                        continue;
                    }
                } else {
                    line = line.replaceAll("(?i)\\{\\{fullpagename\\}\\}", Matcher.quoteReplacement(title))
                            .replaceAll("(?i)\\$title\\$", Matcher.quoteReplacement(title))
                            .replaceAll("(?i)\\{\\{pagename\\}\\}", Matcher.quoteReplacement(shortTitle));
                }
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static boolean containsTitleToken(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(TITLE_TOKEN) || lower.contains("{{pagename}}") || lower.contains("{{fullpagename}}");
    }

    /** {@code {{PAGENAME}}} 语义：去掉第一个冒号前的命名空间。 */
    private static String shortPageName(String title) {
        int colon = title.indexOf(':');
        return colon < 0 ? title : title.substring(colon + 1);
    }

    private String customContent() {
        return wikiPageService.getByTitle(properties.getPage())
                .flatMap(page -> wikiPageService.getLatestContent(page.getId()))
                .orElse("");
    }

    /** 页面缺失时的兜底侧边栏（文案跟随当前语言）：导航 + 完整「工具」箱（仿 MediaWiki）。 */
    private List<SidebarSection> defaultSidebar() {
        return defaultSidebar(this::message);
    }

    private List<SidebarSection> defaultSidebar(java.util.function.Function<String, String> text) {
        String home = WikiUrls.pagePath("首页");
        return List.of(
                new SidebarSection(text.apply("sidebar.navigation"), List.of(
                        new SidebarItem(text.apply("common.home"), home, false),
                        new SidebarItem(text.apply("special.allPages"), WikiUrls.pagePath("特殊:全部页面"), false),
                        new SidebarItem(text.apply("special.specialPages"), WikiUrls.pagePath("特殊:特殊页面"), false))),
                // 工具条目由 UserToolsSidebar 在内容页替换为「当前页面」版本；此处是页面缺失时的兜底目标
                new SidebarSection(text.apply("sidebar.tools"), List.of(
                        new SidebarItem(text.apply("tools.whatLinksHere"), WikiUrls.pagePath("特殊:链入页面"), false),
                        new SidebarItem(text.apply("tools.relatedChanges"), WikiUrls.pagePath("特殊:相关更改"), false),
                        new SidebarItem(text.apply("tools.upload"), WikiUrls.pagePath("特殊:上传文件"), false),
                        new SidebarItem(text.apply("tools.specialPages"), WikiUrls.pagePath("特殊:特殊页面"), false),
                        new SidebarItem(text.apply("tools.printable"), home + "?printable=yes", false),
                        new SidebarItem(text.apply("tools.permanentLink"), home + "?oldid=", false),
                        new SidebarItem(text.apply("tools.pageInfo"), WikiUrls.pagePath("特殊:页面信息"), false))));
    }

    /** Equivalent wikitext for the built-in sidebar, used by Special:AllMessages and its editor preload. */
    public String defaultSource(java.util.Locale locale) {
        var defaults = messageSource instanceof cc.thonly.wikikernel.i18n.PluginMessageSource source
                ? source.defaults(locale) : Map.<String, String>of();
        StringBuilder source = new StringBuilder();
        for (SidebarSection section : defaultSidebar(key -> defaults.containsKey(key) ? defaults.get(key) : messageSource.getMessage(key, null, locale))) {
            source.append("* ").append(section.getTitle()).append('\n');
            for (SidebarItem item : section.getItems()) {
                String target = item.isExternal() ? item.getHref() : WikiUrls.pagePathRest(item.getHref());
                source.append("** ").append(target).append('|').append(item.getLabel()).append('\n');
            }
        }
        return source.toString();
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
