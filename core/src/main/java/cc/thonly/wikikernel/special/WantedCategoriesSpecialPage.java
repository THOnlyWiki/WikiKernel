package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPageCategory;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageCategoryMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PageExistenceService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:待撰分类} / {@code Special:WantedCategories}：被页面使用但尚未创建的分类（按成员数排序）。
 */
@Component
public class WantedCategoriesSpecialPage extends AbstractRankReportPage {

    private final WikiPageCategoryMapper categoryMapper;
    private final PageExistenceService pageExistenceService;
    private final AccessControlService accessControlService;

    public WantedCategoriesSpecialPage(MessageSource messageSource,
                                       WikiPageCategoryMapper categoryMapper,
                                       PageExistenceService pageExistenceService,
                                       AccessControlService accessControlService) {
        super(messageSource);
        this.categoryMapper = categoryMapper;
        this.pageExistenceService = pageExistenceService;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "待撰分类";
    }

    @Override
    public List<String> aliases() {
        return List.of("WantedCategories");
    }

    @Override
    protected String messageKey() {
        return "wantedCategories";
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    protected List<String> headers() {
        return List.of(message("report.col.category"), message("report.col.members"));
    }

    @Override
    protected List<Rank> ranks(SpecialPageRequest request) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WikiPageCategory row : categoryMapper.selectList(Wrappers.<WikiPageCategory>lambdaQuery()
                .last("LIMIT " + INDEX_SCAN_LIMIT))) {
            String title = row.getCategoryTitle();
            if (title != null && !title.isBlank() && !pageExistenceService.exists(title)) {
                counts.merge(title, 1, Integer::sum);
            }
        }
        List<Rank> ranks = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (accessControlService.canRead(entry.getKey(), request.user())) {
                ranks.add(new Rank(entry.getKey(), entry.getValue()));
            }
        }
        ranks.sort(Comparator.comparingInt(Rank::count).reversed()
                .thenComparing(Rank::title, String.CASE_INSENSITIVE_ORDER));
        return ranks;
    }
}
