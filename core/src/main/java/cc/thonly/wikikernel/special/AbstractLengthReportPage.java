package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code 特殊:最长页面} / {@code 特殊:最短页面}（仿 MediaWiki Special:LongPages / Special:ShortPages）：
 * 按最新版本正文的字节数排序，最多展示 {@value #REPORT_LIMIT} 条。
 */
abstract class AbstractLengthReportPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 500;

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;

    protected AbstractLengthReportPage(MessageSource messageSource,
                                       WikiPageMapper wikiPageMapper,
                                       WikiPageService wikiPageService,
                                       AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
    }

    /** true = 最长在前，false = 最短在前。 */
    protected abstract boolean longest();

    @Override
    public String messageKey() {
        return longest() ? "longPages" : "shortPages";
    }

    @Override
    public String category() {
        return SpecialPageCategories.MAINTENANCE;
    }

    @Override
    public String render(SpecialPageRequest request) {
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getId)
                .last("LIMIT " + MAX_SCAN));
        List<WikiPage> readable = accessControlService.filterReadable(pages, WikiPage::getTitle, request.user());
        List<Object[]> sized = new ArrayList<>();
        for (WikiPage page : readable) {
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            sized.add(new Object[]{page, content.getBytes(StandardCharsets.UTF_8).length});
        }
        Comparator<Object[]> comparator = Comparator.comparingInt(entry -> (Integer) entry[1]);
        sized.sort(longest() ? comparator.reversed() : comparator);
        List<List<String>> rows = new ArrayList<>();
        for (Object[] entry : sized) {
            if (rows.size() >= REPORT_LIMIT) {
                break;
            }
            WikiPage page = (WikiPage) entry[0];
            rows.add(List.of(link(page.getTitle()), String.format("%,d", (Integer) entry[1])));
        }
        return page(table(List.of(message("report.col.page"), message("report.col.bytes")), rows));
    }
}
