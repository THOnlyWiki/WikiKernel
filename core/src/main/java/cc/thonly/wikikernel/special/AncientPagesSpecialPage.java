package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.time.WikiTime;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 特殊:最旧页面} / {@code Special:AncientPages}：按创建时间升序列出页面。
 */
@Component
public class AncientPagesSpecialPage extends AbstractReportPage {

    private final WikiPageMapper wikiPageMapper;
    private final AccessControlService accessControlService;
    private final WikiTime wikiTime;

    public AncientPagesSpecialPage(MessageSource messageSource,
                                   WikiPageMapper wikiPageMapper,
                                   AccessControlService accessControlService,
                                   WikiTime wikiTime) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.accessControlService = accessControlService;
        this.wikiTime = wikiTime;
    }

    @Override
    public String name() {
        return "最旧页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("AncientPages");
    }

    @Override
    public String messageKey() {
        return "ancientPages";
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
                .orderByAsc(WikiPage::getCreateAt)
                .last("LIMIT " + REPORT_LIMIT));
        List<List<String>> rows = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, request.user())) {
            rows.add(List.of(link(page.getTitle()), escape(wikiTime.format(page.getCreateAt()))));
        }
        return page(table(List.of(message("report.col.page"), message("report.col.time")), rows));
    }
}
