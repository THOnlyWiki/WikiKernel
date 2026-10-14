package cc.thonly.wikikernel.sidebar;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
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
                return sections;
            }
        }
        return defaultSidebar();
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
        return List.of(
                new SidebarSection(message("sidebar.navigation"), List.of(
                        new SidebarItem(message("common.home"), "/wiki/首页", false),
                        new SidebarItem(message("special.allPages"), "/wiki/特殊:全部页面", false),
                        new SidebarItem(message("special.specialPages"), "/wiki/特殊:特殊页面", false))),
                // 工具条目由 UserToolsSidebar 在内容页替换为「当前页面」版本；此处是页面缺失时的兜底目标
                new SidebarSection(message("sidebar.tools"), List.of(
                        new SidebarItem(message("tools.whatLinksHere"), "/wiki/特殊:链入页面", false),
                        new SidebarItem(message("tools.relatedChanges"), "/wiki/特殊:相关更改", false),
                        new SidebarItem(message("tools.upload"), "/wiki/特殊:上传文件", false),
                        new SidebarItem(message("tools.specialPages"), "/wiki/特殊:特殊页面", false),
                        new SidebarItem(message("tools.printable"), "/wiki/首页?printable=yes", false),
                        new SidebarItem(message("tools.permanentLink"), "/wiki/首页?oldid=", false),
                        new SidebarItem(message("tools.pageInfo"), "/wiki/特殊:页面信息", false))));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
