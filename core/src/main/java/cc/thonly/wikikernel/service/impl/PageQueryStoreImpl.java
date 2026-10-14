package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.service.PageQueryStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 页面列举 + 版本历史桥：把 core 的页面表查询与 {@code WikiPageService} 的版本查询、
 * 以及命名空间定义（{@code WikiNamespaces}）暴露给插件。
 * <p>
 * 命名空间解析规则与「特殊:全部页面」一致：稳定 id（{@code template}）或任一前缀
 * （{@code 模板:} / {@code Template:}）都可以；主命名空间 = 不带任何前缀。
 */
@Service
public class PageQueryStoreImpl implements PageQueryStore {

    /** 列举页面的默认上限（调用方不指定时）。 */
    private static final int DEFAULT_LIMIT = 100;

    /** 一次查库的硬上限（先多取一些再按命名空间过滤）。 */
    private static final int QUERY_LIMIT = 500;

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;

    public PageQueryStoreImpl(WikiPageMapper wikiPageMapper, WikiPageService wikiPageService) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
    }

    @Override
    public List<String> namespaces() {
        Set<String> ids = new LinkedHashSet<>();
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            ids.add(namespace.id());
        }
        return List.copyOf(ids);
    }

    @Override
    public Optional<PageDetail> detail(String title) {
        return wikiPageService.getByTitle(title).map(page -> new PageDetail(page.getId(), page.getTitle(),
                page.getLatestRevisionId(), Boolean.TRUE.equals(page.getProtectedPage()),
                page.getCreateAt(), page.getUpdateAt()));
    }

    @Override
    public List<PageEntry> list(String prefix, String namespace, int limit) {
        int want = limit <= 0 ? DEFAULT_LIMIT : limit;
        String titlePrefix = Strings.trimToEmpty(prefix);
        WikiNamespaces.Namespace filter = resolveNamespace(namespace);
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getHided, false)
                .eq(WikiPage::getDeleted, false)
                .likeRight(!titlePrefix.isEmpty(), WikiPage::getTitle, titlePrefix)
                .orderByAsc(WikiPage::getTitle)
                .last("LIMIT " + Math.min(QUERY_LIMIT, Math.max(want, want * 4))));
        List<PageEntry> rows = new ArrayList<>();
        for (WikiPage page : pages) {
            if (!WikiNamespaces.matches(page.getTitle(), filter)) {
                continue;
            }
            rows.add(new PageEntry(page.getId(), page.getTitle(), page.getLatestRevisionId(), page.getUpdateAt()));
            if (rows.size() >= want) {
                break;
            }
        }
        return List.copyOf(rows);
    }

    @Override
    public Optional<RevisionEntry> latestRevision(long pageId) {
        return wikiPageService.getLatestRevision(pageId).map(PageQueryStoreImpl::entry);
    }

    @Override
    public List<RevisionEntry> history(long pageId, int limit) {
        List<WikiRevision> revisions = wikiPageService.getHistory(pageId);
        List<RevisionEntry> rows = new ArrayList<>();
        for (WikiRevision revision : revisions) {
            rows.add(entry(revision));
            if (limit > 0 && rows.size() >= limit) {
                break;
            }
        }
        return List.copyOf(rows);
    }

    /** 命名空间参数（稳定 id 或前缀）→ 定义；空 / all / 未知 = 不筛选（null）。 */
    private static WikiNamespaces.Namespace resolveNamespace(String value) {
        String want = Strings.trimToEmpty(value);
        if (want.isEmpty()) {
            return null;
        }
        String normalized = want.endsWith(":") ? want.substring(0, want.length() - 1) : want;
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            if (namespace.id().equalsIgnoreCase(normalized)) {
                return namespace;
            }
            for (String prefix : namespace.prefixes()) {
                String name = prefix.endsWith(":") ? prefix.substring(0, prefix.length() - 1) : prefix;
                if (name.equalsIgnoreCase(normalized)) {
                    return namespace;
                }
            }
        }
        return null;
    }

    private static RevisionEntry entry(WikiRevision revision) {
        String content = revision.getContent();
        return new RevisionEntry(revision.getId() == null ? 0 : revision.getId(), revision.getUserId(),
                Strings.trimToEmpty(revision.getComment()), content == null ? 0 : content.length(),
                Boolean.TRUE.equals(revision.getMinor()), Boolean.TRUE.equals(revision.getBot()),
                Boolean.TRUE.equals(revision.getAiAssisted()), Strings.trimToEmpty(revision.getAiModel()),
                Boolean.TRUE.equals(revision.getDeleted()), revision.getCreateAt());
    }
}
