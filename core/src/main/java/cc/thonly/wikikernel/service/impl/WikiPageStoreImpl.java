package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.transfer.WikiSha1;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.wikitext.WikiRedirect;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 页面访问桥：把 core 的页面读取能力暴露给插件（{@code common.service.WikiPageStore}）。
 */
@Service
public class WikiPageStoreImpl implements WikiPageStore {

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;

    public WikiPageStoreImpl(WikiPageMapper wikiPageMapper, WikiPageService wikiPageService) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
    }

    @Override
    public Optional<PageSnapshot> byId(long pageId) {
        WikiPage page = wikiPageMapper.selectById(pageId);
        if (page == null || Boolean.TRUE.equals(page.getDeleted())) {
            return Optional.empty();
        }
        return Optional.of(snapshot(page));
    }

    @Override
    public Optional<PageSnapshot> byTitle(String title) {
        return wikiPageService.getByTitle(title).map(this::snapshot);
    }

    @Override
    public Optional<PageSnapshot> visibleByTitle(String title) {
        return wikiPageService.getVisibleByTitle(title).map(this::snapshot);
    }

    @Override
    public Optional<String> content(long pageId) {
        return wikiPageService.getLatestContent(pageId);
    }

    @Override
    public Optional<Long> latestRevisionId(long pageId) {
        WikiPage page = wikiPageMapper.selectById(pageId);
        if (page == null || Boolean.TRUE.equals(page.getDeleted())) {
            return Optional.empty();
        }
        return Optional.ofNullable(page.getLatestRevisionId());
    }

    @Override
    public List<PageSnapshot> contentPages() {
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .orderByAsc(WikiPage::getId));
        List<PageSnapshot> result = new ArrayList<>();
        for (WikiPage page : pages) {
            if (Boolean.TRUE.equals(page.getHided())) {
                continue;
            }
            PageSnapshot snapshot = snapshot(page);
            if (snapshot.contentPage()) {
                result.add(snapshot);
            }
        }
        return List.copyOf(result);
    }

    private PageSnapshot snapshot(WikiPage page) {
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");
        boolean redirect = WikiRedirect.targetOf(content).isPresent();
        boolean contentPage = !WikiNamespaces.inAnyNamespace(page.getTitle());
        return new PageSnapshot(page.getId(), page.getTitle(), redirect, contentPage,
                WikiSha1.bytes(content), page.getUpdateAt());
    }
}
