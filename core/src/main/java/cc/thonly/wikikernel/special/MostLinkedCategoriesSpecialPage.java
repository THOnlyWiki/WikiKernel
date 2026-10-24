package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPageCategory;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageCategoryMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.impl.PageIndexGate;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:最多链接分类} / {@code Special:MostLinkedCategories}：成员最多的分类。
 * <p>
 * 数据来自分类索引（{@code wiki_page_category}）；与 {@code 特殊:分类} 的区别是本页按成员数排序。
 */
@Component
public class MostLinkedCategoriesSpecialPage extends AbstractRankReportPage {

    private final WikiPageCategoryMapper categoryMapper;
    private final AccessControlService accessControlService;
    private final PageIndexGate indexGate;

    public MostLinkedCategoriesSpecialPage(MessageSource messageSource,
                                           WikiPageCategoryMapper categoryMapper,
                                           AccessControlService accessControlService,
                                           PageIndexGate indexGate) {
        super(messageSource);
        this.categoryMapper = categoryMapper;
        this.accessControlService = accessControlService;
        this.indexGate = indexGate;
    }

    @Override
    public String name() {
        return "最多链接分类";
    }

    @Override
    public List<String> aliases() {
        return List.of("MostLinkedCategories");
    }

    @Override
    protected String messageKey() {
        return "mostLinkedCategories";
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
        indexGate.awaitIdle();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WikiPageCategory row : categoryMapper.selectList(Wrappers.<WikiPageCategory>lambdaQuery()
                .last("LIMIT " + INDEX_SCAN_LIMIT))) {
            if (row.getCategoryTitle() != null && !row.getCategoryTitle().isBlank()) {
                counts.merge(row.getCategoryTitle(), 1, Integer::sum);
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
