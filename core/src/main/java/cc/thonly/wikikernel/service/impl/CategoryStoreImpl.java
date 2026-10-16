package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPageCategory;
import cc.thonly.wikikernel.common.service.CategoryCounts;
import cc.thonly.wikikernel.common.service.CategoryMember;
import cc.thonly.wikikernel.common.service.CategoryStore;
import cc.thonly.wikikernel.db.mapper.WikiPageCategoryMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分类索引访问（{@code wiki_page_category}）的插件桥接实现：
 * 给 CategoryTree 这类插件提供分类成员 / 所属分类 / 成员统计。
 */
@Service
public class CategoryStoreImpl implements CategoryStore {

    /** WikiKernel 命名空间 id → MediaWiki 风格编号。 */
    private static final Map<String, Integer> NAMESPACE_IDS = Map.ofEntries(
            Map.entry("main", 0),
            Map.entry("talk", 1),
            Map.entry("user", 2),
            Map.entry("user-talk", 3),
            Map.entry("wikikernel", 4),
            Map.entry("wikikernel-talk", 5),
            Map.entry("file", 6),
            Map.entry("file-talk", 7),
            Map.entry("template", 10),
            Map.entry("template-talk", 11),
            Map.entry("help", 12),
            Map.entry("help-talk", 13),
            Map.entry("category", 14),
            Map.entry("category-talk", 15),
            Map.entry("widget", 274),
            Map.entry("widget-talk", 275),
            Map.entry("gadget", 2300),
            Map.entry("gadget-talk", 2301),
            Map.entry("module", 828),
            Map.entry("module-talk", 829),
            Map.entry("graph", 484),
            Map.entry("component", 486),
            Map.entry("graph-talk", 485));

    private final WikiPageCategoryMapper categoryMapper;
    private final WikiPageMapper pageMapper;

    public CategoryStoreImpl(WikiPageCategoryMapper categoryMapper, WikiPageMapper pageMapper) {
        this.categoryMapper = categoryMapper;
        this.pageMapper = pageMapper;
    }

    @Override
    public List<CategoryMember> members(String categoryTitle, int limit) {
        String canonical = canonicalCategory(categoryTitle);
        if (canonical.isEmpty()) {
            return List.of();
        }
        var query = Wrappers.<WikiPageCategory>lambdaQuery()
                .eq(WikiPageCategory::getCategoryTitle, canonical)
                .orderByAsc(WikiPageCategory::getSortKey)
                .orderByAsc(WikiPageCategory::getPageId);
        if (limit > 0) {
            query.last("LIMIT " + limit);
        }
        List<WikiPageCategory> rows = categoryMapper.selectList(query);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, WikiPage> pages = new LinkedHashMap<>();
        List<Long> pageIds = rows.stream().map(WikiPageCategory::getPageId).distinct().toList();
        for (WikiPage page : pageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .in(WikiPage::getId, pageIds))) {
            pages.put(page.getId(), page);
        }
        List<CategoryMember> members = new ArrayList<>();
        for (WikiPageCategory row : rows) {
            WikiPage page = pages.get(row.getPageId());
            if (page == null || Boolean.TRUE.equals(page.getDeleted())) {
                continue;
            }
            members.add(new CategoryMember(page.getId(), page.getTitle(),
                    row.getSortKey(), namespaceIdOf(page.getTitle())));
        }
        return List.copyOf(members);
    }

    @Override
    public List<String> categoriesOf(long pageId) {
        List<WikiPageCategory> rows = categoryMapper.selectList(Wrappers.<WikiPageCategory>lambdaQuery()
                .eq(WikiPageCategory::getPageId, pageId)
                .orderByAsc(WikiPageCategory::getId));
        List<String> titles = new ArrayList<>();
        for (WikiPageCategory row : rows) {
            if (row.getCategoryTitle() != null && !row.getCategoryTitle().isBlank()
                    && !titles.contains(row.getCategoryTitle())) {
                titles.add(row.getCategoryTitle());
            }
        }
        return List.copyOf(titles);
    }

    @Override
    public CategoryCounts counts(String categoryTitle) {
        List<CategoryMember> all = members(categoryTitle, 0);
        int subcategories = 0;
        int pages = 0;
        int files = 0;
        for (CategoryMember member : all) {
            if (member.subcategory()) {
                subcategories++;
            } else if (member.file()) {
                files++;
            } else if (member.contentPage()) {
                pages++;
            }
        }
        return new CategoryCounts(subcategories, pages, files, all.size());
    }

    /** 分类标题规范化：`分类:X` / `Category:X` / 裸名 `X` 都归一到 `分类:X`。 */
    static String canonicalCategory(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) {
            return "";
        }
        String name = WikiLinkScanner.categoryName(value);
        return WikiLinkScanner.CATEGORY_PREFIX + (name == null ? value : name);
    }

    /** 标题的 MediaWiki 风格命名空间编号（中英文前缀都识别，未识别按主命名空间 0）。 */
    static int namespaceIdOf(String title) {
        String value = title == null ? "" : title;
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            for (String prefix : namespace.prefixes()) {
                if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    Integer id = NAMESPACE_IDS.get(namespace.id().toLowerCase(Locale.ROOT));
                    return id == null ? 0 : id;
                }
            }
        }
        return 0;
    }
}
