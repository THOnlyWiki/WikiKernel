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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code 特殊:待撰页面} / {@code Special:WantedPages}：被链接但尚未创建的页面（按被链接次数排序）。
 */
@Component
public class WantedPagesSpecialPage extends AbstractReportPage {

    /** 单次扫描上限（最多多少页面参与统计）。 */
    private static final int MAX_SCAN = 300;

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;

    public WantedPagesSpecialPage(MessageSource messageSource,
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
        return "待撰页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("WantedPages");
    }

    @Override
    public String messageKey() {
        return "wantedPages";
    }

    @Override
    public String category() {
        return SpecialPageCategories.HIGHUSE;
    }

    @Override
    public String render(SpecialPageRequest request) {
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getId)
                .last("LIMIT " + MAX_SCAN));
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, request.user())) {
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            for (String target : ReportScans.pageLinks(content, page.getTitle())) {
                if (target.isEmpty() || skip(target)) {
                    continue;
                }
                if (wikiPageService.getByTitle(target).isEmpty()) {
                    wanted.merge(target, 1, Integer::sum);
                }
            }
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(wanted.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Integer>, Integer>comparing(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            if (rows.size() >= REPORT_LIMIT) {
                break;
            }
            rows.add(List.of(link(entry.getKey()), String.valueOf(entry.getValue())));
        }
        return page(table(List.of(message("report.col.page"), message("report.col.count")), rows));
    }

    /** 跳过分类 / 文件 / 特殊页与外部链接（这些不算「待撰」）。 */
    private static boolean skip(String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        return lower.startsWith("分类:") || lower.startsWith("category:")
                || lower.startsWith("文件:") || lower.startsWith("file:")
                || lower.startsWith("特殊:") || lower.startsWith("special:")
                || lower.startsWith("http://") || lower.startsWith("https://");
    }
}
