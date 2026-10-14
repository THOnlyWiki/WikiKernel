package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiFileMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.FileTitles;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:待撰文件} / {@code Special:WantedFiles}：被页面嵌入但尚未上传的文件（按被引用次数排序）。
 */
@Component
public class WantedFilesSpecialPage extends AbstractRankReportPage {

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;
    private final WikiFileMapper wikiFileMapper;
    private final AccessControlService accessControlService;

    public WantedFilesSpecialPage(MessageSource messageSource,
                                  WikiPageMapper wikiPageMapper,
                                  WikiPageService wikiPageService,
                                  WikiFileMapper wikiFileMapper,
                                  AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
        this.wikiFileMapper = wikiFileMapper;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "待撰文件";
    }

    @Override
    public List<String> aliases() {
        return List.of("WantedFiles");
    }

    @Override
    protected String messageKey() {
        return "wantedFiles";
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    protected List<String> headers() {
        return List.of(message("report.col.file"), message("report.col.count"));
    }

    @Override
    protected List<Rank> ranks(SpecialPageRequest request) {
        Set<String> existing = new HashSet<>();
        for (WikiFile file : wikiFileMapper.selectList(Wrappers.<WikiFile>lambdaQuery()
                .select(WikiFile::getName)
                .eq(WikiFile::getDeleted, false))) {
            existing.add(file.getName());
        }
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getId)
                .last("LIMIT " + SCAN_LIMIT));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, request.user())) {
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            for (String name : ReportScans.fileNames(content)) {
                if (!existing.contains(name)) {
                    counts.merge(name, 1, Integer::sum);
                }
            }
        }
        List<Rank> ranks = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            ranks.add(new Rank(FileTitles.titleOf(entry.getKey()), entry.getKey(), entry.getValue()));
        }
        ranks.sort(Comparator.comparingInt(Rank::count).reversed()
                .thenComparing(Rank::label, String.CASE_INSENSITIVE_ORDER));
        return ranks;
    }
}
