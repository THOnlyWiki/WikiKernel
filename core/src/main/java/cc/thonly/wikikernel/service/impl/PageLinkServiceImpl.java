package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.PageLinkService;
import cc.thonly.wikikernel.service.PageTemplateService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import cc.thonly.wikikernel.wikitext.WikitextProperties;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class PageLinkServiceImpl implements PageLinkService {

    /** 先在版本表里粗筛，最多看这么多版本。 */
    private static final int REVISION_SCAN_LIMIT_FACTOR = 5;

    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final WikiPageService wikiPageService;
    private final PageTemplateService pageTemplateService;
    private final WikitextProperties wikitextProperties;
    private final WikitextRenderer wikitextRenderer;

    public PageLinkServiceImpl(WikiPageMapper wikiPageMapper,
                               WikiRevisionMapper wikiRevisionMapper,
                               WikiPageService wikiPageService,
                               PageTemplateService pageTemplateService,
                               WikitextProperties wikitextProperties,
                               WikitextRenderer wikitextRenderer) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.wikiPageService = wikiPageService;
        this.pageTemplateService = pageTemplateService;
        this.wikitextProperties = wikitextProperties;
        this.wikitextRenderer = wikitextRenderer;
    }

    @Override
    public List<WikiPage> pagesLinkingTo(String title, int limit) {
        if (Strings.isBlank(title) || limit <= 0) {
            return List.of();
        }
        String needle = "[[" + title;

        // 1) 版本正文里粗筛出可能链接到该标题的页面
        List<Long> pageIds = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                        .like(WikiRevision::getContent, needle)
                        .orderByDesc(WikiRevision::getId)
                        .last("LIMIT " + (limit * REVISION_SCAN_LIMIT_FACTOR)))
                .stream()
                .map(WikiRevision::getPageId)
                .distinct()
                .toList();
        if (pageIds.isEmpty()) {
            return List.of();
        }

        // 2) 只保留“最新可见版本”里真正还有该链接的页面
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .in(WikiPage::getId, pageIds)
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getTitle));
        List<WikiPage> result = new ArrayList<>();
        for (WikiPage page : pages) {
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            if (content.contains(needle)) {
                result.add(page);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public List<WikiPage> pagesUsingFile(String fileName, int limit) {
        String name = Strings.trimToEmpty(fileName);
        if (name.isEmpty() || limit <= 0) {
            return List.of();
        }
        String chinese = "[[" + FileTitles.PREFIX + name;
        String english = "[[" + FileTitles.PREFIX_ALIAS + name;

        // 1) 版本正文粗筛：直接嵌入（[[文件:名…]]）或模板参数里出现文件名（图片常通过信息框等模板带入）
        List<Long> pageIds = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                        .and(wrapper -> wrapper
                                .like(WikiRevision::getContent, chinese)
                                .or()
                                .like(WikiRevision::getContent, english)
                                .or()
                                .like(WikiRevision::getContent, name))
                        .orderByDesc(WikiRevision::getId)
                        .last("LIMIT " + (limit * REVISION_SCAN_LIMIT_FACTOR)))
                .stream()
                .map(WikiRevision::getPageId)
                .distinct()
                .toList();
        if (pageIds.isEmpty()) {
            return List.of();
        }

        // 2) 最新可见版本里精确核对：正文直接嵌入，或渲染后出现该文件的链接（模板带入也算，仿 MediaWiki 文件用途）
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .in(WikiPage::getId, pageIds)
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getTitle));
        List<WikiPage> result = new ArrayList<>();
        for (WikiPage page : pages) {
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            if (usesFile(content, name) || rendersFileLink(content, page.getTitle(), name)) {
                result.add(page);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /** 正文是否真的嵌入了该文件（前缀匹配后要求紧跟 {@code |} 或 {@code ]}）。 */
    private static boolean usesFile(String content, String fileName) {
        for (String prefix : new String[]{FileTitles.PREFIX, FileTitles.PREFIX_ALIAS}) {
            String needle = "[[" + prefix + fileName;
            int index = content.indexOf(needle);
            while (index >= 0) {
                int after = index + needle.length();
                while (after < content.length() && content.charAt(after) == ' ') {
                    after++;
                }
                if (after < content.length()
                        && (content.charAt(after) == '|' || content.charAt(after) == ']')) {
                    return true;
                }
                index = content.indexOf(needle, index + 1);
            }
        }
        return false;
    }

    /** 渲染该页，看结果里是否出现「文件:<名>」的链接（用于识别模板带入的图片）。 */
    private boolean rendersFileLink(String content, String pageTitle, String fileName) {
        if (!content.contains("{{")) {
            return false;
        }
        String link = WikiUrls.pagePath(FileTitles.titleOf(fileName));
        try {
            return wikitextRenderer.render(content, pageTitle).contains(link);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public List<WikiPage> pagesTranscluding(String templateTitle, int limit) {
        // 模板使用有索引（wiki_page_template，保存 / 删除时实时重建）：
        // 索引找候选 → 对最新版本正文实时扫描核对，索引过期行不会输出
        return pageTemplateService.pagesUsing(templateTitle, limit);
    }

    @Override
    public List<String> templatesUsedBy(String pageTitle) {
        return latestContent(pageTitle)
                .map(content -> WikiLinkScanner.templateTitles(content,
                        wikitextProperties.getTemplatePrefixes()))
                .orElse(List.of());
    }

    // ==================== 内部实现 ====================

    private Optional<String> latestContent(String pageTitle) {
        return wikiPageService.getVisibleByTitle(Strings.trimToEmpty(pageTitle))
                .flatMap(page -> wikiPageService.getLatestContent(page.getId()));
    }
}
