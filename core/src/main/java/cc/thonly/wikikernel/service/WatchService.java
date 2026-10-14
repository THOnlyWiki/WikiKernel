package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiWatch;
import cc.thonly.wikikernel.db.mapper.WikiWatchMapper;
import cc.thonly.wikikernel.web.WikiTitles;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 监视列表（仿 MediaWiki 的 watchlist）：加入 / 移除 / 查询 / 整体替换。
 */
@Service
public class WatchService {

    private final WikiWatchMapper wikiWatchMapper;

    public WatchService(WikiWatchMapper wikiWatchMapper) {
        this.wikiWatchMapper = wikiWatchMapper;
    }

    /** 是否在监视列表中（标题按 MediaWiki 规则规范化）。 */
    public boolean isWatched(Long userId, String title) {
        if (userId == null) {
            return false;
        }
        String value = WikiTitles.normalize(title);
        if (value.isEmpty()) {
            return false;
        }
        return wikiWatchMapper.exists(Wrappers.<WikiWatch>lambdaQuery()
                .eq(WikiWatch::getUserId, userId)
                .eq(WikiWatch::getTitle, value));
    }

    /** 加入监视列表（重复加入忽略）。 */
    public boolean watch(Long userId, String title) {
        String value = WikiTitles.normalize(title);
        if (userId == null || value.isEmpty() || isWatched(userId, value)) {
            return false;
        }
        WikiWatch watch = new WikiWatch();
        watch.setUserId(userId);
        watch.setTitle(value);
        watch.setCreatedAt(LocalDateTime.now());
        wikiWatchMapper.insert(watch);
        return true;
    }

    /** 从监视列表移除。 */
    public boolean unwatch(Long userId, String title) {
        if (userId == null) {
            return false;
        }
        String value = WikiTitles.normalize(title);
        return wikiWatchMapper.delete(Wrappers.<WikiWatch>lambdaQuery()
                .eq(WikiWatch::getUserId, userId)
                .eq(WikiWatch::getTitle, value)) > 0;
    }

    /** 全部监视的标题（加入时间倒序）。 */
    public List<String> titles(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return wikiWatchMapper.selectList(Wrappers.<WikiWatch>lambdaQuery()
                        .eq(WikiWatch::getUserId, userId)
                        .orderByDesc(WikiWatch::getCreatedAt))
                .stream()
                .map(WikiWatch::getTitle)
                .toList();
    }

    /** 整体替换监视列表（「编辑监视列表」用）：去掉空行、按标题去重、最多 1000 条。 */
    @Transactional
    public int replaceAll(Long userId, List<String> titles) {
        if (userId == null) {
            return 0;
        }
        wikiWatchMapper.delete(Wrappers.<WikiWatch>lambdaQuery().eq(WikiWatch::getUserId, userId));
        Set<String> unique = new LinkedHashSet<>();
        for (String title : titles) {
            String value = WikiTitles.normalize(title);
            if (!value.isEmpty()) {
                unique.add(value);
            }
            if (unique.size() >= 1000) {
                break;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (String title : unique) {
            WikiWatch watch = new WikiWatch();
            watch.setUserId(userId);
            watch.setTitle(title);
            watch.setCreatedAt(now);
            wikiWatchMapper.insert(watch);
        }
        return unique.size();
    }
}
