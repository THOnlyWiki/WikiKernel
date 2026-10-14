package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PageCategoryService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 特殊:未分类分类} / {@code Special:UncategorizedCategories}：本身没有归入任何分类的分类页。
 */
@Component
public class UncategorizedCategoriesSpecialPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 2000;

    /** 分类命名空间前缀。 */
    private static final List<String> PREFIXES = List.of("分类:", "Category:");

    private final WikiPageMapper wikiPageMapper;
    private final PageCategoryService pageCategoryService;
    private final AccessControlService accessControlService;

    public UncategorizedCategoriesSpecialPage(MessageSource messageSource,
                                              WikiPageMapper wikiPageMapper,
                                              PageCategoryService pageCategoryService,
                                              AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.pageCategoryService = pageCategoryService;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "未分类分类";
    }

    @Override
    public List<String> aliases() {
        return List.of("UncategorizedCategories");
    }

    @Override
    protected String messageKey() {
        return "uncategorizedCategories";
    }

    @Override
    public String category() {
        return SpecialPageCategories.MAINTENANCE;
    }

    @Override
    public String render(SpecialPageRequest request) {
        List<String> items = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(
                ReportScans.namespacePages(wikiPageMapper, PREFIXES, MAX_SCAN),
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
