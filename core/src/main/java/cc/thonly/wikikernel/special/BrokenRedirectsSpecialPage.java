package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code 特殊:损坏的重定向} / {@code Special:BrokenRedirects}。 */
@Component
public class BrokenRedirectsSpecialPage extends AbstractRedirectReportPage {

    public BrokenRedirectsSpecialPage(MessageSource messageSource,
                                      WikiPageMapper wikiPageMapper,
                                      WikiPageService wikiPageService,
                                      AccessControlService accessControlService) {
        super(messageSource, wikiPageMapper, wikiPageService, accessControlService);
    }

    @Override
    public String name() {
        return "损坏的重定向";
    }

    @Override
    public List<String> aliases() {
        return List.of("BrokenRedirects");
    }

    @Override
    protected Mode mode() {
        return Mode.BROKEN;
    }
}
