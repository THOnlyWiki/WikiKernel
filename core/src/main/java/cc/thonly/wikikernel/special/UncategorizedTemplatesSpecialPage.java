package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PageCategoryService;
import cc.thonly.wikikernel.wikitext.WikitextProperties;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 特殊:未分类模板} / {@code Special:UncategorizedTemplates}：本身没有归入任何分类的模板页。
 */
@Component
public class UncategorizedTemplatesSpecialPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 2000;

    private final WikiPageMapper wikiPageMapper;
    private final PageCategoryService pageCategoryService;
    private final WikitextProperties wikitextProperties;
    private final AccessControlService accessControlService;

    public UncategorizedTemplatesSpecialPage(MessageSource messageSource,
                                             WikiPageMapper wikiPageMapper,
                                             PageCategoryService pageCategoryService,
                                             WikitextProperties wikitextProperties,
                                             AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.pageCategoryService = pageCategoryService;
        this.wikitextProperties = wikitextProperties;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "未分类模板";
    }

    @Override
    public List<String> aliases() {
        return List.of("UncategorizedTemplates");
    }

    @Override
    protected String messageKey() {
        return "uncategorizedTemplates";
    }

    @Override
    public String category() {
        return SpecialPageCategories.MAINTENANCE;
    }

    @Override
    public String render(SpecialPageRequest request) {
        List<String> items = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(
                ReportScans.namespacePages(wikiPageMapper, wikitextProperties.getTemplatePrefixes(), MAX_SCAN),
                WikiPage::getTitle, request.user())) {
            if (items.size() >= REPORT_LIMIT) {
                break;
            }
            if (pageCategoryService.categoriesOf(page.getTitle()).isEmpty()) {
                items.add(link(page.getTitle()));
            }
        }
        return page(list(items));
    }
}
