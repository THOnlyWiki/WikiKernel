package cc.thonly.wikikernel.i18n;

import cc.thonly.wikikernel.service.PermissionService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 用户组名的本地化显示：
 * <ul>
 *     <li>数据库里存的是稳定组名（内置组为中文，如「管理员」），展示时按当前语言映射到
 *     {@code permission.group.*} 文案；</li>
 *     <li>自定义组没有对应文案时原样返回；</li>
 *     <li>URL / 表单值仍使用原始组名（稳定 id，不随语言变化）。</li>
 * </ul>
 * 模板里可直接用 {@code ${@permissionGroupLabels.label(group)}}。
 */
@Component
public class PermissionGroupLabels {

    private static final Map<String, String> BUILT_IN_KEYS = Map.ofEntries(
            Map.entry(PermissionService.GROUP_USER, "permission.group.user"),
            Map.entry(PermissionService.GROUP_AUTOCONFIRMED, "permission.group.autoconfirmed"),
            Map.entry(PermissionService.GROUP_EMAIL_CONFIRMED, "permission.group.emailconfirmed"),
            Map.entry(PermissionService.GROUP_ADMIN, "permission.group.admin"),
            Map.entry(PermissionService.GROUP_INTERFACE_ADMIN, "permission.group.interfaceadmin"),
            Map.entry(PermissionService.GROUP_WIDGET_EDITOR, "permission.group.widgeteditor"),
            Map.entry(PermissionService.GROUP_GADGET_ADMIN, "permission.group.gadgetadmin"),
            Map.entry(PermissionService.GROUP_BUREAUCRAT, "permission.group.bureaucrat"),
            Map.entry(PermissionService.GROUP_CHECKUSER, "permission.group.checkuser"),
            Map.entry(PermissionService.GROUP_RENAME_USER, "permission.group.renameuser"),
            Map.entry(PermissionService.GROUP_COMMENT_ADMIN, "permission.group.commentadmin"),
            Map.entry(PermissionService.GROUP_MODERATION_EXEMPT, "permission.group.moderatorexempt"),
            Map.entry(PermissionService.GROUP_PAGE_ADMIN, "permission.group.pageadmin"),
            Map.entry(PermissionService.GROUP_BOT, "permission.group.bot"));

    private final MessageSource messageSource;

    public PermissionGroupLabels(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 单个组名 → 展示名（未内置的组原样返回，{@code null} 返回空串）。 */
    public String label(String groupName) {
        if (groupName == null) {
            return "";
        }
        String key = BUILT_IN_KEYS.get(groupName);
        return key == null ? groupName
                : messageSource.getMessage(key, null, groupName, LocaleContextHolder.getLocale());
    }

    /** 一组组名 → 展示名列表（保持原顺序）。 */
    public List<String> labels(Collection<String> groupNames) {
        if (groupNames == null || groupNames.isEmpty()) {
            return List.of();
        }
        return groupNames.stream().map(this::label).toList();
    }
}
