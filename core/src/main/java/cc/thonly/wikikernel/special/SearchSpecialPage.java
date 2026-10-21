package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.extension.SearchHit;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.search.SearchService;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:搜索} / {@code Special:Search}：仿 MediaWiki 搜索结果页。
 * <p>
 * 四种 profile：{@code default}（内容页面）、{@code images}（多媒体）、
 * {@code all}（全部页面 + 多媒体）、{@code advanced}（命名空间筛选）。
 * 页面结果按「页面标题匹配」和「页面内容匹配」分开展示，和 MediaWiki 的 Search 结果结构一致。
 */
@Component
public class SearchSpecialPage implements SpecialPage {

    private static final String PROFILE_DEFAULT = "default";
    private static final String PROFILE_IMAGES = "images";
    private static final String PROFILE_ALL = "all";
    private static final String PROFILE_ADVANCED = "advanced";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final SearchService searchService;
    private final AccessControlService accessControlService;
    private final FileService fileService;
    private final MessageSource messageSource;

    public SearchSpecialPage(SearchService searchService,
                             AccessControlService accessControlService,
                             FileService fileService,
                             MessageSource messageSource) {
        this.searchService = searchService;
        this.accessControlService = accessControlService;
        this.fileService = fileService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "搜索";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("search.heading", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Search");
    }

    @Override
    public String template() {
        return "special/search";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> params = request.parameters() == null ? Map.of() : request.parameters();
        String q = firstNonBlank(params.get("search"), params.get("q"));
        String profile = normalizeProfile(params.get("profile"));
        int limit = searchLimit(request);
        List<WikiNamespaces.Namespace> selectedNamespaces = selectedNamespaces(params, profile);

        // default / advanced 会在取回后按命名空间过滤，因此多取一些避免主命名空间结果被挤掉。
        int fetchLimit = profile.equals(PROFILE_ADVANCED) || profile.equals(PROFILE_DEFAULT)
                ? Math.max(limit * 3, 60) : limit;
        List<SearchHit> pageHits = q.isEmpty() || profile.equals(PROFILE_IMAGES)
                ? List.of()
                : filterNamespaces(searchService.search(q, fetchLimit), selectedNamespaces);
        pageHits = pageHits.stream().limit(limit).toList();

        List<SearchHit> titleHits = pageHits.stream()
                .filter(hit -> containsIgnoreCase(hit.title(), q))
                .limit(limit)
                .toList();
        List<SearchHit> contentHits = pageHits.stream()
                .filter(hit -> containsIgnoreCase(hit.snippet(), q))
                .limit(limit)
                .toList();

        List<Map<String, Object>> fileHits = List.of();
        if (q.isEmpty() || !(profile.equals(PROFILE_IMAGES) || profile.equals(PROFILE_ALL))) {
            model.put("fileHits", fileHits);
        } else {
            List<WikiFile> readable = accessControlService.filterReadable(
                    fileService.all(), file -> "文件:" + file.getName(), request.user());
            fileHits = readable.stream()
                    .filter(file -> containsIgnoreCase(file.getName(), q)
                            || containsIgnoreCase(file.getDescription(), q))
                    .limit(limit)
                    .map(this::fileHit)
                    .toList();
            model.put("fileHits", fileHits);
        }

        model.put("q", q);
        model.put("profile", profile);
        model.put("titleHits", titleHits);
        model.put("contentHits", contentHits);
        model.put("resultCount", pageHits.size() + fileHits.size());
        model.put("searchPerformed", !q.isEmpty());
        model.put("advanced", PROFILE_ADVANCED.equals(profile));
        model.put("namespaceOptions", namespaceOptions(selectedNamespaces));
        model.put("selectedNamespaces", selectedNamespaces.stream()
                .map(WikiNamespaces.Namespace::id).toList());
    }

    private List<SearchHit> filterNamespaces(List<SearchHit> hits, List<WikiNamespaces.Namespace> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return hits;
        }
        return hits.stream()
                .filter(hit -> namespaces.stream().anyMatch(ns -> WikiNamespaces.matches(hit.title(), ns)))
                .toList();
    }

    /** profile=default 默认只查主命名空间；advanced 用 ns-* 复选框；all 不筛选。 */
    private static List<WikiNamespaces.Namespace> selectedNamespaces(Map<String, String> params,
                                                                     String profile) {
        if (PROFILE_ALL.equals(profile) || PROFILE_IMAGES.equals(profile)) {
            return List.of();
        }
        if (PROFILE_ADVANCED.equals(profile)) {
            Set<String> selected = new LinkedHashSet<>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey().startsWith("ns-") && truthy(entry.getValue())) {
                    selected.add(entry.getKey().substring(3));
                }
            }
            if (selected.isEmpty()) {
                selected.add(WikiNamespaces.MAIN.id());
            }
            List<WikiNamespaces.Namespace> result = new ArrayList<>();
            for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
                if (selected.contains(namespace.id())) {
                    result.add(namespace);
                }
            }
            return result;
        }
        // 内容页面：和 MediaWiki 默认 profile 一样，只搜内容（主）命名空间
        return List.of(WikiNamespaces.MAIN);
    }

    private List<Map<String, Object>> namespaceOptions(List<WikiNamespaces.Namespace> selected) {
        Set<String> ids = selected.stream().map(WikiNamespaces.Namespace::id).collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> options = new ArrayList<>();
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", namespace.id());
            row.put("name", "ns-" + namespace.id());
            row.put("label", message(namespace.labelKey(), namespace.id()));
            row.put("selected", ids.contains(namespace.id()));
            options.add(row);
        }
        return options;
    }

    private Map<String, Object> fileHit(WikiFile file) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", file.getName());
        row.put("pageUrl", fileService.pageUrlOf(file));
        row.put("url", fileService.urlOf(file));
        row.put("thumbnailUrl", fileService.thumbnailUrl(file, 120));
        row.put("image", fileService.isImage(file));
        row.put("description", Strings.trimToEmpty(file.getDescription()));
        row.put("kind", fileService.mediaKind(file));
        row.put("sizeText", sizeText(file.getSize()));
        return row;
    }

    private int searchLimit(SpecialPageRequest request) {
        int value = request.user() != null && request.user().getSearchLimit() != null
                ? request.user().getSearchLimit() : 0;
        if (value <= 0) {
            value = DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, value));
    }

    private static String normalizeProfile(String raw) {
        String profile = Strings.trimToEmpty(raw).toLowerCase(Locale.ROOT);
        return switch (profile) {
            case PROFILE_IMAGES, PROFILE_ALL, PROFILE_ADVANCED -> profile;
            default -> PROFILE_DEFAULT;
        };
    }

    private static boolean truthy(String value) {
        String normalized = Strings.trimToEmpty(value).toLowerCase(Locale.ROOT);
        return "1".equals(normalized) || "true".equals(normalized)
                || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && keyword != null
                && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private static String firstNonBlank(String first, String second) {
        String value = Strings.trimToEmpty(first);
        return value.isEmpty() ? Strings.trimToEmpty(second) : value;
    }

    private String message(String key, String fallback) {
        return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    private static String sizeText(Long bytes) {
        long size = bytes == null ? 0 : bytes;
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0));
    }
}
