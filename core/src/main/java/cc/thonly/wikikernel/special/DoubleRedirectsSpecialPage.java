package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code 特殊:双重重定向} / {@code Special:DoubleRedirects}。 */
@Component
public class DoubleRedirectsSpecialPage extends AbstractRedirectReportPage {

    public DoubleRedirectsSpecialPage(MessageSource messageSource,
                                      WikiPageMapper wikiPageMapper,
                                      WikiPageService wikiPageService,
                                      AccessControlService accessControlService) {
        super(messageSource, wikiPageMapper, wikiPageService, accessControlService);
    }

    @Override
    public String name() {
        return "双重重定向";
    }

    @Override
    public List<String> aliases() {
        return List.of("DoubleRedirects");
    }

    @Override
    protected Mode mode() {
        return Mode.DOUBLE;
    }
}
