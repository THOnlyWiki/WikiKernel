package cc.thonly.wikikernel.search;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.extension.SearchHit;
import cc.thonly.wikikernel.common.extension.SearchProvider;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.web.WikiUrls;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * core 自带的数据库搜索：标题 + 最新版本正文模糊匹配，页面由 {@code 特殊:搜索} 使用。
 * <p>
 * 正文存放在 {@code wiki_revision}，搜索时先在最新若干版本里匹配出页面 ID，再合并标题命中的页面。
 */
@Component
public class DatabaseSearchProvider implements SearchProvider {

    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;

    public DatabaseSearchProvider(WikiPageMapper wikiPageMapper, WikiRevisionMapper wikiRevisionMapper) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
    }

    @Override
    public String name() {
        return "database";
    }

    @Override
    public List<SearchHit> search(String keyword, int limit) {
        if (Strings.isBlank(keyword)) {
            return List.of();
        }
        int max = limit > 0 ? limit : 20;
        Map<Long, WikiPage> matched = new LinkedHashMap<>();

        // 1) 标题命中
        wikiPageMapper.selectList(visiblePages()
                        .like(WikiPage::getTitle, keyword)
                        .orderByDesc(WikiPage::getUpdateAt)
                        .last("LIMIT " + max))
                .forEach(page -> matched.put(page.getId(), page));

        // 2) 正文命中（取最近的若干版本，去重页面；已删除版本不参与）
        List<Long> contentPageIds = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                        .eq(WikiRevision::getDeleted, false)
                        .like(WikiRevision::getContent, keyword)
                        .orderByDesc(WikiRevision::getId)
                        .last("LIMIT " + (max * 3)))
                .stream()
                .map(WikiRevision::getPageId)
                .distinct()
                .toList();
        if (!contentPageIds.isEmpty()) {
            wikiPageMapper.selectList(visiblePages().in(WikiPage::getId, contentPageIds))
                    .forEach(page -> matched.putIfAbsent(page.getId(), page));
        }

        return matched.values().stream()
                .sorted(Comparator.comparing(WikiPage::getUpdateAt).reversed())
                .limit(max)
                .map(page -> new SearchHit(page.getTitle(), WikiUrls.pagePath(page.getTitle()),
                        snippet(latestContent(page.getId()), keyword), 1.0))
                .toList();
    }

    private LambdaQueryWrapper<WikiPage> visiblePages() {
        return Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false);
    }

    private String latestContent(Long pageId) {
        WikiRevision revision = wikiRevisionMapper.selectOne(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getPageId, pageId)
                .eq(WikiRevision::getDeleted, false)
                .orderByDesc(WikiRevision::getId)
                .last("LIMIT 1"));
        return revision == null ? "" : revision.getContent();
    }

    private String snippet(String content, String keyword) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").strip();
        int index = flat.toLowerCase(Locale.ROOT).indexOf(keyword.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
        }
        int start = Math.max(0, index - 30);
        int end = Math.min(flat.length(), index + keyword.length() + 50);
        return (start > 0 ? "…" : "") + flat.substring(start, end) + (end < flat.length() ? "…" : "");
    }
}
