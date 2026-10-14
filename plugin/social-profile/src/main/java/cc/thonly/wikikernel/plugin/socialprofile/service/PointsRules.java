package cc.thonly.wikikernel.plugin.socialprofile.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 给分规则与等级阈值表（仿 MediaWiki SocialProfile 的 {@code UserStats} / {@code UserLevel}），
 * 由 {@code SocialProfileSupport.pointsRules()} 从站点设置 / 插件配置读出。
 *
 * <h2>给分规则（全部可配置）</h2>
 * <ul>
 *     <li>浏览词条（{@link #pageViewPoints()}）：默认 <b>1</b> 分，键
 *     {@code socialProfile.points.pageView}（插件配置短键 {@code points.pageView}）；
 *     同一个用户对同一个页面在 {@link #viewCooldownSeconds()} 秒内只计一次（默认 300 秒）；</li>
 *     <li>在别人的留言板留言 / 回复（{@link #boardPostPoints()}）：默认 <b>5</b> 分，
 *     键 {@code socialProfile.points.boardPost}（{@code points.boardPost}）；</li>
 *     <li>加好友成功（接受请求时双方各得，{@link #friendPoints()}）：默认 <b>10</b> 分，
 *     键 {@code socialProfile.points.friend}（{@code points.friend}）；</li>
 *     <li>送出一份礼物（{@link #giftPoints()}）：默认 <b>3</b> 分，
 *     键 {@code socialProfile.points.gift}（{@code points.gift}）；</li>
 *     <li>保存个人资料（{@link #profileEditPoints()}）：默认 <b>2</b> 分，
 *     键 {@code socialProfile.points.profileEdit}（{@code points.profileEdit}）。</li>
 * </ul>
 * 总开关：{@code socialProfile.points.enabled}（插件配置 {@code points.enabled}，默认开启）；
 * 任何一项配成 {@code 0} 或负数 = 该动作不给分。
 *
 * <h2>等级阈值表</h2>
 * 默认（{@link #DEFAULT_LEVELS}）：1 级 = 0 分、2 级 = 50 分、3 级 = 200 分、4 级 = 500 分、5 级 = 1000 分。
 * 可用 {@code socialProfile.points.levels}（插件配置 {@code points.levels}）覆盖，格式
 * {@code 等级:最低积分} 用 {@code ;} 或 {@code ,} 分隔，例如 {@code 1:0;2:100;3:500}；
 * 非法项被忽略，空表 / 全非法时回退默认表。
 * 等级名走消息键 {@code socialProfile.level.<n>}（没有这个键时用 {@code socialProfile.level.other} 带等级号）。
 */
public record PointsRules(boolean enabled,
                          int pageViewPoints,
                          int boardPostPoints,
                          int friendPoints,
                          int giftPoints,
                          int profileEditPoints,
                          int viewCooldownSeconds,
                          String levelsSpec) {

    /** 浏览词条的默认分值。 */
    public static final int DEFAULT_PAGE_VIEW = 1;
    /** 留言的默认分值。 */
    public static final int DEFAULT_BOARD_POST = 5;
    /** 加好友的默认分值（双方各得）。 */
    public static final int DEFAULT_FRIEND = 10;
    /** 送礼物的默认分值。 */
    public static final int DEFAULT_GIFT = 3;
    /** 保存个人资料的默认分值。 */
    public static final int DEFAULT_PROFILE_EDIT = 2;
    /** 同一用户对同一页面重复浏览的默认冷却（秒）。 */
    public static final int DEFAULT_VIEW_COOLDOWN_SECONDS = 300;

    /** 默认等级阈值表（{@code 等级:最低积分}）。 */
    public static final String DEFAULT_LEVELS = "1:0;2:50;3:200;4:500;5:1000";

    /** 空规则（全部给 0 分；单元测试与关闭时用）。 */
    public static final PointsRules DISABLED = new PointsRules(false, 0, 0, 0, 0, 0, 0, DEFAULT_LEVELS);

    public PointsRules {
        levelsSpec = levelsSpec == null || levelsSpec.isBlank() ? DEFAULT_LEVELS : levelsSpec.trim();
    }

    /** 该动作的分值（关闭给分 / 配成非正数时为 0）。 */
    public int pointsFor(Action action) {
        if (!enabled) {
            return 0;
        }
        int configured = switch (action) {
            case PAGE_VIEW -> pageViewPoints;
            case BOARD_POST -> boardPostPoints;
            case FRIEND -> friendPoints;
            case GIFT -> giftPoints;
            case PROFILE_EDIT -> profileEditPoints;
        };
        return Math.max(configured, 0);
    }

    /** 等级阈值表（按最低积分升序、等级去重后的 {@code 等级:最低积分} 列表）。 */
    public List<Level> levels() {
        List<Level> parsed = parse(levelsSpec);
        if (parsed.isEmpty()) {
            // 整份配置都非法 → 回退默认阈值表（与类注释一致）
            parsed = parse(DEFAULT_LEVELS);
        }
        if (parsed.isEmpty()) {
            parsed.add(new Level(1, 0));
        }
        parsed.sort(Comparator.comparingLong(Level::minPoints).thenComparingInt(Level::level));
        return List.copyOf(parsed);
    }

    /** 解析一份 {@code 等级:最低积分} 规格（非法项忽略；全都非法时返回空表）。 */
    private static List<Level> parse(String spec) {
        List<Level> parsed = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return parsed;
        }
        for (String item : spec.split("[;,\\r\\n]+")) {
            String text = item.trim();
            if (text.isEmpty()) {
                continue;
            }
            int colon = text.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                int level = Integer.parseInt(text.substring(0, colon).trim());
                long threshold = Long.parseLong(text.substring(colon + 1).trim());
                if (level >= 1 && threshold >= 0 && parsed.stream().noneMatch(row -> row.level() == level)) {
                    parsed.add(new Level(level, threshold));
                }
            } catch (NumberFormatException ignored) {
                // 非法项忽略，其余项照常
            }
        }
        return parsed;
    }

    /** 某个积分对应的等级（取「最低积分 <= points」里最高的一级；低于最低阈值时用最低等级）。 */
    public int levelFor(long points) {
        List<Level> levels = levels();
        int level = levels.get(0).level();
        for (Level row : levels) {
            if (points >= row.minPoints()) {
                level = Math.max(level, row.level());
            }
        }
        return level;
    }

    /** 达到该等级所需的最低积分（表里没有这个等级时返回该等级之前的最高门槛）。 */
    public long minPointsFor(int level) {
        long min = 0;
        for (Level row : levels()) {
            if (row.level() <= level) {
                min = Math.max(min, row.minPoints());
            }
        }
        return min;
    }

    /** 可给分的动作（键名见类 javadoc 与 {@code socialProfile.points.*}）。 */
    public enum Action {
        /** 浏览词条。 */
        PAGE_VIEW,
        /** 留言 / 回复。 */
        BOARD_POST,
        /** 加好友成功。 */
        FRIEND,
        /** 送礼物。 */
        GIFT,
        /** 保存个人资料。 */
        PROFILE_EDIT
    }

    /** 等级与它的最低积分。 */
    public record Level(int level, long minPoints) {
    }
}
