package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PageCategoryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 特殊:未分类页面} / {@code Special:UncategorizedPages}：没有属于任何分类的页面。
 */
@Component
public class UncategorizedPagesSpecialPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 300;

    private final WikiPageMapper wikiPageMapper;
    private final PageCategoryService pageCategoryService;
    private final AccessControlService accessControlService;

    public UncategorizedPagesSpecialPage(MessageSource messageSource,
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
        return "未分类页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("UncategorizedPages");
    }

    @Override
    public String messageKey() {
        return "uncategorizedPages";
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
                .orderByAsc(WikiPage::getTitle)
                .last("LIMIT " + MAX_SCAN));
        List<String> items = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, request.user())) {
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
