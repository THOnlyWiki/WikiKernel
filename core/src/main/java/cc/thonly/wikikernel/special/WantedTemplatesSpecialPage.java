package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPageTemplate;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageTemplateMapper;
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
 * {@code 特殊:待撰模板} / {@code Special:WantedTemplates}：被页面调用但尚未创建的模板（按被调用次数排序）。
 */
@Component
public class WantedTemplatesSpecialPage extends AbstractRankReportPage {

    private final WikiPageTemplateMapper templateMapper;
    private final PageExistenceService pageExistenceService;
    private final AccessControlService accessControlService;

    public WantedTemplatesSpecialPage(MessageSource messageSource,
                                      WikiPageTemplateMapper templateMapper,
                                      PageExistenceService pageExistenceService,
                                      AccessControlService accessControlService) {
        super(messageSource);
        this.templateMapper = templateMapper;
        this.pageExistenceService = pageExistenceService;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "待撰模板";
    }

    @Override
    public List<String> aliases() {
        return List.of("WantedTemplates");
    }

    @Override
    protected String messageKey() {
        return "wantedTemplates";
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    protected List<Rank> ranks(SpecialPageRequest request) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WikiPageTemplate row : templateMapper.selectList(Wrappers.<WikiPageTemplate>lambdaQuery()
                .last("LIMIT " + INDEX_SCAN_LIMIT))) {
            String title = row.getTemplateTitle();
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
