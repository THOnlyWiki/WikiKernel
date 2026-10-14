package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code 特殊:最多修订} / {@code Special:MostRevisions}：编辑次数最多的页面。
 */
@Component
public class MostRevisionsSpecialPage extends AbstractRevisionCountReportPage {

    public MostRevisionsSpecialPage(MessageSource messageSource,
                                    WikiRevisionMapper wikiRevisionMapper,
                                    WikiPageMapper wikiPageMapper,
                                    AccessControlService accessControlService) {
        super(messageSource, wikiRevisionMapper, wikiPageMapper, accessControlService);
    }

    @Override
    public String name() {
        return "最多修订";
    }

    @Override
    public List<String> aliases() {
        return List.of("MostRevisions");
    }

    @Override
    protected boolean most() {
        return true;
    }
}
