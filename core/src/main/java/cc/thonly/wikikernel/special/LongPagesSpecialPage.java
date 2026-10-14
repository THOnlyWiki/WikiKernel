package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code 特殊:最长页面} / {@code Special:LongPages}。 */
@Component
public class LongPagesSpecialPage extends AbstractLengthReportPage {

    public LongPagesSpecialPage(MessageSource messageSource,
                                WikiPageMapper wikiPageMapper,
                                WikiPageService wikiPageService,
                                AccessControlService accessControlService) {
        super(messageSource, wikiPageMapper, wikiPageService, accessControlService);
    }

    @Override
    public String name() {
        return "最长页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("LongPages");
    }

    @Override
    protected boolean longest() {
        return true;
    }
}
