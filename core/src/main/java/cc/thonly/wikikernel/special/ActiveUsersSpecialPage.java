package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.time.WikiTime;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:活跃用户} / {@code Special:ActiveUsers}：最近 30 天有编辑的用户（按编辑数排序）。
 */
@Component
public class ActiveUsersSpecialPage extends AbstractReportPage {

    /** 统计窗口（天）。 */
    private static final int RECENT_DAYS = 30;

    /** 单次统计的版本数上限。 */
    private static final int MAX_REVISIONS = 5000;

    private final WikiRevisionMapper wikiRevisionMapper;
    private final UserService userService;
    private final WikiTime wikiTime;

    public ActiveUsersSpecialPage(MessageSource messageSource,
                                  WikiRevisionMapper wikiRevisionMapper,
                                  UserService userService,
                                  WikiTime wikiTime) {
        super(messageSource);
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.userService = userService;
        this.wikiTime = wikiTime;
    }

    @Override
    public String name() {
        return "活跃用户";
    }

    @Override
    public List<String> aliases() {
        return List.of("ActiveUsers");
    }

    @Override
    public String messageKey() {
        return "activeUsers";
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String render(SpecialPageRequest request) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RECENT_DAYS);
        List<WikiRevision> revisions = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getDeleted, false)
                .ge(WikiRevision::getCreateAt, cutoff)
                .orderByDesc(WikiRevision::getCreateAt)
                .last("LIMIT " + MAX_REVISIONS));
        Map<Long, Integer> counts = new LinkedHashMap<>();
        Map<Long, LocalDateTime> last = new LinkedHashMap<>();
        for (WikiRevision revision : revisions) {
            if (revision.getUserId() == null) {
                continue;
            }
            counts.merge(revision.getUserId(), 1, Integer::sum);
            last.merge(revision.getUserId(), revision.getCreateAt(),
                    (first, second) -> first.isAfter(second) ? first : second);
        }
        List<Long> userIds = new ArrayList<>(counts.keySet());
        userIds.sort(Comparator.<Long, Integer>comparing(counts::get).reversed());
        if (userIds.size() > REPORT_LIMIT) {
            userIds = userIds.subList(0, REPORT_LIMIT);
        }
        Map<Long, String> names = userService.usernames(userIds);
        List<List<String>> rows = new ArrayList<>();
        for (Long userId : userIds) {
            String name = names.getOrDefault(userId, "#" + userId);
            rows.add(List.of(
                    link("用户:" + name, name),
                    String.valueOf(counts.get(userId)),
                    escape(wikiTime.format(last.get(userId)))));
        }
        return page(table(List.of(message("report.col.user"), message("report.col.count"),
                message("report.col.time")), rows));
    }
}
