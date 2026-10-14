package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiAccessRule;
import cc.thonly.wikikernel.common.data.WikiUser;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 访问控制：按「目标 + 动作」解析需要哪些用户组，服务于页面 / 特殊页 / 各类操作与列表过滤。
 * <p>
 * 规则存 {@code wiki_access_rule}，匹配优先级：精确标题 &gt; 最长前缀（目标以 {@code *} 结尾）&gt; {@code *}（全部）；
 * 没有任何规则时使用内置默认值（查看=所有人；编辑/创建/移动=登录用户；保护/删除=管理员）。
 * 规则可在「特殊:权限配置」网页管理，也可写在 LocalSettings.yaml（{@code wikikernel.permissions.rules}，启动时导入）。
 */
public interface AccessControlService {

    /** 全部规则（含默认目标 {@code *} 的规则）。 */
    List<WikiAccessRule> allRules();

    /** 按优先级查找命中的规则（不包含内置默认值）。 */
    Optional<WikiAccessRule> findRule(AccessAction action, @Nullable String target);

    /** 目标 + 动作实际需要的用户组字符串（规则命中值或内置默认值；* 或 | 分隔或空）。 */
    String requiredGroups(AccessAction action, @Nullable String target);

    /** 当前用户是否可以对该目标执行动作（匿名用 user == null）。 */
    boolean can(AccessAction action, @Nullable String target, @Nullable WikiUser user);

    /** 当前用户是否可以查看（{@link AccessAction#READ}）。 */
    default boolean canRead(@Nullable String target, @Nullable WikiUser user) {
        return can(AccessAction.READ, target, user);
    }

    /** 批量过滤：只保留当前用户可读的条目（列表页防标题泄露）。 */
    <T> List<T> filterReadable(List<T> items, Function<T, String> titleGetter, @Nullable WikiUser user);

    /** 新增或更新一条规则（按目标 + 动作去重）；groupNames 为 * / 空 / | 分隔的用户组。 */
    WikiAccessRule saveRule(String target, AccessAction action, String groupNames);

    /** 删除一条规则，返回是否存在。 */
    boolean deleteRule(long ruleId);

    /** 清空缓存并重新从数据库加载（保存 / 导入 / 测试用）。 */
    void reload();
}
