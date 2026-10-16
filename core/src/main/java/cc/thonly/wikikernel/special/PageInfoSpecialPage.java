package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.PageCategoryService;
import cc.thonly.wikikernel.service.PageLinkService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.time.WikiTime;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code 特殊:页面信息?page=标题} / {@code Special:PageInfo} / 页面上的 {@code ?action=info}：
 * 仿 MediaWiki 的「页面信息」（action=info）——基本信息 / 文件信息 / 历史统计 / 使用的模板与分类，
 * 排版同样用 {@code table.wikitable.mw-page-info} 多张表格。
 */
@Component
public class PageInfoSpecialPage implements SpecialPage {

    public static final String PARAM_PAGE = "page";

    private static final List<String> USER_PREFIXES = List.of("用户:", "User:");

    /** 特殊页前缀（{@code 特殊:} / {@code Special:}）。 */
    private static final List<String> SPECIAL_PREFIXES = List.of("特殊:", "Special:");

    /** 「近期编辑」统计窗口（仿 MediaWiki 的过去 90 天）。 */
    private static final int RECENT_DAYS = 90;

    private final WikiPageService wikiPageService;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final PageLinkService pageLinkService;
    private final UserService userService;
    private final PermissionService permissionService;
    private final AccessControlService accessControlService;
    private final PageCategoryService pageCategoryService;
    private final FileService fileService;
    private final WikiTime wikiTime;
    private final MessageSource messageSource;

    public PageInfoSpecialPage(WikiPageService wikiPageService,
                               WikiRevisionMapper wikiRevisionMapper,
                               PageLinkService pageLinkService,
                               UserService userService,
                               PermissionService permissionService,
                               AccessControlService accessControlService,
                               PageCategoryService pageCategoryService,
                               FileService fileService,
                               WikiTime wikiTime,
                               MessageSource messageSource) {
        this.wikiPageService = wikiPageService;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.pageLinkService = pageLinkService;
        this.userService = userService;
        this.permissionService = permissionService;
        this.accessControlService = accessControlService;
        this.pageCategoryService = pageCategoryService;
        this.fileService = fileService;
        this.wikiTime = wikiTime;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "页面信息";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.pageInfo", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("PageInfo", "Info");
    }

    @Override
    public String template() {
        return "special/page-info";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        // 命名空间别名归一到规范前缀（{@code ?page=User:X} 与 {@code 用户:X} 是同一页）
        String title = WikiTitles.normalize(Strings.trimToEmpty(request.parameters().get(PARAM_PAGE)));
        model.put("target", title);
        if (title.isEmpty()) {
            model.put("pageRequired", true);
            return;
        }
        if (!accessControlService.canRead(title, request.user())) {
            model.put("pageForbidden", true);
            return;
        }

        // 特殊页（没有 WikiPage 记录）：显示基本信息（标题 / 命名空间 / 内容语言 / 链入数）
        if (isSpecialTitle(title)) {
            model.put("title", message("pageInfo.heading", title));
            model.put("basicInfo", specialRows(title));
            model.put("fileInfo", List.of());
            model.put("historyInfo", List.of());
            model.put("extraInfo", List.of());
            return;
        }

        Optional<WikiPage> pageOptional = wikiPageService.getVisibleByTitle(title);
        Optional<WikiFile> fileOptional = FileTitles.isFileTitle(title)
                ? fileService.findByName(FileTitles.fileNameOf(title))
                : Optional.empty();

        if (pageOptional.isPresent()) {
            WikiPage page = pageOptional.get();
            Map<Long, String> userNames = userNamesOfPage(page);
            model.put("title", message("pageInfo.heading", page.getTitle()));
            model.put("basicInfo", basicRows(page));
            model.put("fileInfo", fileOptional.map(file -> fileRows(file, userNames)).orElse(List.of()));
            model.put("historyInfo", historyRows(page, userNames));
            model.put("extraInfo", extraRows(page.getTitle()));
            return;
        }

        if (fileOptional.isPresent()) {
            WikiFile file = fileOptional.get();
            model.put("title", message("pageInfo.heading", FileTitles.titleOf(file.getName())));
            model.put("basicInfo", fileBasicRows(file));
            model.put("fileInfo", fileRows(file, userNamesOf(file.getUploaderId())));
            model.put("historyInfo", List.of());
            model.put("extraInfo", List.of());
            return;
        }

        String username = userTitleName(title);
        if (username != null) {
            model.put("title", message("pageInfo.heading", title));
            Optional<WikiUser> user = userService.findByUsername(username);
            if (user.isPresent()) {
                model.put("user", user.get());
                model.put("userGroups", permissionService.namesOf(user.get().getId()));
                model.put("editCount", wikiRevisionMapper.selectCount(Wrappers.<WikiRevision>lambdaQuery()
                        .eq(WikiRevision::getUserId, user.get().getId())));
            } else {
                model.put("pageNotFound", true);
            }
            return;
        }
        model.put("pageNotFound", true);
    }

    // ==================== 各行数据（仿 MediaWiki action=info） ====================

    /** 第一张表：显示标题 / 排序键 / 长度 / 命名空间 / ID / 内容语言与类型 / 链入数 / 保护状态。 */
    private List<Map<String, String>> basicRows(WikiPage page) {
        String title = page.getTitle();
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row(message("pageInfo.displayTitle"), escape(title)));
        rows.add(row(message("pageInfo.defaultSortKey"), escape(defaultSortKey(title))));
        rows.add(row(message("pageInfo.lengthBytes"), number(content.getBytes(StandardCharsets.UTF_8).length)));
        rows.add(row(message("pageInfo.namespace"), escape(namespaceLabel(title))));
        rows.add(row(message("pageInfo.id"), String.valueOf(page.getId())));
        rows.add(row(message("pageInfo.contentLanguage"), escape(contentLanguage())));
        rows.add(row(message("pageInfo.contentModel"), escape(contentModel(title))));
        rows.add(row(message("pageInfo.contentPage"),
                message(WikiNamespaces.matches(title, WikiNamespaces.MAIN) ? "pageInfo.yes" : "pageInfo.no")));
        rows.add(row(message("pageInfo.linksHere"), link(
                WikiUrls.pagePath("特殊:链入页面") + "?page=" + encode(title),
                String.valueOf(pageLinkService.pagesLinkingTo(title, 1000).size()))));
        rows.add(row(message("pageInfo.protection"),
                message(Boolean.TRUE.equals(page.getProtectedPage())
                        ? "pageInfo.protectionOn" : "pageInfo.protectionOff")));
        return rows;
    }

    /** 特殊页的基本行（特殊页没有版本 / 历史 / 模板，只显示身份与链入数）。 */
    private List<Map<String, String>> specialRows(String title) {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row(message("pageInfo.displayTitle"), escape(title)));
        rows.add(row(message("pageInfo.namespace"), escape(message("pageInfo.namespaceSpecial"))));
        rows.add(row(message("pageInfo.contentLanguage"), escape(contentLanguage())));
        rows.add(row(message("pageInfo.contentModel"), escape(message("pageInfo.contentModelSpecial"))));
        rows.add(row(message("pageInfo.contentPage"), message("pageInfo.no")));
        rows.add(row(message("pageInfo.linksHere"), link(
                WikiUrls.pagePath("特殊:链入页面") + "?page=" + encode(title),
                String.valueOf(pageLinkService.pagesLinkingTo(title, 1000).size()))));
        return rows;
    }

    /** 没有说明页的文件页：基本行只有文件名 / 命名空间 / 内容语言与类型。 */
    private List<Map<String, String>> fileBasicRows(WikiFile file) {        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row(message("pageInfo.displayTitle"), escape(FileTitles.titleOf(file.getName()))));
        rows.add(row(message("pageInfo.namespace"), escape(namespaceLabel(FileTitles.titleOf(file.getName())))));
        rows.add(row(message("pageInfo.contentLanguage"), escape(contentLanguage())));
        rows.add(row(message("pageInfo.contentModel"), "wikitext"));
        return rows;
    }

    /** 文件信息：大小 / 类型 / 当前版本 / 上传者 / 上传日期。 */
    private List<Map<String, String>> fileRows(WikiFile file, Map<Long, String> userNames) {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row(message("pageInfo.fileSize"), number(file.getSize() == null ? 0 : file.getSize())));
        rows.add(row(message("pageInfo.fileType"), escape(Strings.trimToEmpty(file.getContentType()))));
        rows.add(row(message("pageInfo.fileVersion"), String.valueOf(file.getVersion() == null ? 1 : file.getVersion())));
        rows.add(row(message("pageInfo.fileUploader"), userLink(file.getUploaderId(), userNames)));
        rows.add(row(message("pageInfo.fileUploadedAt"), escape(wikiTime.format(file.getCreatedAt()))));
        return rows;
    }

    /** 第二张表：创建者 / 创建日期 / 最后编辑者 / 最后编辑日期 / 编辑数与作者数统计。 */
    private List<Map<String, String>> historyRows(WikiPage page, Map<Long, String> userNames) {
        List<WikiRevision> revisions = wikiPageService.getHistory(page.getId());
        WikiRevision latest = wikiPageService.getLatestRevision(page.getId()).orElse(null);
        WikiRevision first = revisions.isEmpty() ? null : revisions.get(revisions.size() - 1);

        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row(message("pageInfo.creator"), userLink(first == null ? null : first.getUserId(), userNames)));
        rows.add(row(message("pageInfo.creationDate"),
                escape(wikiTime.format(first != null ? first.getCreateAt() : page.getCreateAt()))));
        rows.add(row(message("pageInfo.lastEditor"), userLink(latest == null ? null : latest.getUserId(), userNames)));
        rows.add(row(message("pageInfo.lastEditedAt"),
                escape(wikiTime.format(latest != null ? latest.getCreateAt() : page.getUpdateAt()))));
        rows.add(row(message("pageInfo.totalEdits"), String.valueOf(revisions.size())));
        rows.add(row(message("pageInfo.distinctAuthors"), String.valueOf(distinctAuthors(revisions))));

        LocalDateTime cutoff = LocalDateTime.now().minusDays(RECENT_DAYS);
        List<WikiRevision> recent = revisions.stream()
                .filter(revision -> revision.getCreateAt() != null && !revision.getCreateAt().isBefore(cutoff))
                .toList();
        rows.add(row(message("pageInfo.recentEdits", RECENT_DAYS), String.valueOf(recent.size())));
        rows.add(row(message("pageInfo.recentAuthors"), String.valueOf(distinctAuthors(recent))));
        return rows;
    }

    /** 第三张表：使用的模板（带数量）与所属分类。 */
    private List<Map<String, String>> extraRows(String title) {
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> templates = pageLinkService.templatesUsedBy(title);
        if (!templates.isEmpty()) {
            String links = templates.stream()
                    .map(template -> link(WikiUrls.pagePath(template), template))
                    .collect(Collectors.joining(" "));
            rows.add(row(message("pageInfo.templatesUsed", templates.size()), links));
        }
        List<WikiLinkScanner.CategoryRef> categories = pageCategoryService.categoriesOf(title);
        String categoryLinks = categories.isEmpty()
                ? escape(message("pageInfo.none"))
                : categories.stream()
                        .map(category -> link(WikiUrls.pagePath(category.title()), category.title()))
                        .collect(Collectors.joining(" "));
        rows.add(row(message("pageInfo.categories"), categoryLinks));
        return rows;
    }

    // ==================== 工具 ====================

    private Map<Long, String> userNamesOfPage(WikiPage page) {
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiRevision revision : wikiPageService.getHistory(page.getId())) {
            if (revision.getUserId() != null) {
                ids.add(revision.getUserId());
            }
        }
        return userService.usernames(ids);
    }

    private Map<Long, String> userNamesOf(Long userId) {
        return userId == null ? Map.of() : userService.usernames(List.of(userId));
    }

    /** 不同作者总数：注册用户按 ID 去重；匿名编辑合并为一个（数据库没有存 IP）。 */
    private static int distinctAuthors(List<WikiRevision> revisions) {
        Set<Long> ids = new LinkedHashSet<>();
        boolean anonymous = false;
        for (WikiRevision revision : revisions) {
            if (revision.getUserId() == null) {
                anonymous = true;
            } else {
                ids.add(revision.getUserId());
            }
        }
        return ids.size() + (anonymous ? 1 : 0);
    }

    private String userLink(Long userId, Map<Long, String> userNames) {
        if (userId == null) {
            return escape(message("pageInfo.anonymous"));
        }
        String name = userNames.get(userId);
        if (name == null) {
            return escape(message("pageInfo.anonymous"));
        }
        String contributions = WikiUrls.pagePath("特殊:用户贡献") + "?name=" + encode(name);
        return link(WikiUrls.pagePath("用户:" + name), name)
                + "（" + link(contributions, message("pageInfo.contributions")) + "）";
    }

    /** 命名空间显示名（仿 MediaWiki 的「命名空间」行）。 */
    private String namespaceLabel(String title) {
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            if (WikiNamespaces.matches(title, namespace)) {
                return message(namespace.labelKey());
            }
        }
        return message("namespace.main");
    }

    /** 默认排序键：分类页是分类名，文件页是文件名，其余是页面标题。 */
    private static String defaultSortKey(String title) {
        if (WikiLinkScanner.isCategoryTitle(title)) {
            return WikiLinkScanner.categoryName(title);
        }
        if (FileTitles.isFileTitle(title)) {
            return FileTitles.fileNameOf(title);
        }
        return title;
    }

    /** 页面内容类型（仿 MediaWiki 的内容模型）。 */
    private static String contentModel(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".css")) {
            return "css";
        }
        if (lower.endsWith(".js")) {
            return "javascript";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "typescript";
        }
        if (WikiNamespaces.isWidget(title)) {
            return "html";
        }
        return "wikitext";
    }

    /** 页面内容语言（站点内容就是当前界面语言）。 */
    private static String contentLanguage() {
        Locale locale = LocaleContextHolder.getLocale();
        String language = locale.getLanguage();
        String display = locale.getDisplayLanguage(locale);
        return display == null || display.isEmpty() || display.equals(language)
                ? language : language + " - " + display;
    }

    private String userTitleName(String title) {
        for (String prefix : USER_PREFIXES) {
            if (title.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return title.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** 是否特殊页标题（{@code 特殊:} / {@code Special:}）。 */
    private static boolean isSpecialTitle(String title) {
        for (String prefix : SPECIAL_PREFIXES) {
            if (title.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> row(String label, String html) {
        return Map.of("label", label, "html", html);
    }

    private static String link(String href, String text) {
        return "<a href=\"" + HtmlUtils.htmlEscape(href) + "\">" + HtmlUtils.htmlEscape(text) + "</a>";
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(Strings.trimToEmpty(value));
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String encode(String value) {
        // 查询值里空格用 '+'（服务端按 query 语义解码）；'/' 保持字面量，避免出现 %2F
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%2F", "/");
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
