package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.log.LogFormatter;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code 特殊:用户权限?name=用户名} / {@code Special:UserRights}：
 * 仿 MediaWiki Special:UserRights 的布局：
 * 「选择用户」输入框 → 「您可以更改的用户组」（勾选组 + 每组「期限」下拉 + 「原因」+ 保存用户组；
 * 只读用户看到「查看用户组」）→「自动用户组」→「用户权限日志」（来自通用日志系统的 rights 类型）。
 */
@Component
public class UserRightsSpecialPage implements SpecialPage {

    /** 查询参数名：用户名。 */
    public static final String PARAM_NAME = "name";

    /** 日志显示条数。 */
    private static final int LOG_LIMIT = 50;

    /** datetime-local 输入框的格式（yyyy-MM-ddTHH:mm）。 */
    private static final DateTimeFormatter EXPIRY_INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final UserService userService;
    private final PermissionService permissionService;
    private final LogService logService;
    private final LogFormatter logFormatter;
    private final UserToolsSidebar userToolsSidebar;
    private final MessageSource messageSource;

    public UserRightsSpecialPage(UserService userService,
                                 PermissionService permissionService,
                                 LogService logService,
                                 LogFormatter logFormatter,
                                 UserToolsSidebar userToolsSidebar,
                                 MessageSource messageSource) {
        this.userService = userService;
        this.permissionService = permissionService;
        this.logService = logService;
        this.logFormatter = logFormatter;
        this.userToolsSidebar = userToolsSidebar;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "用户权限";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.userRights", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("UserRights", "用户权限管理");
    }

    @Override
    public String template() {
        return "special/user-rights";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String raw = request.parameters().get(PARAM_NAME);
        String username = Strings.trimToEmpty(raw);
        if (username.isEmpty()) {
            model.put("nameRequired", raw != null);
            return; // 只显示「选择用户」
        }

        Optional<WikiUser> found = userService.findByUsername(username);
        if (found.isEmpty()) {
            model.put("userNotFound", true);
            model.put("lookupName", username);
            return;
        }

        WikiUser user = found.get();
        WikiUser current = request.user();
        boolean canEdit = current != null && permissionService.canManage(current.getId());

        model.put("user", user);
        // 每个用户组一行：是否已授予（含已过期）/ 有效期（datetime-local 值）/ 是否已过期
        Map<String, LocalDateTime> expiryByName = new LinkedHashMap<>();
        for (PermissionService.Membership membership : permissionService.membershipsOf(user.getId())) {
            expiryByName.put(membership.name(), membership.expiresAt());
        }
        LocalDateTime now = LocalDateTime.now();
        model.put("rightsRows", permissionService.changeableTypes().stream()
                .map(type -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    LocalDateTime expiry = expiryByName.get(type.getName());
                    row.put("name", type.getName());
                    row.put("checked", expiryByName.containsKey(type.getName()));
                    row.put("expiry", expiry == null ? "" : expiry.format(EXPIRY_INPUT));
                    row.put("expired", expiry != null && !expiry.isAfter(now));
                    return row;
                })
                .toList());
        model.put("automaticGroups", permissionService.automaticGroupsOf(user.getId()));
        model.put("autoConfirmEdits", PermissionService.AUTO_CONFIRM_EDITS);
        model.put("canEdit", canEdit);
        model.put("title", user.getUsername());
        model.put("talkUrl", WikiUrls.pagePath("用户讨论:" + user.getUsername()) + "?action=edit");
        model.put("contributionsUrl", WikiUrls.pagePath(UserToolsSidebar.CONTRIBUTIONS_PAGE)
                + "?name=" + URLEncoder.encode(user.getUsername(), StandardCharsets.UTF_8));
        model.put("logRows", logFormatter.format(
                logService.recentForTarget(LogTypeConfig.RIGHTS, user.getId(), canEdit, LOG_LIMIT)));
        model.put("sidebar", userToolsSidebar.withUserTools(user.getUsername(), false, false, false, canEdit));
    }
}
