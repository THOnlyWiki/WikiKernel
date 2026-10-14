package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修订数报告基类（仿 MediaWiki 的 Special:MostRevisions / Special:FewestRevisions）：
 * 按「页面最新可见修订条数」排序；{@link #most()} 决定最多在前还是最少在前。
 */
abstract class AbstractRevisionCountReportPage extends AbstractRankReportPage {

    /** 单次统计的修订上限（只取 pageId 列，避免读出正文）。 */
    private static final int REVISION_SCAN_LIMIT = 20000;

    private final WikiRevisionMapper wikiRevisionMapper;
    private final WikiPageMapper wikiPageMapper;
    private final AccessControlService accessControlService;

    protected AbstractRevisionCountReportPage(MessageSource messageSource,
                                              WikiRevisionMapper wikiRevisionMapper,
                                              WikiPageMapper wikiPageMapper,
                                              AccessControlService accessControlService) {
        super(messageSource);
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.wikiPageMapper = wikiPageMapper;
        this.accessControlService = accessControlService;
    }

    /** true = 修订最多在前，false = 最少在前。 */
    protected abstract boolean most();

    @Override
    protected String messageKey() {
        return most() ? "mostRevisions" : "fewestRevisions";
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    protected List<Rank> ranks(SpecialPageRequest request) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (WikiRevision revision : wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .select(WikiRevision::getPageId)
                .eq(WikiRevision::getDeleted, false)
                .last("LIMIT " + REVISION_SCAN_LIMIT))) {
            if (revision.getPageId() != null) {
                counts.merge(revision.getPageId(), 1, Integer::sum);
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
        Comparator<Rank> byCount = Comparator.comparingInt(Rank::count);
        ranks.sort((most() ? byCount.reversed() : byCount)
                .thenComparing(Rank::title, String.CASE_INSENSITIVE_ORDER));
        return ranks;
    }
}
