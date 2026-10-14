package cc.thonly.wikikernel.common.service;

/**
 * 用户写入桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 只读查询用 {@link UserStore}；这里只提供插件需要的**写入**：
 * 创建系统账号（插件启用时自动创建「滥用过滤器」这类机器人账号）与移除某个用户组。
 */
public interface UserWriter {

    /**
     * 确保系统账号存在：不存在则创建（随机密码，无法登录）并授予 {@code groupName} 用户组
     * （同时隐式拥有「用户」组）；已存在则不动它（不覆盖密码 / 权限）。
     *
     * @param username  账号名
     * @param groupName 要授予的用户组（组名按站点语言，如「机器人」）
     * @return 是否**新建**（已存在返回 false）
     */
    boolean ensureSystemAccount(String username, String groupName);

    /**
     * 移除用户的某个用户组（如滥用过滤器的 {@code blockautopromote} 移除「自动确定用户」）。
     *
     * @return 用户确实有该组并被移除时返回 true；用户不存在 / 没有该组返回 false
     */
    boolean revokeGroup(long userId, String groupName);
}
