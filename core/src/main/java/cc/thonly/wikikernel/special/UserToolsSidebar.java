package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.InterwikiService;
import cc.thonly.wikikernel.sidebar.SidebarItem;
import cc.thonly.wikikernel.sidebar.SidebarSection;
import cc.thonly.wikikernel.sidebar.SidebarService;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 内容页 / 用户页左侧的「工具」栏（仿 MediaWiki 工具箱）：
 * 链入页面、相关更改、上传文件、特殊页面、可打印版、固定链接、页面信息，
 * 用户页另有用户贡献 / 日志 / 用户组 / 私信 / 核查等。追加在常规侧边栏之后。
 */
@Component
public class UserToolsSidebar {

    /** 权限编辑页（查看用户组也来这里）。 */
    public static final String RIGHTS_PAGE = "特殊:用户权限";
    /** 权限列表页。 */
    public static final String RIGHTS_LIST_PAGE = "特殊:权限列表";
    /** 链入页面。 */
    public static final String WHAT_LINKS_HERE_PAGE = "特殊:链入页面";
    /** 相关更改。 */
    public static final String RELATED_CHANGES_PAGE = "特殊:相关更改";
    /** 用户贡献。 */
    public static final String CONTRIBUTIONS_PAGE = "特殊:用户贡献";
    /** 日志。 */
    public static final String LOG_PAGE = "特殊:日志";
    /** 页面信息。 */
    public static final String PAGE_INFO_PAGE = "特殊:页面信息";
    /** 上传文件。 */
    public static final String UPLOAD_PAGE = "特殊:上传文件";
    /** 特殊页面列表。 */
    public static final String SPECIAL_PAGES_PAGE = "特殊:特殊页面";
    /** 用户核查（核查员）。 */
    public static final String CHECK_USER_PAGE = "特殊:用户核查";
    /** 用户合并（核查员）。 */
    public static final String USER_MERGE_PAGE = "特殊:用户合并";
    /** 封禁（需要 block 权限，默认管理员）。 */
    public static final String BLOCK_PAGE = "特殊:封禁";
    /** 私信（写私信入口）。 */
    public static final String MESSAGES_PAGE = "特殊:私信";

    private final SidebarService sidebarService;
    private final MessageSource messageSource;

    public UserToolsSidebar(SidebarService sidebarService, MessageSource messageSource) {
        this.sidebarService = sidebarService;
        this.messageSource = messageSource;
    }

    /** 常规侧边栏 + 「工具」工具箱。 */
    public List<SidebarSection> withUserTools(String username) {
        return withUserTools(username, false);
    }

    /** 常规侧边栏 + 「工具」工具箱；{@code canCheckUser=true}（核查员）追加用户核查 / 用户合并链接。 */
    public List<SidebarSection> withUserTools(String username, boolean canCheckUser) {
        return withUserTools(username, canCheckUser, false);
    }

    /** 常规侧边栏 + 「工具」工具箱；{@code canBlock=true}（有封禁权限）追加「封禁」链接。 */
    public List<SidebarSection> withUserTools(String username, boolean canCheckUser, boolean canBlock) {
        return withUserTools(username, canCheckUser, canBlock, false);
    }

    /** 常规侧边栏 + 「工具」工具箱；{@code canSendMessage=true}（登录且不是自己的用户页）追加「发私信」链接。 */
    public List<SidebarSection> withUserTools(String username, boolean canCheckUser, boolean canBlock,
                                              boolean canSendMessage) {
        return withUserTools(username, canCheckUser, canBlock, canSendMessage, false);
    }

    /**
     * 常规侧边栏 + 「工具」工具箱。
     *
     * @param canChangeGroups 当前查看者能否修改用户组（管理员 / 行政员）：决定「更改用户组」还是「查看用户组」
     */
    public List<SidebarSection> withUserTools(String username, boolean canCheckUser, boolean canBlock,
                                              boolean canSendMessage, boolean canChangeGroups) {
        String userTitle = "用户:" + username;
        String userUrl = page(userTitle);
        List<SidebarItem> items = new ArrayList<>();
        items.add(item("tools.whatLinksHere", page(WHAT_LINKS_HERE_PAGE) + "?page=" + encode(userTitle)));
        items.add(item("tools.relatedChanges", page(RELATED_CHANGES_PAGE) + "?page=" + encode(userTitle)));
        items.add(item("tools.userContributions", page(CONTRIBUTIONS_PAGE) + "?name=" + encode(username)));
        items.add(item("tools.logs", page(LOG_PAGE) + "?name=" + encode(username)));
        items.add(item("tools.upload", page(UPLOAD_PAGE)));
        items.add(item("tools.specialPages", page(SPECIAL_PAGES_PAGE)));
        items.add(item("tools.printable", userUrl + "?printable=yes"));
        items.add(item("tools.permanentLink", userUrl));
        items.add(item(canChangeGroups ? "tools.changeGroups" : "tools.viewGroups",
                page(RIGHTS_PAGE) + "?name=" + encode(username)));
        items.add(item("tools.pageInfo", userUrl + "?action=info"));
        if (canSendMessage) {
            items.add(item("tools.sendMessage", page(MESSAGES_PAGE) + "?write=1&to=" + encode(username)));
        }
        if (canCheckUser) {
            items.add(item("tools.checkUser", page(CHECK_USER_PAGE) + "?user=" + encode(username)));
            items.add(item("tools.userMerge", page(USER_MERGE_PAGE) + "?oldUser=" + encode(username)));
        }
        if (canBlock) {
            items.add(item("tools.block", page(BLOCK_PAGE) + "?target=" + encode(username)));
        }
        return replaceTools(userTitle, items);
    }

    /** 内容页工具箱（不带固定链接版本号）：见 {@link #withPageTools(String, Long)}。 */
    public List<SidebarSection> withPageTools(String pageTitle) {
        return withPageTools(pageTitle, null);
    }

    /**
     * 在侧栏末尾追加「语言」区块（跨语言链接，仿 MediaWiki 的 Languages 侧栏）：
     * 条目来自正文里的 {@code [[en:页面]]}（渲染时已从正文剥离），全部是外部链接（可嵌入 wiki 的其它语言版本）。
     */
    public List<SidebarSection> withInterlanguages(List<SidebarSection> sections,
                                                  List<InterwikiService.Link> links) {
        if (sections == null || links == null || links.isEmpty()) {
            return sections;
        }
        List<SidebarItem> items = new ArrayList<>();
        for (InterwikiService.Link link : links) {
            String label = link.languageName() == null || link.languageName().isBlank()
                    ? link.prefix() : link.languageName();
            items.add(new SidebarItem(label, link.url(), true));
        }
        List<SidebarSection> result = new ArrayList<>(sections);
        result.add(new SidebarSection(message("sidebar.languages"), items));
        return result;
    }

    /**
     * 特殊页侧栏：工具箱条目按当前特殊页生成——
     * 链入页面 / 相关更改以该特殊页为查询目标；可打印版打开特殊页的打印版；
     * 固定链接指向特殊页本身（特殊页没有版本）；页面信息显示特殊页信息（{@code ?action=info}）。
     */
    public List<SidebarSection> withSpecialPageTools(String specialTitle, String specialPath) {
        String title = Strings.trimToEmpty(specialTitle);
        String base = Strings.trimToEmpty(specialPath);
        if (base.isEmpty()) {
            base = page(title);
        }
        List<SidebarItem> items = new ArrayList<>();
        items.add(item("tools.whatLinksHere", page(WHAT_LINKS_HERE_PAGE) + "?page=" + encode(title)));
        items.add(item("tools.relatedChanges", page(RELATED_CHANGES_PAGE) + "?page=" + encode(title)));
        items.add(item("tools.upload", page(UPLOAD_PAGE)));
        items.add(item("tools.specialPages", page(SPECIAL_PAGES_PAGE)));
        items.add(item("tools.printable", base + "?printable=yes"));
        items.add(item("tools.permanentLink", base));
        items.add(item("tools.pageInfo", base + "?action=info"));
        return replaceTools(title, items);
    }

    /**
     * 内容页（词条 / 模板 / 分类 / 文件等）的「工具」箱（仿 MediaWiki 工具箱）：
     * 链入页面 / 相关更改 / 上传文件 / 特殊页面 / 可打印版 / 固定链接 / 页面信息，
     * 其中链入页面、相关更改、页面信息指向**当前页面**，固定链接指向 {@code ?oldid=当前版本}。
     * <p>
     * {@code pageTitle} 为空表示没有页面上下文：只保留上传文件 / 特殊页面（特殊页请用
     * {@link #withSpecialPageTools(String, String)}）。
     */
    public List<SidebarSection> withPageTools(String pageTitle, Long latestRevisionId) {
        String title = Strings.trimToEmpty(pageTitle);
        List<SidebarItem> items = new ArrayList<>();
        if (title.isEmpty()) {
            items.add(item("tools.upload", page(UPLOAD_PAGE)));
            items.add(item("tools.specialPages", page(SPECIAL_PAGES_PAGE)));
        } else {
            String pageUrl = page(title);
            items.add(item("tools.whatLinksHere", page(WHAT_LINKS_HERE_PAGE) + "?page=" + encode(title)));
            items.add(item("tools.relatedChanges", page(RELATED_CHANGES_PAGE) + "?page=" + encode(title)));
            items.add(item("tools.upload", page(UPLOAD_PAGE)));
            items.add(item("tools.specialPages", page(SPECIAL_PAGES_PAGE)));
            items.add(item("tools.printable", pageUrl + "?printable=yes"));
            items.add(item("tools.permanentLink",
                    latestRevisionId == null ? pageUrl : pageUrl + "?oldid=" + latestRevisionId));
            items.add(item("tools.pageInfo", pageUrl + "?action=info"));
        }
        return replaceTools(title, items);
    }

    /** 工具箱托管的链接目标：这些条目由工具箱按当前页面自动生成（识别与页面语言无关）。 */
    private static final Set<String> MANAGED_TARGETS = Set.of(
            "特殊:链入页面", "特殊:相关更改", "特殊:上传文件", "特殊:特殊页面", "特殊:页面信息");

    /**
     * 把标准「工具」条目并入站点侧栏：先移除原有的托管条目（按链接目标识别，中英文界面都能命中），
     * 在第一个托管条目的位置插入标准条目；用户自定义的条目原样保留。
     * <p>
     * {@code contextTitle} 用于把侧栏页面里的 {@code $title$} / {@code {{PAGENAME}}} 占位替换成当前页面标题。
     */
    private List<SidebarSection> replaceTools(String contextTitle, List<SidebarItem> items) {
        List<SidebarSection> sections = new ArrayList<>();
        String toolsTitle = message("sidebar.tools");
        boolean merged = false;
        for (SidebarSection existing : sidebarService.sidebar(contextTitle)) {
            if (!merged && isToolsSection(existing, toolsTitle)) {
                List<SidebarItem> mergedItems = new ArrayList<>();
                boolean inserted = false;
                for (SidebarItem existingItem : existing.getItems()) {
                    if (isManagedToolItem(existingItem)) {
                        if (!inserted) {
                            mergedItems.addAll(items);
                            inserted = true;
                        }
                    } else {
                        mergedItems.add(existingItem);
                    }
                }
                if (!inserted) {
                    mergedItems.addAll(items);
                }
                // 沿用侧栏页面里的分组标题（侧栏页面可能是另一种语言，不能直接换成本地化标题）
                sections.add(new SidebarSection(existing.getTitle(), List.copyOf(mergedItems)));
                merged = true;
            } else {
                sections.add(existing);
            }
        }
        if (!merged) {
            sections.add(new SidebarSection(toolsTitle, List.copyOf(items)));
        }
        return sections;
    }

    /**
     * 是否站点侧栏里的「工具」分组：标题匹配当前语言，或含工具箱托管条目的其它分组
     * （侧栏页面内容的语言可能与界面语言不同，例如中文侧栏 + 英文界面）。「导航」分组即使
     * 有「特殊页面」这类条目也不算工具分组。
     */
    private boolean isToolsSection(SidebarSection section, String toolsTitle) {
        if (toolsTitle.equals(section.getTitle())) {
            return true;
        }
        if (message("sidebar.navigation").equals(section.getTitle())) {
            return false;
        }
        return section.getItems().stream().anyMatch(this::isManagedToolItem);
    }

    /** 是否工具箱托管的条目：链接目标为标准工具页，或带 {@code printable=} / {@code oldid=}（非差异对比）参数。 */
    private boolean isManagedToolItem(SidebarItem item) {
        String href = item.getHref();
        if (href == null || href.isEmpty()) {
            return false;
        }
        int query = href.indexOf('?');
        String base = query < 0 ? href : href.substring(0, query);
        String queryText = query < 0 ? "" : href.substring(query + 1);
        if (base.startsWith(WikiUrls.pagePrefix())) {
            String target = base.substring(WikiUrls.pagePrefix().length());
            try {
                target = URLDecoder.decode(target, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // 非法编码时按原样比较
            }
            if (MANAGED_TARGETS.contains(target.replace('_', ' '))) {
                return true;
            }
        }
        if (queryText.contains("printable=") || queryText.contains("action=info")) {
            return true;
        }
        return queryText.contains("oldid=") && !queryText.contains("diff=");
    }

    private SidebarItem item(String labelKey, String href) {
        // 条目 id 用 MediaWiki 风格的 t-*（皮肤 CSS 的 #t-whatlinkshere / #t-print … 图标规则按它匹配；
        // 与站点侧栏条目用原始文字做 n-* id 是同一套约定）。没有对应工具的键用消息键兜底。
        String toolId = toolId(labelKey);
        return new SidebarItem(message(labelKey), href, false, toolId != null ? toolId : labelKey);
    }

    /**
     * 工具条目的 MediaWiki 风格 id（与 MediaWiki：Timeless 皮肤的 {@code #t-*} 图标规则一一对应）；
     * 没有对应工具的返回 {@code null}。
     */
    private static String toolId(String labelKey) {
        return switch (labelKey) {
            case "tools.whatLinksHere" -> "t-whatlinkshere";
            case "tools.relatedChanges" -> "t-recentchangeslinked";
            case "tools.printable" -> "t-print";
            case "tools.permanentLink" -> "t-permalink";
            case "tools.pageInfo" -> "t-info";
            case "tools.upload" -> "t-upload";
            case "tools.specialPages" -> "t-specialpages";
            case "tools.userContributions" -> "t-contributions";
            case "tools.logs" -> "t-log";
            case "tools.viewGroups", "tools.changeGroups" -> "t-userrights";
            case "tools.checkUser" -> "t-checkuser";
            case "tools.userMerge" -> "t-usermerge";
            case "tools.block" -> "t-blockip";
            case "tools.sendMessage" -> "t-emailuser";
            case "tools.pageLog" -> "t-pagelog";
            default -> null;
        };
    }

    private static String page(String title) {
        return WikiUrls.pagePath(title);
    }

    private static String encode(String value) {
        // 查询值里空格用 '+'（服务端按 query 语义解码）；'/' 保持字面量，避免全站出现 %2F
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%2F", "/");
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
