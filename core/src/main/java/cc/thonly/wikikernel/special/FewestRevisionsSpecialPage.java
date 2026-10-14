package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code 特殊:最少修订} / {@code Special:FewestRevisions}：编辑次数最少的页面。
 */
@Component
public class FewestRevisionsSpecialPage extends AbstractRevisionCountReportPage {

    public FewestRevisionsSpecialPage(MessageSource messageSource,
                                      WikiRevisionMapper wikiRevisionMapper,
                                      WikiPageMapper wikiPageMapper,
                                      AccessControlService accessControlService) {
        super(messageSource, wikiRevisionMapper, wikiPageMapper, accessControlService);
    }

    @Override
    public String name() {
        return "最少修订";
    }

    @Override
    public List<String> aliases() {
        return List.of("FewestRevisions");
    }

    @Override
    protected boolean most() {
        return false;
    }
}
