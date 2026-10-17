package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.RecentChangesStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link RecentChangesStore} 的 core 实现：edits / page / user 三张 core 表的只读投影
 * （从原 {@code AbuseFilterStoreImpl} 的「测试过滤器」查询搬出来，成为通用能力——
 * 插件不再需要 core 的表结构知识，也不需要滥用过滤器专属的桥）。
 */
@Service
public class RecentChangesStoreImpl implements RecentChangesStore {

    private final WikiRevisionMapper wikiRevisionMapper;
    private final WikiPageMapper wikiPageMapper;
    private final WikiUserMapper wikiUserMapper;

    public RecentChangesStoreImpl(WikiRevisionMapper wikiRevisionMapper,
                                  WikiPageMapper wikiPageMapper,
                                  WikiUserMapper wikiUserMapper) {
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.wikiPageMapper = wikiPageMapper;
        this.wikiUserMapper = wikiUserMapper;
    }

    @Override
    public List<RecentEdit> recentEdits(int limit) {
        int effective = Math.min(Math.max(1, limit), 500);
        List<WikiRevision> revisions = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getDeleted, false)
                .orderByDesc(WikiRevision::getId)
                .last("LIMIT " + effective));
        List<RecentEdit> edits = new ArrayList<>();
        for (WikiRevision revision : revisions) {
            WikiPage page = wikiPageMapper.selectById(revision.getPageId());
            if (page == null) {
                continue;
            }
            WikiRevision previous = wikiRevisionMapper.selectOne(Wrappers.<WikiRevision>lambdaQuery()
                    .eq(WikiRevision::getPageId, page.getId())
                    .eq(WikiRevision::getDeleted, false)
                    .lt(WikiRevision::getId, revision.getId())
                    .orderByDesc(WikiRevision::getId)
                    .last("LIMIT 1"));
            WikiUser author = revision.getUserId() == null
                    ? null : wikiUserMapper.selectById(revision.getUserId());
            String authorName = author == null
                    ? Strings.trimToEmpty(revision.getUserText())
                    : Strings.trimToEmpty(author.getUsername());
            edits.add(new RecentEdit(revision.getId(), page.getId(), page.getTitle(),
                    revision.getUserId(), authorName,
                    revision.getCreateAt(), Strings.trimToEmpty(revision.getComment()),
                    revision.getContent() == null ? "" : revision.getContent(),
                    previous == null || previous.getContent() == null ? "" : previous.getContent()));
        }
        return edits;
    }

    @Override
    public Optional<EditorStats> editorStats(long userId) {
        WikiUser user = wikiUserMapper.selectById(userId);
        if (user == null) {
            return Optional.empty();
        }
        long editCount = wikiRevisionMapper.selectCount(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getUserId, userId));
        LocalDateTime registeredAt = user.getCreateAt();
        return Optional.of(new EditorStats(editCount, registeredAt));
    }
}
