package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiAccessRule;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiAccessRuleMapper;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.web.WikiTitles;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 原子更新页面阅读 / 编辑规则，提交后才刷新共享权限缓存。 */
@Service
public class PageProtectionService {
    public static final String INHERIT = "__inherit__";
    public static final String DENY = "__deny__";
    private final WikiAccessRuleMapper rules;
    private final WikiPageService pages;
    private final PermissionService permissions;
    private final AccessControlService access;
    private final LogService logs;

    public PageProtectionService(WikiAccessRuleMapper rules, WikiPageService pages,
                                 PermissionService permissions, AccessControlService access, LogService logs) {
        this.rules = rules; this.pages = pages; this.permissions = permissions; this.access = access;
        this.logs = logs;
    }

    public String normalize(List<String> values) {
        if (values == null || values.isEmpty()) { throw new IllegalArgumentException("protect.invalidGroups"); }
        Set<String> selected = new LinkedHashSet<>(values);
        if (selected.size() == 1 && selected.contains(INHERIT)) { return null; }
        if (selected.size() == 1 && selected.contains(DENY)) { return ""; }
        if (selected.size() == 1 && selected.contains("*")) { return "*"; }
        Set<String> known = permissions.allTypes().stream().map(type -> type.getName()).collect(Collectors.toSet());
        if (!known.containsAll(selected)) { throw new IllegalArgumentException("protect.invalidGroups"); }
        return String.join(AccessAction.GROUP_SEPARATOR, selected);
    }

    @Transactional
    public String update(WikiUser user, String title, String readGroups, String editGroups) {
        String canonical = WikiTitles.normalize(title);
        boolean protectedFlag = PermissionService.GROUP_ADMIN.equals(editGroups);
        // 插件可接管没有 wiki_page 记录的标题；保护规则直接绑定标题，不创建空词条或修订。
        if (pages.setProtected(user, canonical, protectedFlag).isEmpty()) {
            logs.record(LogTypeConfig.PROTECT, user == null ? null : user.getId(), canonical, null,
                    protectedFlag ? "on" : "off");
        }
        setRule(canonical, AccessAction.READ, readGroups);
        setRule(canonical, AccessAction.EDIT, editGroups);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { access.reload(); }
        });
        return canonical;
    }

    private void setRule(String title, AccessAction action, String groups) {
        var query = Wrappers.<WikiAccessRule>lambdaQuery().eq(WikiAccessRule::getTarget, title)
                .eq(WikiAccessRule::getAction, action.id());
        WikiAccessRule existing = rules.selectOne(query);
        if (groups == null) {
            if (existing != null) { rules.deleteById(existing.getId()); }
        } else if (existing == null) {
            rules.insert(new WikiAccessRule(null, title, action.id(), groups));
        } else {
            existing.setGroupNames(groups); rules.updateById(existing);
        }
    }
}
