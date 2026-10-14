package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPageTemplate;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageTemplateMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:最多链接模板} / {@code Special:MostLinkedTemplates}：被最多页面嵌入（使用）的模板。
 * <p>
 * 数据来自模板使用索引（{@code wiki_page_template}，保存时实时维护）。
 */
@Component
public class MostLinkedTemplatesSpecialPage extends AbstractRankReportPage {

    private final WikiPageTemplateMapper templateMapper;
    private final AccessControlService accessControlService;

    public MostLinkedTemplatesSpecialPage(MessageSource messageSource,
                                          WikiPageTemplateMapper templateMapper,
                                          AccessControlService accessControlService) {
        super(messageSource);
        this.templateMapper = templateMapper;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "最多链接模板";
    }

    @Override
    public List<String> aliases() {
        return List.of("MostLinkedTemplates");
    }

    @Override
    protected String messageKey() {
        return "mostLinkedTemplates";
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
            if (row.getTemplateTitle() != null && !row.getTemplateTitle().isBlank()) {
                counts.merge(row.getTemplateTitle(), 1, Integer::sum);
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
