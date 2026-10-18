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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模板使用索引（{@code wiki_page_template}）：
 * <ul>
 *     <li>页面保存 / 删除后由页面变更事件在后台索引线程重建（{@code PageIndexService}），保存请求不等待；</li>
 *     <li>查询时先有界等待本页索引队列（不等待模板依赖页队列），再按索引找候选并对最新正文实时核对，
 *     保存后立刻查询仍是新结果；</li>
 *     <li>启动时全量回填（{@code @Order(5)}，在 DDL / 迁移 / 设置 / 分类索引之后）。</li>
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
    private final PageIndexGate indexGate;
    private final TransactionTemplate transactionTemplate;

    /** 本服务的索引写入串行锁：本页索引队列与后台依赖队列可能同时重建同一页，避免删/插交错。 */
    private final Object writeLock = new Object();

    public PageTemplateServiceImpl(WikiPageTemplateMapper templateMapper,
                                   WikiPageMapper wikiPageMapper,
                                   WikiPageService wikiPageService,
                                   WikitextProperties wikitextProperties,
                                   PageIndexGate indexGate,
                                   PlatformTransactionManager transactionManager) {
        this.templateMapper = templateMapper;
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
        this.wikitextProperties = wikitextProperties;
        this.indexGate = indexGate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        backfill();
    }

    /**
     * 重建一页的模板使用索引：读取正文 / 扫描模板调用在事务外完成，
     * 只有删旧行 + 写新行放进短事务，避免长时间占住 SQLite 的单写连接。
     */
    @Override
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
        replace(pageId, titles);
    }

    private void replace(long pageId, Set<String> titles) {
        synchronized (writeLock) {
            transactionTemplate.executeWithoutResult(status -> {
                templateMapper.delete(Wrappers.<WikiPageTemplate>lambdaQuery()
                        .eq(WikiPageTemplate::getPageId, pageId));
                for (String title : titles) {
                    templateMapper.insert(new WikiPageTemplate(null, pageId, title));
                }
            });
        }
    }

    @Override
    public void reindex(String title) {
        wikiPageService.getByTitle(Strings.trimToEmpty(title))
                .ifPresent(page -> reindex(page.getId()));
    }

    @Override
    public void clear(long pageId) {
        synchronized (writeLock) {
            transactionTemplate.executeWithoutResult(status -> templateMapper.delete(
                    Wrappers.<WikiPageTemplate>lambdaQuery().eq(WikiPageTemplate::getPageId, pageId)));
        }
    }

    @Override
    public List<WikiPage> pagesUsing(String templateTitle, int limit) {
        // 保存请求异步更新索引；读取时等队列清空，保证「保存后立刻查询」看到最新结果。
        indexGate.awaitIdle();
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
