package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPermissionType;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUserPermission;
import cc.thonly.wikikernel.db.mapper.WikiFileMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiPermissionTypeMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserPermissionMapper;
import cc.thonly.wikikernel.web.WikiNamespaces;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 站内统计（{{NUMBEROFPAGES}} 等魔术字用）：页面 / 条目 / 编辑 / 文件 / 用户 / 管理员。
 * 结果做 30 秒短缓存，避免页面里出现统计魔术字时反复查库。
 */
@Service
public class WikiStatsService {

    private static final long TTL_MILLIS = 30_000;

    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final WikiFileMapper wikiFileMapper;
    private final WikiUserMapper wikiUserMapper;
    private final WikiPermissionTypeMapper wikiPermissionTypeMapper;
    private final WikiUserPermissionMapper wikiUserPermissionMapper;

    private volatile long cachedAt;
    private volatile long[] cached = new long[6];

    public WikiStatsService(WikiPageMapper wikiPageMapper,
                            WikiRevisionMapper wikiRevisionMapper,
                            WikiFileMapper wikiFileMapper,
                            WikiUserMapper wikiUserMapper,
                            WikiPermissionTypeMapper wikiPermissionTypeMapper,
                            WikiUserPermissionMapper wikiUserPermissionMapper) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.wikiFileMapper = wikiFileMapper;
        this.wikiUserMapper = wikiUserMapper;
        this.wikiPermissionTypeMapper = wikiPermissionTypeMapper;
        this.wikiUserPermissionMapper = wikiUserPermissionMapper;
    }

    /** {@code [pages, articles, edits, files, users, admins]}。 */
    public long[] values() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > TTL_MILLIS) {
            cached = compute();
            cachedAt = now;
        }
        return cached.clone();
    }

    private long[] compute() {
        long pages = wikiPageMapper.selectCount(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false));
        long articles = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                        .select(WikiPage::getTitle)
                        .eq(WikiPage::getDeleted, false)
                        .eq(WikiPage::getHided, false))
                .stream()
                .filter(page -> !WikiNamespaces.inAnyNamespace(page.getTitle()))
                .count();
        long edits = wikiRevisionMapper.selectCount(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getDeleted, false));
        long files = wikiFileMapper.selectCount(Wrappers.<WikiFile>lambdaQuery()
                .eq(WikiFile::getDeleted, false));
        long users = wikiUserMapper.selectCount(null);
        return new long[]{pages, articles, edits, files, users, admins()};
    }

    private long admins() {
        Long typeId = Optional.ofNullable(wikiPermissionTypeMapper.selectOne(
                        Wrappers.<WikiPermissionType>lambdaQuery()
                                .eq(WikiPermissionType::getName, PermissionService.GROUP_ADMIN)
                                .last("LIMIT 1")))
                .map(WikiPermissionType::getId)
                .orElse(null);
        if (typeId == null) {
            return 0;
        }
        return wikiUserPermissionMapper.selectCount(Wrappers.<WikiUserPermission>lambdaQuery()
                .eq(WikiUserPermission::getPermissionType, typeId));
    }
}
