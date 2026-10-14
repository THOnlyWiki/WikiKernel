package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPageTemplate;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageTemplateMapper;
import cc.thonly.wikikernel.service.PageTemplateService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import cc.thonly.wikikernel.wikitext.WikitextProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模板使用索引实现（{@code wiki_page_template}）：
 * <ul>
 *     <li>保存 / 删除时事件驱动<b>实时重建</b>（{@code PageTemplateEventListener}）；</li>
 *     <li>启动时全量回填（{@code @Order(5)}，在 DDL / 迁移 / 设置 / 分类索引之后）；</li>
 *     <li>反查时用索引找候选，再对候选页面的<b>最新版本正文做一次实时扫描核对</b>，索引过期行不会输出。</li>
 * </ul>
 * 模板调用是源码级直接使用（不做模板展开）；正查见 {@code PageLinkService#templatesUsedBy}。
 */
@Service
@Order(5)
public class PageTemplateServiceImpl implements PageTemplateService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PageTemplateServiceImpl.class);

    /** 启动时全量回填的页面上限。 */
    private static final int BACKFILL_LIMIT = 2000;

    /** 索引查询的候选上限倍率：多取一些，实时核对后按 limit 截断。 */
    private static final int CANDIDATE_LIMIT_FACTOR = 5;

    private final WikiPageTemplateMapper templateMapper;
    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;
    private final WikitextProperties wikitextProperties;

    public PageTemplateServiceImpl(WikiPageTemplateMapper templateMapper,
                                   WikiPageMapper wikiPageMapper,
                                   WikiPageService wikiPageService,
                                   WikitextProperties wikitextProperties) {
        this.templateMapper = templateMapper;
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
        this.wikitextProperties = wikitextProperties;
    }

    @Override
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
        Set<String> titles = new LinkedHashSet<>();
        for (WikiLinkScanner.TemplateCall call : WikiLinkScanner.templateCalls(content)) {
            String title = WikiLinkScanner.canonicalTemplateTitle(call.name(),
                    wikitextProperties.getTemplatePrefixes());
            if (!title.isEmpty()) {
                titles.add(title);
            }
        }
        templateMapper.delete(Wrappers.<WikiPageTemplate>lambdaQuery()
                .eq(WikiPageTemplate::getPageId, pageId));
        for (String title : titles) {
            templateMapper.insert(new WikiPageTemplate(null, pageId, title));
        }
    }

    @Override
    public void reindex(String title) {
        wikiPageService.getByTitle(Strings.trimToEmpty(title))
                .ifPresent(page -> reindex(page.getId()));
    }

    @Override
    @Transactional
    public void clear(long pageId) {
        templateMapper.delete(Wrappers.<WikiPageTemplate>lambdaQuery()
                .eq(WikiPageTemplate::getPageId, pageId));
    }

    @Override
    public List<WikiPage> pagesUsing(String templateTitle, int limit) {
        String title = Strings.trimToEmpty(templateTitle);
        if (title.isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> keys = WikiLinkScanner.templateIndexKeys(title,
                wikitextProperties.getTemplatePrefixes());
        List<WikiPageTemplate> rows = templateMapper.selectList(Wrappers.<WikiPageTemplate>lambdaQuery()
                .in(WikiPageTemplate::getTemplateTitle, keys)
                .last("LIMIT " + (limit * CANDIDATE_LIMIT_FACTOR)));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> pageIds = rows.stream().map(WikiPageTemplate::getPageId).distinct().toList();
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .in(WikiPage::getId, pageIds)
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getTitle));

        // 实时扫描核对：候选页面的最新可见版本仍然直接使用了该模板才输出
        List<WikiPage> result = new ArrayList<>();
        for (WikiPage page : pages) {
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            if (usesTemplate(content, keys)) {
                result.add(page);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /** 正文（源码）里是否有模板调用命中候选键。 */
    private boolean usesTemplate(String content, Set<String> keys) {
        return WikiLinkScanner.templateLookupKeys(content, wikitextProperties.getTemplatePrefixes())
                .stream()
                .anyMatch(keys::contains);
    }

    /** 启动时全量回填：让旧数据 / 索引表刚创建时的已有页面进入索引。 */
    private void backfill() {
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .orderByAsc(WikiPage::getId)
                .last("LIMIT " + BACKFILL_LIMIT));
        for (WikiPage page : pages) {
            try {
                reindex(page.getId());
            } catch (RuntimeException exception) {
                log.warn("重建页面模板索引失败: {}", page.getTitle(), exception);
            }
        }
        log.info("模板索引重建完成，共 {} 个页面", pages.size());
    }
}
