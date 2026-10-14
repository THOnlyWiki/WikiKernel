package cc.thonly.wikikernel.common.extension;

import java.util.Set;

/**
 * 当前浏览器用户（页面渲染 / 钩子上下文里用）：
 * 匿名用户为 {@link #ANONYMOUS}（{@code id = 0}、空用户名、{@code anonymous = true}）。
 *
 * @param id        用户 ID（匿名 / 不存在为 0）
 * @param name      用户名（匿名为空串）
 * @param anonymous 是否匿名
 * @param groups    用户组名集合（匿名 / 未取到时为空集合）
 * @param bot       是否机器人（「机器人」用户组；匿名恒为 false）
 */
public record Viewer(long id, String name, boolean anonymous, Set<String> groups, boolean bot) {

    /** 匿名访问者。 */
    public static final Viewer ANONYMOUS = new Viewer(0, "", true, Set.of(), false);

    public Viewer {
        name = name == null ? "" : name;
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }

    /** 便捷构造：非机器人。 */
    public static Viewer of(long id, String name, Set<String> groups) {
        return new Viewer(id, name, id <= 0, groups, false);
    }

    /** 是否属于某个用户组（组名按站点语言，如「管理员」）。 */
    public boolean inGroup(String group) {
        return group != null && groups.contains(group);
    }

    /** 是否已登录。 */
    public boolean loggedIn() {
        return !anonymous && id > 0;
    }
}
