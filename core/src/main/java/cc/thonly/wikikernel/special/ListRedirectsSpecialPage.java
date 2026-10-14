package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code 特殊:重定向列表} / {@code Special:ListRedirects}。 */
@Component
public class ListRedirectsSpecialPage extends AbstractRedirectReportPage {

    public ListRedirectsSpecialPage(MessageSource messageSource,
                                    WikiPageMapper wikiPageMapper,
                                    WikiPageService wikiPageService,
                                    AccessControlService accessControlService) {
        super(messageSource, wikiPageMapper, wikiPageService, accessControlService);
    }

    @Override
    public String name() {
        return "重定向列表";
    }

    @Override
    public List<String> aliases() {
        return List.of("ListRedirects");
    }

    @Override
    protected Mode mode() {
        return Mode.LIST;
    }
}
