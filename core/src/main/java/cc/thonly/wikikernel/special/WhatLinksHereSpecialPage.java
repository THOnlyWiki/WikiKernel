package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PageLinkService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.wikitext.WikiRedirect;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code 特殊:链入页面?page=标题} / {@code Special:WhatLinksHere}（仿 MediaWiki）：
 * 页面 / 命名空间筛选、隐藏嵌入 / 链入 / 重定向、统计与翻页；
 * 列表项带「（嵌入）」「（重定向页面）」标记和「（←链接）」工具链接。
 */
@Component
public class WhatLinksHereSpecialPage implements SpecialPage {

    public static final String PARAM_PAGE = "page";

    /** MediaWiki 兼容的别名参数（{@code Special:WhatLinksHere?target=}）。 */
    public static final String PARAM_TARGET = "target";

    /** 一次扫描 / 汇总的最大条目数。 */
    private static final int MAX_ITEMS = 2000;

    /** 分页可选条数（仿 MediaWiki）。 */
    private static final List<Integer> LIMIT_OPTIONS = List.of(20, 50, 100, 250, 500);

    private static final int DEFAULT_LIMIT = 50;

    private final PageLinkService pageLinkService;
    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public WhatLinksHereSpecialPage(PageLinkService pageLinkService,
                                    WikiPageService wikiPageService,
                                    AccessControlService accessControlService,
                                    MessageSource messageSource) {
        this.pageLinkService = pageLinkService;
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "链入页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.whatLinksHere", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("WhatLinksHere");
    }

    @Override
    public String template() {
        return "special/what-links-here";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> parameters = request.parameters();
        String rawTarget = parameters.containsKey(PARAM_PAGE)
                ? parameters.get(PARAM_PAGE) : parameters.get(PARAM_TARGET);
        String target = WikiTitles.normalize(Strings.trimToEmpty(rawTarget));

        boolean hideTrans = isOn(parameters.get("hidetrans"));
        boolean hideLinks = isOn(parameters.get("hidelinks"));
        boolean hideRedirects = isOn(parameters.get("hideredirs"));
        String namespaceId = Strings.trimToEmpty(parameters.get("namespace"));
        int limit = parseLimit(parameters.get("limit"));

        model.put("target", target);
        model.put("formAction", WikiUrls.pagePath("特殊:链入页面"));
        model.put("hideTrans", hideTrans);
        model.put("hideLinks", hideLinks);
        model.put("hideRedirects", hideRedirects);
        model.put("namespaceOptions", namespaceOptions(namespaceId));
        model.put("limitOptions", limitOptions(limit, target, namespaceId, hideTrans, hideLinks, hideRedirects));
        model.put("limit", limit);
        if (target.isEmpty()) {
            return; // 只显示表单
        }
        model.put("title", message("whatLinksHere.title", target));

        // 汇总：嵌入（模板反查）∪ 链入（最新正文 [[target]]）——同页合并为一行
        Map<String, LinkRow> rows = new LinkedHashMap<>();
        for (WikiPage page : readablePages(
                pageLinkService.pagesTranscluding(target, MAX_ITEMS), request)) {
            rowOf(rows, page).transclusion = true;
        }
        for (WikiPage page : readablePages(
                pageLinkService.pagesLinkingTo(target, MAX_ITEMS), request)) {
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            if (!linksTo(content, target)) {
                continue; // 精确匹配，排除 [[首页/子页]] 这类前缀误报
            }
            LinkRow row = rowOf(rows, page);
            row.link = true;
            if (isRedirectTo(content, target)) {
                row.redirect = true;
            }
        }

        // 筛选：隐藏嵌入 / 隐藏链入 / 隐藏重定向 + 命名空间
        List<LinkRow> filtered = rows.values().stream()
                .filter(row -> visible(row, hideTrans, hideLinks, hideRedirects))
                .filter(row -> namespaceId.isEmpty()
                        || WikiNamespaces.byId(namespaceId)
                                .map(namespace -> WikiNamespaces.matches(row.title, namespace))
                                .orElse(true))
                .sorted(Comparator.comparing(row -> row.title))
                .toList();

        PageWindow window = windowOf(filtered, parameters, limit);
        model.put("total", filtered.size());
        model.put("rows", rowsOf(window.rows()));
        model.put("prevUrl", window.start() > 0
                ? pageUrl(target, namespaceId, hideTrans, hideLinks, hideRedirects, limit,
                        filtered.get(window.start()).title, "prev")
                : null);
        model.put("nextUrl", window.end() < filtered.size()
                ? pageUrl(target, namespaceId, hideTrans, hideLinks, hideRedirects, limit,
                        filtered.get(window.end() - 1).title, "next")
                : null);
    }

    // ==================== 分页与链接 ====================

    /** 当前分页窗口：{@code [start, end)} 以及窗口内的条目。 */
    private record PageWindow(List<LinkRow> rows, int start, int end) {
    }

    /** 按 limit / from / dir 切出当前窗口（from + dir=prev 表示上一页的游标）。 */
    private PageWindow windowOf(List<LinkRow> filtered, Map<String, String> parameters, int limit) {
        String from = WikiTitles.normalize(Strings.trimToEmpty(parameters.get("from")));
        boolean previous = "prev".equalsIgnoreCase(Strings.trimToEmpty(parameters.get("dir")));
        int total = filtered.size();
        int start;
        int end;
        if (from.isEmpty()) {
            start = 0;
        } else {
            int index = indexOf(filtered, from);
            if (previous) {
                end = index < 0 ? total : index;
                start = Math.max(0, end - limit);
                return new PageWindow(List.copyOf(filtered.subList(start, end)), start, end);
            }
            start = index < 0 ? 0 : index + 1;
        }
        end = Math.min(total, start + limit);
        return new PageWindow(List.copyOf(filtered.subList(start, end)), start, end);
    }

    private static int indexOf(List<LinkRow> rows, String title) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).title.equals(title)) {
                return i;
            }
        }
        return -1;
    }

    private List<Map<String, Object>> rowsOf(List<LinkRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LinkRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", row.title);
            item.put("url", WikiUrls.pagePath(row.title) + (row.redirect ? "?redirect=no" : ""));
            item.put("class", row.redirect ? "mw-redirect" : "");
            item.put("redirect", row.redirect);
            item.put("transclusion", row.transclusion);
            item.put("toolsUrl", WikiUrls.pagePath("特殊:链入页面") + "?page=" + encode(row.title));
            result.add(item);
        }
        return result;
    }

    /** 翻页 / 限制条数的链接（保留筛选参数）。 */
    private String pageUrl(String target, String namespaceId, boolean hideTrans, boolean hideLinks,
                           boolean hideRedirects, int limit, String from, String dir) {
        StringBuilder url = new StringBuilder(WikiUrls.pagePath("特殊:链入页面"))
                .append("?page=").append(encode(target));
        if (!namespaceId.isEmpty()) {
            url.append("&namespace=").append(encode(namespaceId));
        }
        if (hideTrans) {
            url.append("&hidetrans=1");
        }
        if (hideLinks) {
            url.append("&hidelinks=1");
        }
        if (hideRedirects) {
            url.append("&hideredirs=1");
        }
        if (limit != DEFAULT_LIMIT) {
            url.append("&limit=").append(limit);
        }
        if (from != null && !from.isEmpty()) {
            url.append("&from=").append(encode(from));
        }
        if (dir != null && !dir.isEmpty()) {
            url.append("&dir=").append(dir);
        }
        return url.toString();
    }

    /** 条数选项链接（重置游标）。 */
    private List<Map<String, Object>> limitOptions(int selected, String target, String namespaceId,
                                                   boolean hideTrans, boolean hideLinks, boolean hideRedirects) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (int value : LIMIT_OPTIONS) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("value", value);
            option.put("selected", value == selected);
            option.put("url", pageUrl(target, namespaceId, hideTrans, hideLinks, hideRedirects, value, "", ""));
            options.add(option);
        }
        return options;
    }

    /** 命名空间下拉：所有（空 id）+ 全部命名空间。 */
    private List<Map<String, Object>> namespaceOptions(String selectedId) {
        List<Map<String, Object>> options = new ArrayList<>();
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("id", "");
        all.put("label", message("whatLinksHere.namespaceAll"));
        all.put("selected", selectedId.isEmpty());
        options.add(all);
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", namespace.id());
            option.put("label", message(namespace.labelKey()));
            option.put("selected", namespace.id().equals(selectedId));
            options.add(option);
        }
        return options;
    }

    // ==================== 判定 ====================

    private static boolean isOn(String value) {
        String text = Strings.trimToEmpty(value);
        return !text.isEmpty() && !"0".equals(text) && !"false".equalsIgnoreCase(text);
    }

    private static int parseLimit(String value) {
        try {
            int parsed = Integer.parseInt(Strings.trimToEmpty(value));
            return parsed >= 1 && parsed <= 500 ? parsed : DEFAULT_LIMIT;
        } catch (NumberFormatException exception) {
            return DEFAULT_LIMIT;
        }
    }

    /** 一行 = 一个来源页面（可能同时是链入 / 嵌入 / 重定向）。 */
    private static final class LinkRow {
        private final String title;
        private boolean link;
        private boolean transclusion;
        private boolean redirect;

        private LinkRow(String title) {
            this.title = title;
        }
    }

    private static LinkRow rowOf(Map<String, LinkRow> rows, WikiPage page) {
        return rows.computeIfAbsent(page.getTitle(), LinkRow::new);
    }

    /** 可见性（仿 MediaWiki 的三组筛选）：重定向只看「隐藏重定向」；普通链入看「隐藏链入」；仅嵌入看「隐藏嵌入」。 */
    private static boolean visible(LinkRow row, boolean hideTrans, boolean hideLinks, boolean hideRedirects) {
        if (row.redirect) {
            return !hideRedirects;
        }
        if (row.link && !hideLinks) {
            return true;
        }
        return row.transclusion && !hideTrans;
    }

    /** 正文是否真的链接到该标题（排除前缀相同的子页面等误报）。 */
    private static boolean linksTo(String content, String target) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        Pattern pattern = Pattern.compile("\\[\\[\\s*" + Pattern.quote(target) + "\\s*(\\||#|]])");
        return pattern.matcher(content).find();
    }

    private static boolean isRedirectTo(String content, String target) {
        return WikiRedirect.targetOf(content)
                .map(WikiTitles::normalize)
                .filter(target::equals)
                .isPresent();
    }

    private List<WikiPage> readablePages(List<WikiPage> pages, SpecialPageRequest request) {
        return accessControlService.filterReadable(pages, WikiPage::getTitle, request.user());
    }

    // ==================== 工具 ====================

    private static String encode(String value) {
        // 查询值里空格用 '+'（服务端按 query 语义解码）；'/' 保持字面量，避免出现 %2F
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%2F", "/");
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
