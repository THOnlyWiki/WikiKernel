package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserAward;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 奖励 / 勋章规则（仿 MediaWiki SocialProfile 的 {@code Special:Awards}）：
 * <ul>
 *     <li><b>奖励清单</b>：内置若干种（见 {@link #CATALOG}），名称走消息键
 *     {@code socialProfile.award.<id>}（不写死中文）；</li>
 *     <li><b>授予 / 撤销</b>：只有管理员（用户组 {@code sysop} / 本地化的 {@code 管理员}，
 *     由 API 层用 {@code common.service.AccessCheck} 判定）能做；同一种奖励不重复授予（幂等）；</li>
 *     <li>用户的勋章在**用户页资料卡**里显示（受社交偏好 {@code socialProfile.pref.showAwards} 控制）。</li>
 * </ul>
 * 数据读写走 {@link UserAwardStore} 桥。
 */
@Component
public final class AwardService {

    /** 内置奖励清单（id 稳定，顺序即展示顺序；名称走 {@code socialProfile.award.<id>}）。 */
    public static final List<String> CATALOG = List.of(
            "barnstar", "goodEditor", "friendly", "helper", "veteran", "socialButterfly");

    /** 说明长度上限。 */
    public static final int COMMENT_LIMIT = 200;

    /** 列表条数上限（默认 200，键 {@code socialProfile.awards.limit}）。 */
    public static final int DEFAULT_LIST_LIMIT = 200;

    private final UserAwardStore awards;
    private final UserStore users;
    private final SocialProfileSupport support;

    public AwardService(UserAwardStore awards, UserStore users, SocialProfileSupport support) {
        this.awards = awards;
        this.users = users;
        this.support = support;
    }

    /** 列表条数上限。 */
    public int listLimit() {
        return Math.max(support.setting("awards.limit", "socialProfile.awards.limit",
                DEFAULT_LIST_LIMIT), 1);
    }

    /** 奖励 id 是否在内置清单里。 */
    public boolean isKnown(String awardId) {
        return awardId != null && CATALOG.contains(awardId.trim());
    }

    /** 奖励清单（id + 本地化名称），供授予表单下拉框用。 */
    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : CATALOG) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", support.awardName(id));
            rows.add(row);
        }
        return rows;
    }

    /** 某人获得的奖励（新的在前）。 */
    public List<WikiUserAward> of(long userId, int limit) {
        return awards.byUser(userId, limit);
    }

    /** 某人获得的奖励种类 id（去重，按授予时间倒序）。 */
    public List<String> awardIdsOf(long userId) {
        return awards.awardIdsOf(userId);
    }

    /** 一批用户各自的勋章数。 */
    public Map<Long, Long> counts(Collection<Long> userIds) {
        return awards.counts(userIds);
    }

    /**
     * 授予奖励（管理员；同一种奖励已授予时返回已有记录，不重复插入）。
     *
     * @throws AwardException 未登录 / 无权限 / 用户不存在 / 奖励种类未知 / 没有这条记录
     */
    public WikiUserAward grant(long actorUserId, boolean admin, long targetUserId, String awardId,
                               String comment) {
        requireAdmin(actorUserId, admin);
        if (users == null || users.byId(targetUserId).isEmpty()) {
            throw new AwardException(AwardException.USER_NOT_FOUND);
        }
        String id = Strings.trimToEmpty(awardId);
        if (!isKnown(id)) {
            throw new AwardException(AwardException.UNKNOWN_AWARD);
        }
        WikiUserAward existing = awards.byUser(targetUserId, 0).stream()
                .filter(row -> id.equals(row.getAwardId()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        WikiUserAward award = new WikiUserAward(null, targetUserId, id,
                truncate(Strings.trimToEmpty(comment), COMMENT_LIMIT), actorUserId, LocalDateTime.now());
        return awards.insert(award);
    }

    /**
     * 撤销奖励（管理员）。
     *
     * @throws AwardException 未登录 / 无权限 / 记录不存在
     */
    public boolean revoke(long actorUserId, boolean admin, long awardRowId) {
        requireAdmin(actorUserId, admin);
        WikiUserAward award = awards.find(awardRowId)
                .orElseThrow(() -> new AwardException(AwardException.NOT_FOUND));
        awards.delete(award.getId());
        return true;
    }

    /** 奖励显示名（{@code socialProfile.award.<id>}）。 */
    public String awardName(String awardId) {
        return support.awardName(awardId);
    }

    private static void requireAdmin(long actorUserId, boolean admin) {
        if (actorUserId <= 0) {
            throw new AwardException(AwardException.LOGIN_REQUIRED);
        }
        if (!admin) {
            throw new AwardException(AwardException.FORBIDDEN);
        }
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * 奖励操作失败：{@link #getMessage()} 是**消息键**（调用方本地化）。
     */
    public static class AwardException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** 需要登录。 */
        public static final String LOGIN_REQUIRED = "permissions.loginRequired";
        /** 只有管理员能授予 / 撤销奖励。 */
        public static final String FORBIDDEN = "socialProfile.error.awardForbidden";
        /** 目标用户不存在。 */
        public static final String USER_NOT_FOUND = "socialProfile.error.userNotFound";
        /** 奖励种类未知。 */
        public static final String UNKNOWN_AWARD = "socialProfile.error.awardUnknown";
        /** 授予记录不存在。 */
        public static final String NOT_FOUND = "socialProfile.error.awardNotFound";

        public AwardException(String messageKey) {
            super(messageKey);
        }
    }
}
