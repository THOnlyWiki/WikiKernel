package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPageCategory;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageCategoryMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code 特殊:未使用分类} / {@code Special:UnusedCategories}：没有任何成员的分类（分类页已创建但没页面归入）。
 */
@Component
public class UnusedCategoriesSpecialPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 2000;

    /** 分类命名空间前缀（中文规范名 + 英文别名）。 */
    private static final List<String> PREFIXES = List.of("分类:", "Category:");

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageCategoryMapper categoryMapper;
    private final AccessControlService accessControlService;

    public UnusedCategoriesSpecialPage(MessageSource messageSource,
                                       WikiPageMapper wikiPageMapper,
                                       WikiPageCategoryMapper categoryMapper,
                                       AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.categoryMapper = categoryMapper;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "未使用分类";
    }

    @Override
    public List<String> aliases() {
        return List.of("UnusedCategories");
    }

    @Override
    protected String messageKey() {
        return "unusedCategories";
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String render(SpecialPageRequest request) {
        Set<String> used = new HashSet<>();
        for (WikiPageCategory row : categoryMapper.selectList(Wrappers.<WikiPageCategory>lambdaQuery()
                .select(WikiPageCategory::getCategoryTitle))) {
            if (row.getCategoryTitle() != null) {
                used.add(row.getCategoryTitle());
            }
        }
        List<String> items = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(
                ReportScans.namespacePages(wikiPageMapper, PREFIXES, MAX_SCAN),
                WikiPage::getTitle, request.user())) {
            if (items.size() >= REPORT_LIMIT) {
                break;
            }
            if (!used.contains(WikiLinkScanner.canonicalCategoryTitle(page.getTitle()))) {
                items.add(link(page.getTitle()));
            }
        }
        return page(list(items));
    }
}
