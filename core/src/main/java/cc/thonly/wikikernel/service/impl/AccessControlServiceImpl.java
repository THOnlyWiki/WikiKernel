package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiAccessRule;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.PermissionProperties;
import cc.thonly.wikikernel.db.mapper.WikiAccessRuleMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.web.CodePages;
import cc.thonly.wikikernel.web.ConfigPages;
import cc.thonly.wikikernel.web.WikiNamespaces;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 访问控制实现：内存缓存规则，匹配优先级「精确标题 &gt; 最长前缀 &gt; 全部」，
 * 未命中时用内置默认值兜底；启动时导入 LocalSettings 里配置的规则（与网页共用同一份数据）。
 */
@Service
@Order(2) // Auto-DDL（@Order(0)）与旧数据迁移（@Order(1)）之后再导规则
public class AccessControlServiceImpl implements AccessControlService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccessControlServiceImpl.class);

    /** 没有任何规则命中时使用的内置默认值（保持改造前的行为）。 */
    private static final Map<AccessAction, String> DEFAULT_GROUPS = Map.of(
            AccessAction.READ, AccessAction.EVERYONE,
            AccessAction.EDIT, PermissionService.GROUP_USER,
            AccessAction.CREATE, PermissionService.GROUP_USER,
            AccessAction.MOVE, PermissionService.GROUP_USER,
            AccessAction.UPLOAD, PermissionService.GROUP_USER,
            AccessAction.PROTECT, PermissionService.GROUP_ADMIN,
            AccessAction.DELETE, PermissionService.GROUP_ADMIN,
            AccessAction.PURGE, PermissionService.GROUP_ADMIN,
            AccessAction.BLOCK, PermissionService.GROUP_ADMIN);

    private final WikiAccessRuleMapper accessRuleMapper;
    private final PermissionService permissionService;
    private final PermissionProperties properties;

    private volatile List<WikiAccessRule> cache;

    public AccessControlServiceImpl(WikiAccessRuleMapper accessRuleMapper,
                                    PermissionService permissionService,
                                    PermissionProperties properties) {
        this.accessRuleMapper = accessRuleMapper;
        this.permissionService = permissionService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        importConfiguredRules();
    }

    @Override
    public List<WikiAccessRule> allRules() {
        return loaded();
    }

    @Override
    public Optional<WikiAccessRule> findRule(AccessAction action, String target) {
        String value = Strings.trimToEmpty(target);
        WikiAccessRule exact = null;
        WikiAccessRule prefix = null;
        int prefixLength = -1;
        WikiAccessRule star = null;
        for (WikiAccessRule rule : loaded()) {
            if (!action.id().equals(rule.getAction())) {
                continue;
            }
            String ruleTarget = Strings.trimToEmpty(rule.getTarget());
            if (AccessAction.EVERYONE.equals(ruleTarget)) {
                star = rule;
            } else if (ruleTarget.endsWith("*")) {
                String match = ruleTarget.substring(0, ruleTarget.length() - 1);
                if (value.startsWith(match) && match.length() > prefixLength) {
                    prefix = rule;
                    prefixLength = match.length();
                }
            } else if (ruleTarget.equals(value)) {
                exact = rule;
            }
        }
        return Optional.ofNullable(exact != null ? exact : prefix != null ? prefix : star);
    }

    @Override
    public String requiredGroups(AccessAction action, String target) {
        return findRule(action, target)
                .map(WikiAccessRule::getGroupNames)
                .orElseGet(() -> builtinGroups(action, target));
    }

    /**
     * 内置默认值：
     * <ul>
     *     <li>CSS / JS 页面（{@code *.css} / {@code *.js}）：编辑 / 创建 / 移动默认只有「界面管理员」
     *     （仿 MediaWiki 的 interface-admin / editsitecss / editsitejs，可用访问规则覆盖）；</li>
     *     <li>微件（Widget）命名空间仿 MediaWiki 的 editwidgets：编辑 / 创建 / 移动默认只有「小部件编辑者」
     *     （管理员不豁免；可在 特殊:权限配置 加规则覆盖）；</li>
     *     <li>WikiKernel（界面）命名空间：编辑 / 创建 / 移动默认只有管理员（可在 特殊:权限配置 覆盖）；</li>
     *     <li>标题黑 / 白名单配置页：查看默认只有管理员（其他用户按「页面不存在」处理，不暴露规则）。</li>
     * </ul>
     */
    private static String builtinGroups(AccessAction action, String target) {
        String title = Strings.trimToEmpty(target);
        boolean write = action == AccessAction.EDIT || action == AccessAction.CREATE || action == AccessAction.MOVE;
        if (write && (WikiNamespaces.isGadget(title)
                || title.equalsIgnoreCase("WikiKernel:Gadgets-definition"))) {
            return PermissionService.GROUP_GADGET_ADMIN;
        }
        if (write && CodePages.isInterfacePage(title)) {
            return PermissionService.GROUP_INTERFACE_ADMIN;
        }
        if (write && WikiNamespaces.isWikiKernel(title)) {
            return PermissionService.GROUP_ADMIN;
        }
        if (ConfigPages.hiddenWhenUnreadable(title) && action == AccessAction.READ) {
            return PermissionService.GROUP_ADMIN;
        }
        if (write && WikiNamespaces.isWidget(target)) {
            // 微件页的编辑权等同于全站脚本权：默认只有「小部件编辑者」（仿 MediaWiki 的 editwidgets）
            return PermissionService.GROUP_WIDGET_EDITOR;
        }
        return DEFAULT_GROUPS.get(action);
    }

    @Override
    public boolean can(AccessAction action, String target, WikiUser user) {
        return canWithGroups(action, target, groupsOf(user));
    }

    @Override
    public <T> List<T> filterReadable(List<T> items, Function<T, String> titleGetter, WikiUser user) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Set<String> userGroups = groupsOf(user);
        List<T> result = new ArrayList<>();
        for (T item : items) {
            String title = titleGetter.apply(item);
            if (title != null && canWithGroups(AccessAction.READ, title, userGroups)) {
                result.add(item);
            }
        }
        return result;
    }

    @Override
    public WikiAccessRule saveRule(String target, AccessAction action, String groupNames) {
        String normalizedTarget = normalizeTarget(target);
        String normalizedGroups = normalizeGroups(groupNames);
        WikiAccessRule existing = accessRuleMapper.selectOne(Wrappers.<WikiAccessRule>lambdaQuery()
                .eq(WikiAccessRule::getTarget, normalizedTarget)
                .eq(WikiAccessRule::getAction, action.id())
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setGroupNames(normalizedGroups);
            accessRuleMapper.updateById(existing);
            reload();
            return existing;
        }
        WikiAccessRule rule = new WikiAccessRule(null, normalizedTarget, action.id(), normalizedGroups);
        accessRuleMapper.insert(rule);
        reload();
        return rule;
    }

    @Override
    public boolean deleteRule(long ruleId) {
        boolean deleted = accessRuleMapper.deleteById(ruleId) > 0;
        if (deleted) {
            reload();
        }
        return deleted;
    }

    @Override
    public void reload() {
        synchronized (this) {
            cache = null;
        }
    }

    // ==================== 内部实现 ====================

    /** 用户实际生效的用户组：表内绑定 + 隐式基础组「用户」（注册即拥有）。 */
    private Set<String> groupsOf(WikiUser user) {
        if (user == null) {
            return null;
        }
        Set<String> groups = new LinkedHashSet<>(permissionService.namesOf(user.getId()));
        groups.add(PermissionService.GROUP_USER);
        return groups;
    }

    /** 规则命中后的判定；userGroups == null 表示匿名（没有用户组）。 */
    private boolean canWithGroups(AccessAction action, String target, Set<String> userGroups) {
        String groups = requiredGroups(action, target);
        if (groups == null) {
            return false;
        }
        String value = groups.trim();
        if (value.isEmpty()) {
            return false; // 无人（禁止）
        }
        if (AccessAction.EVERYONE.equals(value)) {
            return true; // 所有人（含匿名）
        }
        if (userGroups == null) {
            return false;
        }
        for (String group : value.split("\\" + AccessAction.GROUP_SEPARATOR)) {
            if (!group.isBlank() && userGroups.contains(group.trim())) {
                return true;
            }
        }
        return false;
    }

    private List<WikiAccessRule> loaded() {
        List<WikiAccessRule> current = cache;
        if (current == null) {
            synchronized (this) {
                current = cache;
                if (current == null) {
                    current = List.copyOf(accessRuleMapper.selectList(Wrappers.<WikiAccessRule>lambdaQuery()
                            .orderByAsc(WikiAccessRule::getAction)
                            .orderByAsc(WikiAccessRule::getTarget)));
                    cache = current;
                }
            }
        }
        return current;
    }

    /** 启动时把 LocalSettings 里的规则写入数据库（同目标 + 动作覆盖，网页新增的规则不受影响）。 */
    private void importConfiguredRules() {
        if (properties.getRules().isEmpty()) {
            return;
        }
        for (PermissionProperties.AccessRuleConfig config : properties.getRules()) {
            Optional<AccessAction> action = AccessAction.byId(config.getAction());
            if (action.isEmpty() || Strings.isBlank(config.getTarget())) {
                log.warn("忽略非法访问规则配置：target={}, action={}", config.getTarget(), config.getAction());
                continue;
            }
            WikiAccessRule rule = saveRule(config.getTarget(), action.get(), Strings.trimToEmpty(config.getGroups()));
            log.info("已导入访问规则：{} {} → {}", rule.getTarget(), rule.getAction(),
                    rule.getGroupNames().isEmpty() ? "（无人）" : rule.getGroupNames());
        }
    }

    private static String normalizeTarget(String target) {
        String value = Strings.trimToEmpty(target);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("访问控制目标不能为空");
        }
        return value;
    }

    /** 归一化用户组字符串：* 原样；否则按 | 拆分、去空白后重新拼接（空 = 无人）。 */
    private static String normalizeGroups(String groupNames) {
        String value = Strings.trimToEmpty(groupNames);
        if (value.isEmpty() || AccessAction.EVERYONE.equals(value)) {
            return value;
        }
        Set<String> groups = new LinkedHashSet<>();
        for (String group : value.split("\\" + AccessAction.GROUP_SEPARATOR)) {
            if (!group.isBlank()) {
                groups.add(group.trim());
            }
        }
        return String.join(AccessAction.GROUP_SEPARATOR, groups);
    }
}
