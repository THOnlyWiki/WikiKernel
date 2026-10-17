package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.actionapi.ActionApiTokens;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.PageCategoryService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MediaWiki 兼容的页面配置（{@code RLCONF} / {@code mw.config}）：
 * 每个页面把 {@code wgPageName}、{@code wgNamespaceNumber}、{@code wgUserName}、{@code wgUserGroups}、
 * {@code wgUserTokens}、{@code wgFormattedNamespaces} 等注入模板（{@code head.html} 里的
 * {@code window.RLCONF = …}），由 {@code /js/mediawiki.js} 读进 {@code mw.config}。
 * <p>
 * 组名做 MediaWiki 风格映射（管理员 → {@code sysop} 等），便于 Gadgets 里
 * {@code mw.config.get('wgUserGroups').indexOf('sysop')} 这类判断直接可用；未映射的组保留原名。
 */
@ControllerAdvice
public class MwConfigAdvice {

    /** 我们命名空间的稳定 id → MediaWiki 风格命名空间序号（0/1 为条母与讨论）。 */
    private static final Map<String, Integer> NAMESPACE_NUMBERS = Map.ofEntries(
            Map.entry("main", 0), Map.entry("talk", 1),
            Map.entry("user", 2), Map.entry("user-talk", 3),
            Map.entry("wikikernel", 4), Map.entry("wikikernel-talk", 5),
            Map.entry("file", 6), Map.entry("file-talk", 7),
            Map.entry("template", 10), Map.entry("template-talk", 11),
            Map.entry("help", 12), Map.entry("help-talk", 13),
            Map.entry("category", 14), Map.entry("category-talk", 15),
            Map.entry("module", 828), Map.entry("module-talk", 829),
            Map.entry("graph", 484), Map.entry("graph-talk", 485),
            Map.entry("component", 486), Map.entry("component-talk", 487),
            Map.entry("widget", 2300), Map.entry("widget-talk", 2301),
            Map.entry("gadget", 2302), Map.entry("gadget-talk", 2303));

    /** 我们的权限组 → MediaWiki 组名（脚本兼容用；未列出的组保留原名）。 */
    private static final Map<String, String> MEDIAWIKI_GROUPS = Map.ofEntries(
            Map.entry("管理员", "sysop"),
            Map.entry("行政员", "bureaucrat"),
            Map.entry("自动确定用户", "autoconfirmed"),
            Map.entry("机器人", "bot"),
            Map.entry("核查员", "checkuser"),
            Map.entry("界面管理员", "interface-admin"),
            Map.entry("评论管理员", "commentadmin"),
            Map.entry("小部件管理员", "gadgetmanager"),
            Map.entry("小部件编辑者", "gadgeteditor"),
            Map.entry("免审查用户", "autoreview"),
            Map.entry("页面管理员", "pageadmin"));

    /** 命名空间规范名（MediaWiki 的 wgCanonicalNamespace 风格，英文）。 */
    private static final Map<String, String> CANONICAL_NAMESPACES = Map.ofEntries(
            Map.entry("main", ""), Map.entry("talk", "Talk"),
            Map.entry("user", "User"), Map.entry("user-talk", "User talk"),
            Map.entry("wikikernel", "Project"), Map.entry("wikikernel-talk", "Project talk"),
            Map.entry("file", "File"), Map.entry("file-talk", "File talk"),
            Map.entry("template", "Template"), Map.entry("template-talk", "Template talk"),
            Map.entry("help", "Help"), Map.entry("help-talk", "Help talk"),
            Map.entry("category", "Category"), Map.entry("category-talk", "Category talk"),
            Map.entry("module", "Module"), Map.entry("module-talk", "Module talk"),
            Map.entry("graph", "Graph"), Map.entry("graph-talk", "Graph talk"),
            Map.entry("component", "Component"), Map.entry("component-talk", "Component talk"),
            Map.entry("widget", "Widget"), Map.entry("widget-talk", "Widget talk"),
            Map.entry("gadget", "Gadget"), Map.entry("gadget-talk", "Gadget talk"));

    private static final String SPECIAL_PREFIX_ZH = "特殊:";
    private static final String SPECIAL_PREFIX_EN = "Special:";

    private final MessageSource messageSource;
    private final WikiPageService wikiPageService;
    private final PageCategoryService pageCategoryService;
    private final PermissionService permissionService;
    private final SiteNameService siteNameService;

    public MwConfigAdvice(MessageSource messageSource,
                          WikiPageService wikiPageService,
                          PageCategoryService pageCategoryService,
                          PermissionService permissionService,
                          SiteNameService siteNameService) {
        this.messageSource = messageSource;
        this.wikiPageService = wikiPageService;
        this.pageCategoryService = pageCategoryService;
        this.permissionService = permissionService;
        this.siteNameService = siteNameService;
    }

    /** 每个请求都注入 {@code mwConfig}（模板里序列化成 {@code window.RLCONF}）。 */
    @ModelAttribute("mwConfig")
    public Map<String, Object> mwConfig(HttpServletRequest request) {
        try {
            return buildConfig(request);
        } catch (RuntimeException exception) {
            // 任何异常都不能影响页面渲染：退化成最小配置
            return Map.of("wgPageName", "", "wgScript", WikiUrls.pagePrefix(),
                    "wgArticlePath", WikiUrls.pagePrefix() + "$1");
        }
    }

    private Map<String, Object> buildConfig(HttpServletRequest request) {
        Locale locale = LocaleContextHolder.getLocale();
        String title = pageTitle(request);
        boolean special = isSpecial(title);
        String plainTitle = special ? title.substring(SPECIAL_PREFIX_ZH.length()) : title;

        WikiPage page = null;
        if (!special && !title.isEmpty()) {
            try {
                page = wikiPageService.getByTitle(title).orElse(null);
            } catch (RuntimeException ignored) {
                // 数据库未就绪（安装向导等）：忽略
            }
        }
        String namespaceId = special ? null : namespaceIdOf(title);
        Integer namespaceNumber = special ? -1 : NAMESPACE_NUMBERS.getOrDefault(namespaceId, 0);
        String action = actionOf(request);

        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("wgPageName", special ? title : WikiTitles.normalize(title));
        conf.put("wgTitle", plainTitle);
        conf.put("wgNamespaceNumber", namespaceNumber);
        conf.put("wgNamespace", special
                ? messageSource.getMessage("namespace.special", null, null, locale)
                : namespaceLabel(namespaceId, locale));        conf.put("wgCanonicalNamespace", special ? "Special" : CANONICAL_NAMESPACES.getOrDefault(namespaceId, ""));
        conf.put("wgIsArticle", page != null && namespaceNumber == 0 && "view".equals(action)
                && page.getLatestRevisionId() != null);
        conf.put("wgAction", action);
        conf.put("wgArticlePath", WikiUrls.pagePrefix() + "$1");
        // 我们没有 index.php：wgScript 用页面的入口前缀（非标准，已在 README 说明）
        conf.put("wgScriptPath", "");
        conf.put("wgScript", WikiUrls.pagePrefix());
        conf.put("wgServer", serverOf(request));
        conf.put("wgSiteName", siteNameService.name());
        conf.put("wgVersion", appVersion());
        conf.put("wgContentLanguage", locale.getLanguage());
        conf.put("wgPageContentLanguage", locale.getLanguage());
        conf.put("wgUserLanguage", locale.getLanguage());
        conf.put("wgCategories", categories(page));
        conf.put("wgRevisionId", page == null ? null : page.getLatestRevisionId());
        conf.put("wgCurRevisionId", page == null ? null : page.getLatestRevisionId());
        conf.put("wgFormattedNamespaces", formattedNamespaces(locale));
        conf.put("wgNamespaceIds", namespaceIds(locale));
        conf.put("wgContentNamespaces", List.of(0));
        conf.put("wgCaseSensitiveNamespaces", List.of());
        conf.put("wgLegalTitleChars", " %!\"$&'()*,\\-./0-9:;=?@A-Z\\\\^_`a-z~+\\u0080-\\uFFFF");

        WikiUser user = UserSession.current(request);
        conf.put("wgUserName", user == null ? null : user.getUsername());
        conf.put("wgUserId", user == null ? null : user.getId());
        conf.put("wgUserGroups", groups(user));
        try {
            conf.put("wgUserTokens", Map.of("csrfToken", ActionApiTokens.csrf(request),
                    "loginToken", ActionApiTokens.login(request)));
        } catch (RuntimeException ignored) {
            conf.put("wgUserTokens", Map.of());
        }
        return conf;
    }

    // ==================== 取值工具 ====================

    /** 请求路径 {@code /wiki/<标题>}（含子页）→ 页面标题；其它路径返回空串。 */
    private static String pageTitle(HttpServletRequest request) {
        try {
            String uri = request.getRequestURI();
            String context = request.getContextPath();
            if (context != null && !context.isEmpty() && uri.startsWith(context)) {
                uri = uri.substring(context.length());
            }
            String rest = WikiUrls.pagePathRest(uri);
            if (rest == null) {
                return "";
            }
            // 路径解码：+ 不能当空格（标题里的 + 是字面量）
            String decoded = URLDecoder.decode(rest.replace("+", "%2B"), StandardCharsets.UTF_8);
            return WikiTitles.normalize(decoded);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static boolean isSpecial(String title) {
        return title.regionMatches(true, 0, SPECIAL_PREFIX_EN, 0, SPECIAL_PREFIX_EN.length())
                || title.startsWith(SPECIAL_PREFIX_ZH);
    }

    /** 标题命中的命名空间 id（主命名空间 / 未知 → {@code main}）。 */
    private static String namespaceIdOf(String title) {
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            if (!WikiNamespaces.MAIN.equals(namespace) && WikiNamespaces.matches(title, namespace)) {
                return namespace.id();
            }
        }
        return "main";
    }

    private String namespaceLabel(String namespaceId, Locale locale) {
        if ("main".equals(namespaceId)) {
            return ""; // MediaWiki：主命名空间的 wgNamespace / wgFormattedNamespaces[0] 都是空串
        }
        return WikiNamespaces.byId(namespaceId)
                .map(namespace -> messageSource.getMessage(namespace.labelKey(), null,
                        CANONICAL_NAMESPACES.getOrDefault(namespace.id(), ""), locale))
                .orElse("");
    }

    /** 命名空间 id → 本地化名称（{@code 0 → ""}）。 */
    private Map<String, String> formattedNamespaces(Locale locale) {
        Map<String, String> result = new LinkedHashMap<>();
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            result.put(String.valueOf(NAMESPACE_NUMBERS.getOrDefault(namespace.id(), 0)),
                    namespaceLabel(namespace.id(), locale));
        }
        return result;
    }

    /** 小写名称 / 别名 → 命名空间 id（MediaWiki 的 {@code wgNamespaceIds} 风格）。 */
    private Map<String, Integer> namespaceIds(Locale locale) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            int number = NAMESPACE_NUMBERS.getOrDefault(namespace.id(), 0);
            String label = namespaceLabel(namespace.id(), locale);
            if (!label.isEmpty()) {
                result.put(label.toLowerCase(Locale.ROOT).replace(' ', '_'), number);
            }
            for (String prefix : namespace.prefixes()) {
                String name = prefix.endsWith(":") ? prefix.substring(0, prefix.length() - 1) : prefix;
                if (!name.isEmpty()) {
                    result.put(name.toLowerCase(Locale.ROOT).replace(' ', '_'), number);
                }
            }
        }
        return result;
    }

    /** 页面的分类名（不含命名空间前缀）。 */
    private List<String> categories(WikiPage page) {
        if (page == null) {
            return List.of();
        }
        try {
            List<String> result = new ArrayList<>();
            for (WikiLinkScanner.CategoryRef ref
                    : pageCategoryService.categoriesOf(page.getTitle())) {
                String name = Strings.trimToEmpty(ref.title());
                int colon = name.indexOf(':');
                if (colon > 0) {
                    name = name.substring(colon + 1).trim();
                }
                if (!name.isEmpty()) {
                    result.add(name);
                }
            }
            return result;
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /** 当前用户组：{@code *} + MediaWiki 风格名称 + 未映射的原名。 */
    private List<String> groups(WikiUser user) {
        Set<String> result = new LinkedHashSet<>();
        result.add("*");
        if (user == null) {
            return List.copyOf(result);
        }
        result.add("user");
        for (String group : permissionService.namesOf(user.getId())) {
            result.add(MEDIAWIKI_GROUPS.getOrDefault(group, group));
        }
        return List.copyOf(result);
    }

    private static String actionOf(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return "submit";
        }
        String veaction = Strings.trimToEmpty(request.getParameter("veaction"));
        if (!veaction.isEmpty()) {
            return veaction;
        }
        String action = Strings.trimToEmpty(request.getParameter("action"));
        return action.isEmpty() ? "view" : action;
    }

    /** 站点根地址（含端口；反代场景按请求推导）。 */
    private static String serverOf(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private String appVersion() {
        String version = Optional.ofNullable(getClass().getPackage())
                .map(Package::getImplementationVersion)
                .orElse(null);
        return version == null || version.isBlank() ? "WikiKernel" : "WikiKernel " + version;
    }
}
