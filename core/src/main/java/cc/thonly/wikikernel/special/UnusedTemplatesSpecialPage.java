package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPageTemplate;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageTemplateMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import cc.thonly.wikikernel.wikitext.WikitextProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code 特殊:未使用模板} / {@code Special:UnusedTemplates}：没有任何页面嵌入（使用）的模板。
 */
@Component
public class UnusedTemplatesSpecialPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 1000;

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageTemplateMapper templateMapper;
    private final WikitextProperties wikitextProperties;
    private final AccessControlService accessControlService;

    public UnusedTemplatesSpecialPage(MessageSource messageSource,
                                      WikiPageMapper wikiPageMapper,
                                      WikiPageTemplateMapper templateMapper,
                                      WikitextProperties wikitextProperties,
                                      AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.templateMapper = templateMapper;
        this.wikitextProperties = wikitextProperties;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "未使用模板";
    }

    @Override
    public List<String> aliases() {
        return List.of("UnusedTemplates");
    }

    @Override
    protected String messageKey() {
        return "unusedTemplates";
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String render(SpecialPageRequest request) {
        Set<String> used = new HashSet<>();
        for (WikiPageTemplate row : templateMapper.selectList(Wrappers.<WikiPageTemplate>lambdaQuery()
                .select(WikiPageTemplate::getTemplateTitle))) {
            if (row.getTemplateTitle() != null) {
                used.add(row.getTemplateTitle());
            }
        }
        List<String> items = new ArrayList<>();
        List<String> prefixes = wikitextProperties.getTemplatePrefixes();
        for (WikiPage page : accessControlService.filterReadable(
                ReportScans.namespacePages(wikiPageMapper, prefixes, MAX_SCAN),
                WikiPage::getTitle, request.user())) {
            if (items.size() >= REPORT_LIMIT) {
                break;
            }
            boolean isUsed = false;
            for (String key : WikiLinkScanner.templateIndexKeys(page.getTitle(), prefixes)) {
                if (used.contains(key)) {
                    isUsed = true;
                    break;
                }
            }
            if (!isUsed) {
                items.add(link(page.getTitle()));
            }
        }
        return page(list(items));
    }
}
