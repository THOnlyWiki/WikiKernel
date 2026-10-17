package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPageCategory;
import cc.thonly.wikikernel.common.service.PageSearchStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageCategoryMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import cc.thonly.wikikernel.wikitext.WikiRedirect;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@link PageSearchStore} 的 core 实现：把 DPL 需要的「分类 / 命名空间 / 标题 / 排序」翻译成页面表查询。
 * <p>
 * 先按标题前缀 / 包含做 SQL 过滤并取一批候选（上限 {@value #SCAN_LIMIT}），再在内存里做分类、正则、
 * 重定向与命名空间过滤。这样 SQLite / MySQL / H2 共用同一套语义，插件也不需要知道表结构。
 */
@Service
public class PageSearchStoreImpl implements PageSearchStore {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 500;
    private static final int SCAN_LIMIT = 10_000;

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageCategoryMapper categoryMapper;
    private final WikiPageService wikiPageService;

    public PageSearchStoreImpl(WikiPageMapper wikiPageMapper,
                               WikiPageCategoryMapper categoryMapper,
                               WikiPageService wikiPageService) {
        this.wikiPageMapper = wikiPageMapper;
        this.categoryMapper = categoryMapper;
        this.wikiPageService = wikiPageService;
    }

    @Override
    public List<PageRow> query(PageQuery query) {
        if (query == null) {
            return List.of();
        }
        int limit = Math.min(MAX_LIMIT, Math.max(1, query.limit() <= 0 ? DEFAULT_LIMIT : query.limit()));
        int offset = Math.max(0, query.offset());
        String prefix = Strings.trimToEmpty(query.titlePrefix());
        String contains = Strings.trimToEmpty(query.titleContains());
        Pattern regex = compile(query.titleRegex());
        Pattern notRegex = compile(query.titleNotRegex());

        var wrapper = Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false);
        if (!prefix.isEmpty()) {
            wrapper.likeRight(WikiPage::getTitle, prefix);
        }
        if (!contains.isEmpty()) {
            wrapper.like(WikiPage::getTitle, contains);
        }
        order(wrapper, query.orderMethod(), query.descending());
        wrapper.last("LIMIT " + SCAN_LIMIT);
        List<WikiPage> pages = wikiPageMapper.selectList(wrapper);

        Set<Long> included = categoryIntersection(query.categories());
        if (included != null && included.isEmpty()) {
            return List.of();
        }
        Set<Long> excluded = categoryUnion(query.notCategories());
        WikiNamespaces.Namespace namespace = resolveNamespace(query.namespace());
        String redirectMode = Strings.trimToEmpty(query.redirects()).toLowerCase(Locale.ROOT);

        List<PageRow> rows = new ArrayList<>();
        int skipped = 0;
        for (WikiPage page : pages) {
            String title = Strings.trimToEmpty(page.getTitle());
            if (!namespaceMatches(title, namespace)) {
                continue;
            }
            if (included != null && !included.contains(page.getId())) {
                continue;
            }
            if (excluded.contains(page.getId())) {
                continue;
            }
            if (!contains.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (regex != null && !regex.matcher(title).find()) {
                continue;
            }
            if (notRegex != null && notRegex.matcher(title).find()) {
                continue;
            }
            if (!redirectMatches(page, redirectMode)) {
                continue;
            }
            if (skipped < offset) {
                skipped++;
                continue;
            }
            rows.add(new PageRow(page.getId(), title, page.getLatestRevisionId(),
                    page.getCreateAt(), page.getUpdateAt()));
            if (rows.size() >= limit) {
                break;
            }
        }
        return List.copyOf(rows);
    }

    private static void order(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WikiPage> wrapper,
                              String method, boolean descending) {
        String value = Strings.trimToEmpty(method).toLowerCase(Locale.ROOT);
        switch (value) {
            case "firstedit", "created" -> {
                if (descending) {
                    wrapper.orderByDesc(WikiPage::getCreateAt);
                } else {
                    wrapper.orderByAsc(WikiPage::getCreateAt);
                }
            }
            case "lastedit", "pagetouched", "updated" -> {
                if (descending) {
                    wrapper.orderByDesc(WikiPage::getUpdateAt);
                } else {
                    wrapper.orderByAsc(WikiPage::getUpdateAt);
                }
            }
            case "none" -> {
                if (descending) {
                    wrapper.orderByDesc(WikiPage::getId);
                } else {
                    wrapper.orderByAsc(WikiPage::getId);
                }
            }
            default -> {
                if (descending) {
                    wrapper.orderByDesc(WikiPage::getTitle);
                } else {
                    wrapper.orderByAsc(WikiPage::getTitle);
                }
            }
        }
    }

    /** 分类 AND：任一分类不存在 / 没有页面时返回空集合。 */
    private Set<Long> categoryIntersection(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return null;
        }
        Set<Long> result = null;
        for (String category : categories) {
            Set<Long> members = categoryMembers(category);
            if (result == null) {
                result = new HashSet<>(members);
            } else {
                result.retainAll(members);
            }
            if (result.isEmpty()) {
                return Set.of();
            }
        }
        return result;
    }

    /** 分类 NOT：任一命中即排除。 */
    private Set<Long> categoryUnion(List<String> categories) {
        Set<Long> result = new HashSet<>();
        if (categories != null) {
            for (String category : categories) {
                result.addAll(categoryMembers(category));
            }
        }
        return result;
    }

    private Set<Long> categoryMembers(String category) {
        String raw = Strings.trimToEmpty(category);
        String name = WikiLinkScanner.categoryName(raw);
        String canonical = name == null
                ? WikiLinkScanner.CATEGORY_PREFIX + raw
                : WikiLinkScanner.CATEGORY_PREFIX + name;
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiPageCategory row : categoryMapper.selectList(Wrappers.<WikiPageCategory>lambdaQuery()
                .eq(WikiPageCategory::getCategoryTitle, canonical))) {
            ids.add(row.getPageId());
        }
        return ids;
    }

    private boolean redirectMatches(WikiPage page, String mode) {
        if (!"exclude".equals(mode) && !"only".equals(mode)) {
            return true;
        }
        boolean redirect = wikiPageService.getLatestContent(page.getId())
                .flatMap(WikiRedirect::targetOf)
                .isPresent();
        return "only".equals(mode) ? redirect : !redirect;
    }

    private static boolean namespaceMatches(String title, WikiNamespaces.Namespace namespace) {
        return namespace == null || WikiNamespaces.matches(title, namespace);
    }

    /** 空白 / {@code main} = 主命名空间；{@code all} = 不筛选；stable id / 前缀均可。 */
    private static WikiNamespaces.Namespace resolveNamespace(String namespace) {
        String value = Strings.trimToEmpty(namespace);
        if (value.isEmpty() || "main".equalsIgnoreCase(value)) {
            return WikiNamespaces.MAIN;
        }
        if ("all".equalsIgnoreCase(value)) {
            return null;
        }
        Optional<WikiNamespaces.Namespace> byId = WikiNamespaces.byId(value);
        if (byId.isPresent()) {
            return byId.get();
        }
        String normalized = value.endsWith(":") ? value.substring(0, value.length() - 1) : value;
        for (WikiNamespaces.Namespace candidate : WikiNamespaces.all()) {
            for (String prefix : candidate.prefixes()) {
                String name = prefix.endsWith(":") ? prefix.substring(0, prefix.length() - 1) : prefix;
                if (name.equalsIgnoreCase(normalized)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Pattern compile(String regex) {
        String value = Strings.trimToEmpty(regex);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(value, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException exception) {
            return null;
        }
    }
}
