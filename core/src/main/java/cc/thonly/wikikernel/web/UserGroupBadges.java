package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.i18n.PermissionGroupLabels;
import cc.thonly.wikikernel.service.PermissionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用户页资料卡的**用户组徽章**（仿 MediaWiki SocialProfile 用户页头部：只列显式 / 特权组，
 * 最多 {@value #MAX_BADGES} 个，多出来的折成「+N」）。core 侧的数据加工放在这里，
 * 模板 {@code fragments/user-profile.html} 只做渲染（见 {@code web/UserProfileAdvice}）。
 *
 * <h2>隐式组（人人都有，资料卡不显示）</h2>
 * 与 {@link PermissionService#IMPLICIT_GROUPS} 只含「用户」不同，
 * 资料卡还要挡掉「编辑满 N 次自动授予」的两类自动组（对齐 MediaWiki：{@code user} /
 * {@code autoconfirmed} / {@code emailconfirmed} 都不是「身份徽章」）。清单同时收录
 * **库里实际会出现的稳定中文组名**与 **MediaWiki 风格英文别名**（core 的
 * {@code MwConfigAdvice} 会把中文组名映射成英文别名，见 {@code wgUserGroups}）：
 * <ul>
 *     <li>基础组：{@code 用户} / {@code *} / {@code user}</li>
 *     <li>编辑自动授予组：{@code 自动确定用户} / {@code autoconfirmed}</li>
 *     <li>邮箱确认组：{@code 邮箱确认用户} / {@code emailconfirmed}</li>
 * </ul>
 * <b>匹配方式</b>：组名先 {@code trim()} 再 {@code toLowerCase(Locale.ROOT)}，与上面这张
 * <b>白名单式定值表</b>做**全等**比较（不做子串匹配）——因此「管理员」「行政员」「核查员」
 * 这类显式组绝不会被误判，也不会因为清单里出现了 {@code user} 就把
 * {@code 超级用户} / {@code user-admin} 这类自定义组误伤（子串匹配才会踩这个坑）。
 * 代价是：自定义组若真的起名 {@code user} / {@code autoconfirmed}，也会被当成隐式组挡掉——
 * 这是有意为之（与 MediaWiki 保留组名同义）。
 *
 * <h2>上限与「+N」</h2>
 * 显式组按**当前语言的展示名**排序后取前 {@value #MAX_BADGES} 个作为徽章，其余折成「+N」，
 * 全量列表通过徽章行的 {@code data-summary} 属性暴露（模板用 {@code th:attr}，CSS 不感知）。
 * 一个显式组都没有时返回空结果，模板整段不渲染（不会出现空的 {@code .wiki-profile-meta} 组行）。
 */
@Component
public class UserGroupBadges {

    /** 资料卡最多渲染几个组徽章，其余折成「+N」。 */
    public static final int MAX_BADGES = 3;

    /** 隐式组清单：基础组 + 编辑自动授予组 + 邮箱确认组（中文稳定名与英文别名都收录）。 */
    private static final Set<String> IMPLICIT_ALIASES = Set.of(
            "用户", "*", "user",
            "自动确定用户", "autoconfirmed",
            "邮箱确认用户", "emailconfirmed");

    private final PermissionGroupLabels labels;

    public UserGroupBadges(PermissionGroupLabels labels) {
        this.labels = labels;
    }

    /** 一个组徽章的展示数据。 */
    public record Badge(String name, String label) {
    }

    /**
     * 资料卡的组徽章渲染结果。
     *
     * @param badges      要渲染的徽章（≤ {@value UserGroupBadges#MAX_BADGES} 个，按展示名排序）
     * @param hiddenCount 因为超过上限而折成「+N」的组数（0 = 没有省略）
     * @param summary     全部显式组的展示名（按同一顺序，逗号分隔；0 个显式组时为空串）
     */
    public record Result(List<Badge> badges, int hiddenCount, String summary) {

        /** 没有任何显式组（模板据此整段不渲染组标签行）。 */
        public boolean isEmpty() {
            return badges.isEmpty();
        }
    }

    /** 从用户组名集合里挑出显式组并折成徽章（{@code null} / 空集合 → 空结果）。 */
    public Result summarize(Collection<String> groupNames) {
        List<Badge> visible = visibleBadges(groupNames);
        int hidden = Math.max(visible.size() - MAX_BADGES, 0);
        List<Badge> shown = hidden == 0 ? visible : List.copyOf(visible.subList(0, MAX_BADGES));
        List<String> allLabels = new ArrayList<>(visible.size());
        for (Badge badge : visible) {
            allLabels.add(badge.label());
        }
        return new Result(shown, hidden, String.join(", ", allLabels));
    }

    /**
     * 显式组（去掉隐式组）的展示名列表，按当前语言的展示名排序（去重、稳定）。
     * 「+N」的 {@code title} 与 {@code data-summary} 用它列全。
     */
    public List<String> hiddenGroupLabels(Collection<String> groupNames) {
        List<Badge> visible = visibleBadges(groupNames);
        List<String> result = new ArrayList<>(visible.size());
        for (Badge badge : visible) {
            result.add(badge.label());
        }
        return result;
    }

    /** 显式组（非隐式）→ 徽章，按展示名排序；同一展示名只留一个。 */
    private List<Badge> visibleBadges(Collection<String> groupNames) {
        if (groupNames == null || groupNames.isEmpty()) {
            return List.of();
        }
        // 去重按原始组名（大小写不敏感），避免同组名的中英写法各出一个徽章
        Set<String> seen = new LinkedHashSet<>();
        List<Badge> badges = new ArrayList<>();
        for (String groupName : groupNames) {
            if (isImplicit(groupName)) {
                continue;
            }
            String name = groupName.trim();
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            badges.add(new Badge(name, labels.label(name)));
        }
        badges.sort((left, right) -> left.label().compareTo(right.label()));
        return List.copyOf(badges);
    }

    /** 是不是「人人都有」的隐式组（trim + 小写后与清单全等比较）。 */
    public static boolean isImplicit(String groupName) {
        return groupName != null && IMPLICIT_ALIASES.contains(groupName.trim().toLowerCase(Locale.ROOT));
    }
}
