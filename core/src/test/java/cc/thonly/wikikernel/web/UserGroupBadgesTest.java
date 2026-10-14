package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.i18n.PermissionGroupLabels;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资料卡的用户组徽章规则（{@link UserGroupBadges}）：
 * 隐式组过滤（用户 / 自动确定用户 / 邮箱确认用户 + 英文别名）、最多 3 个徽章 + 「+N」、
 * 普通用户不会被误判成管理员。
 */
class UserGroupBadgesTest {

    private final UserGroupBadges badges = new UserGroupBadges(new PermissionGroupLabels(new StaticMessageSource()));

    @Test
    void implicitGroupsAreNeverBadges() {
        for (String implicit : List.of("用户", "*", "user", "USER", " user ",
                "自动确定用户", "autoconfirmed", "AutoConfirmed", "邮箱确认用户", "emailconfirmed")) {
            assertTrue(UserGroupBadges.isImplicit(implicit), implicit + " 应算隐式组");
        }
        // 显式组 / 名字里只是**含** user 的自定义组都不能被误判（全等匹配，不做子串匹配）
        for (String explicit : List.of("管理员", "行政员", "sysop", "bureaucrat", "超级用户", "user-admin")) {
            assertFalse(UserGroupBadges.isImplicit(explicit), explicit + " 不应算隐式组");
        }
    }

    @Test
    void plainUserHasNoBadgeSection() {
        UserGroupBadges.Result result = badges.summarize(List.of("用户", "自动确定用户", "邮箱确认用户"));

        assertTrue(result.isEmpty(), result.toString());
        assertEquals(0, result.hiddenCount());
        assertEquals("", result.summary());
    }

    @Test
    void explicitGroupsAreCappedAtThreeAndFoldedIntoMore() {
        UserGroupBadges.Result result = badges.summarize(List.of(
                "用户", "自动确定用户", "管理员", "行政员", "核查员", "界面管理员", "评论管理员"));

        assertEquals(3, result.badges().size(), result.toString());
        assertEquals(2, result.hiddenCount(), "5 个显式组 - 3 个徽章 = 2 个折成「+N」：" + result);
        assertEquals("核查员, 界面管理员, 管理员, 行政员, 评论管理员", result.summary(),
                "全量列表按展示名排序：" + result);
        // 显示的 3 个徽章 + 「+2」覆盖全部 5 个显式组
        List<String> shown = result.badges().stream().map(UserGroupBadges.Badge::label).toList();
        assertEquals(result.summary().split(", ").length, shown.size() + result.hiddenCount());
        for (String explicit : List.of("管理员", "行政员", "核查员", "界面管理员", "评论管理员")) {
            assertTrue(result.summary().contains(explicit), "全量列表里有 " + explicit + "：" + result);
        }
        // 隐式组一个都不出现
        for (String implicit : List.of("用户", "自动确定用户", "邮箱确认用户")) {
            assertFalse(result.summary().contains(implicit), result.summary());
        }
    }

    @Test
    void duplicateNamesOnlyProduceOneBadge() {
        UserGroupBadges.Result result = badges.summarize(List.of("管理员", "管理员", "ADMIN-unused"));

        assertEquals(2, result.badges().size(), result.toString());
        assertEquals(0, result.hiddenCount());
    }

    @Test
    void emptyOrNullInputProducesEmptyResult() {
        assertTrue(badges.summarize(null).isEmpty());
        assertTrue(badges.summarize(List.of()).isEmpty());
    }

    @Test
    void badgeLabelsFallBackToStableGroupNames() {
        // 没有当前语言的 permission.group.* 文案时原样返回稳定组名（与 PermissionGroupLabels 的语义一致）
        assertEquals("管理员", badges.summarize(List.of("管理员")).badges().get(0).label());
        assertEquals("sysop", badges.summarize(List.of("sysop")).badges().get(0).label());
    }
}
