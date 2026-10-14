package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPageCategory;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageCategoryMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.PageCategoryService;
import cc.thonly.wikikernel.service.PageLinkService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分类索引实现：索引按“渲染结果”（模板展开后）重建，模板里的 {@code [[分类:X]]} 也会归属到嵌入页面。
 * <p>
 * 启动时全量重建（@{@code @Order(4)}，在 DDL / 迁移 / 设置之后），保证旧数据与模板改动后的依赖页一致。
 */
@Service
@Order(4)
public class PageCategoryServiceImpl implements PageCategoryService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PageCategoryServiceImpl.class);

    /**
     * 启动时全量重建的页面上限。
     */
    private static final int BACKFILL_LIMIT = 2000;
    /**
     * 模板修改时重建的依赖页上限。
     */
    private static final int DEPENDENT_LIMIT = 200;

    private final WikiPageCategoryMapper categoryMapper;
    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;
    private final WikitextRenderer wikitextRenderer;
    private final PageLinkService pageLinkService;

    public PageCategoryServiceImpl(WikiPageCategoryMapper categoryMapper,
                                   WikiPageMapper wikiPageMapper,
                                   WikiPageService wikiPageService,
                                   WikitextRenderer wikitextRenderer,
                                   PageLinkService pageLinkService) {
        this.categoryMapper = categoryMapper;
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
        this.wikitextRenderer = wikitextRenderer;
        this.pageLinkService = pageLinkService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        backfill();
    }

    @Override
    @Transactional
    public void reindex(long pageId) {
        WikiPage page = wikiPageMapper.selectById(pageId);
        if (page == null || Boolean.TRUE.equals(page.getDeleted())) {
            clear(pageId);
            return;
        }
        String content = wikiPageService.getLatestContent(pageId).orElse("");
        List<WikiLinkScanner.CategoryRef> refs = wikitextRenderer.renderWithMeta(content, page.getTitle())
                .categories();
        categoryMapper.delete(Wrappers.<WikiPageCategory>lambdaQuery()
                .eq(WikiPageCategory::getPageId, pageId));
        Set<String> seen = new LinkedHashSet<>();
        for (WikiLinkScanner.CategoryRef ref : refs) {
            if (seen.add(ref.title())) {
                categoryMapper.insert(new WikiPageCategory(null, pageId, ref.title(), ref.sortKey()));
            }
        }
    }

    @Override
    @Transactional
    public void reindex(String title) {
        wikiPageService.getByTitle(Strings.trimToEmpty(title))
                .ifPresent(page -> reindex(page.getId()));
    }

    @Override
    @Transactional
    public void clear(long pageId) {
        categoryMapper.delete(Wrappers.<WikiPageCategory>lambdaQuery()
                .eq(WikiPageCategory::getPageId, pageId));
    }

    @Override
    @Transactional
    public void reindexDependents(String templateTitle) {
        for (WikiPage page : pageLinkService.pagesTranscluding(templateTitle, DEPENDENT_LIMIT)) {
            reindex(page.getId());
        }
    }

    @Override
    public List<Member> members(String categoryTitle, int limit) {
        String canonical = WikiLinkScanner.canonicalCategoryTitle(categoryTitle);
        if (limit <= 0) {
            return List.of();
        }
        List<WikiPageCategory> rows = categoryMapper.selectList(Wrappers.<WikiPageCategory>lambdaQuery()
                .eq(WikiPageCategory::getCategoryTitle, canonical)
                .last("LIMIT " + (limit * 3)));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> pageIds = rows.stream().map(WikiPageCategory::getPageId).distinct().toList();
        Map<Long, WikiPage> pageById = new LinkedHashMap<>();
        for (WikiPage page : wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .in(WikiPage::getId, pageIds)
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false))) {
            pageById.put(page.getId(), page);
        }
        List<Member> members = new ArrayList<>();
        for (WikiPageCategory row : rows) {
            WikiPage page = pageById.get(row.getPageId());
            if (page != null) {
                members.add(new Member(page, Strings.trimToEmpty(row.getSortKey())));
            }
        }
        members.sort(Comparator
                .comparing((Member member) -> member.sortKey().isEmpty()
                        ? member.page().getTitle() : member.sortKey(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(member -> member.page().getTitle(), String.CASE_INSENSITIVE_ORDER));
        return members.size() > limit ? members.subList(0, limit) : members;
    }

    @Override
    public List<WikiLinkScanner.CategoryRef> categoriesOf(String pageTitle) {
        return wikiPageService.getVisibleByTitle(Strings.trimToEmpty(pageTitle))
                .map(page -> categoryMapper.selectList(Wrappers.<WikiPageCategory>lambdaQuery()
                                .eq(WikiPageCategory::getPageId, page.getId()))
                        .stream()
                        .map(row -> new WikiLinkScanner.CategoryRef(row.getCategoryTitle(),
                                row.getSortKey()))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<CategoryCount> allCategories(int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WikiPageCategory row : categoryMapper.selectList(null)) {
            counts.merge(row.getCategoryTitle(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(1, limit))
                .map(entry -> new CategoryCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 启动时全量重建：让旧数据与模板里的分类都进入索引。
     */
    @Transactional
    protected void backfill() {
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .orderByAsc(WikiPage::getId)
                .last("LIMIT " + BACKFILL_LIMIT));
        for (WikiPage page : pages) {
            try {
                reindex(page.getId());
            } catch (RuntimeException exception) {
                log.warn("重建页面分类索引失败: {}", page.getTitle(), exception);
            }
        }
        log.info("分类索引重建完成，共 {} 个页面", pages.size());
    }
}
