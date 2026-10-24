package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPageCategory;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageCategoryMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.impl.PageIndexGate;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:最多分类} / {@code Special:MostCategories}：归入分类最多的页面。
 * <p>
 * 数据来自分类索引（{@code wiki_page_category}，含模板里带出的分类）。
 */
@Component
public class MostCategoriesSpecialPage extends AbstractRankReportPage {

    private final WikiPageCategoryMapper categoryMapper;
    private final WikiPageMapper wikiPageMapper;
    private final AccessControlService accessControlService;
    private final PageIndexGate indexGate;

    public MostCategoriesSpecialPage(MessageSource messageSource,
                                     WikiPageCategoryMapper categoryMapper,
                                     WikiPageMapper wikiPageMapper,
                                     AccessControlService accessControlService,
                                     PageIndexGate indexGate) {
        super(messageSource);
        this.categoryMapper = categoryMapper;
        this.wikiPageMapper = wikiPageMapper;
        this.accessControlService = accessControlService;
        this.indexGate = indexGate;
    }

    @Override
    public String name() {
        return "最多分类";
    }

    @Override
    public List<String> aliases() {
        return List.of("MostCategories");
    }

    @Override
    protected String messageKey() {
        return "mostCategories";
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    protected List<Rank> ranks(SpecialPageRequest request) {
        indexGate.awaitIdle();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (WikiPageCategory row : categoryMapper.selectList(Wrappers.<WikiPageCategory>lambdaQuery()
                .last("LIMIT " + INDEX_SCAN_LIMIT))) {
            if (row.getPageId() != null) {
                counts.merge(row.getPageId(), 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return List.of();
        }
        List<Rank> ranks = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(
                wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                        .in(WikiPage::getId, counts.keySet())
                        .eq(WikiPage::getDeleted, false)
                        .eq(WikiPage::getHided, false)),
                WikiPage::getTitle, request.user())) {
            Integer count = counts.get(page.getId());
            if (count != null && count > 0) {
                ranks.add(new Rank(page.getTitle(), count));
            }
        }
        ranks.sort(Comparator.comparingInt(Rank::count).reversed()
                .thenComparing(Rank::title, String.CASE_INSENSITIVE_ORDER));
        return ranks;
    }
}
