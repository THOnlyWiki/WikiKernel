package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code 特殊:最多链接页面} / {@code Special:MostLinkedPages}：被最多页面链接（链入）的页面，按链入数排序。
 * <p>
 * 与 {@code 特殊:待撰页面} 同一套正文扫描；只统计确实已创建的页面（红链归「待撰页面」）。
 */
@Component
public class MostLinkedPagesSpecialPage extends AbstractRankReportPage {

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;

    public MostLinkedPagesSpecialPage(MessageSource messageSource,
                                      WikiPageMapper wikiPageMapper,
                                      WikiPageService wikiPageService,
                                      AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "最多链接页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("MostLinkedPages");
    }

    @Override
    protected String messageKey() {
        return "mostLinkedPages";
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    protected List<Rank> ranks(SpecialPageRequest request) {
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getId)
                .last("LIMIT " + SCAN_LIMIT));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, request.user())) {
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            for (String target : ReportScans.pageLinks(content, page.getTitle())) {
                counts.merge(target, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return List.of();
        }
        Map<String, String> existing = existingTitles(counts.keySet());
        List<Rank> ranks = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String canonical = existing.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (canonical != null && accessControlService.canRead(canonical, request.user())) {
                ranks.add(new Rank(canonical, entry.getValue()));
            }
        }
        ranks.sort(Comparator.comparingInt(Rank::count).reversed()
                .thenComparing(Rank::title, String.CASE_INSENSITIVE_ORDER));
        return ranks;
    }

    /** 批量查「这些标题里哪些真的有页面」，返回「小写标题 → 规范标题」。 */
    private Map<String, String> existingTitles(Collection<String> titles) {
        Map<String, String> existing = new LinkedHashMap<>();
        List<String> batch = new ArrayList<>(titles);
        int chunk = 300;
        for (int start = 0; start < batch.size(); start += chunk) {
            List<String> slice = batch.subList(start, Math.min(batch.size(), start + chunk));
            for (WikiPage page : wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                    .in(WikiPage::getTitle, slice)
                    .eq(WikiPage::getDeleted, false)
                    .eq(WikiPage::getHided, false))) {
                existing.put(page.getTitle().toLowerCase(Locale.ROOT), page.getTitle());
            }
        }
        return existing;
    }
}
