package cc.thonly.wikikernel.common.service;

import cc.thonly.wikikernel.common.extension.Viewer;

/**
 * 当前请求用户桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 插件在做「按用户决定的行为」时用它，例如 Gadgets 按用户组判断 {@code rights=…}、
 * 评论区判断登录状态、私信判断收件人。
 */
public interface CurrentUser {

    /** 当前用户（匿名时为 {@link Viewer#ANONYMOUS}）。 */
    Viewer viewer();

    /** 当前用户 ID（匿名 0）。 */
    default long id() {
        return viewer().id();
    }

    /** 当前用户名（匿名为空串）。 */
    default String name() {
        return viewer().name();
    }

    /** 是否已登录。 */
    default boolean loggedIn() {
        return viewer().loggedIn();
    }

    /** 当前用户是否属于某个用户组（组名按站点语言，如「管理员」）。 */
    default boolean inGroup(String group) {
        return viewer().inGroup(group);
    }
}
