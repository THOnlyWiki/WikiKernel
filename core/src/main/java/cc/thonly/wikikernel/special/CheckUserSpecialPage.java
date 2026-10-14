package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.log.LogFormatter;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code 特殊:用户核查} / {@code Special:CheckUser}（仿 MediaWiki CheckUser）：
 * <ul>
 *     <li>按用户查：TA 最近的操作记录（IP / User-Agent / 动作）；</li>
 *     <li>按 IP 或 IPv4 CIDR 查：该地址段最近是哪些账号在操作。</li>
 * </ul>
 * 数据来自通用日志（每条日志都记录 IP / UA，支持从 Cloudflare 等 CDN 取真实 IP）。
 * 使用权限由「核查员」组决定。
 */
@Component
public class CheckUserSpecialPage implements SpecialPage {

    /** 查询最多返回多少条。 */
    private static final int LIMIT = 500;

    public static final String PARAM_USER = "user";
    public static final String PARAM_IP = "ip";

    private final PermissionService permissionService;
    private final UserService userService;
    private final LogService logService;
    private final LogFormatter logFormatter;
    private final MessageSource messageSource;

    public CheckUserSpecialPage(PermissionService permissionService,
                                UserService userService,
                                LogService logService,
                                LogFormatter logFormatter,
                                MessageSource messageSource) {
        this.permissionService = permissionService;
        this.userService = userService;
        this.logService = logService;
        this.logFormatter = logFormatter;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "用户核查";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.checkUser", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("CheckUser");
    }

    @Override
    public String template() {
        return "special/check-user";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser viewer = request.user();
        boolean canCheck = viewer != null
                && permissionService.has(viewer.getId(), PermissionService.GROUP_CHECKUSER);
        model.put("canCheck", canCheck);

        String userQuery = Strings.trimToEmpty(request.parameters().get(PARAM_USER));
        String ipQuery = Strings.trimToEmpty(request.parameters().get(PARAM_IP));
        model.put("userQuery", userQuery);
        model.put("ipQuery", ipQuery);
        if (!canCheck) {
            return;
        }

        if (!userQuery.isEmpty()) {
            Optional<WikiUser> target = userService.findByUsername(userQuery);
            model.put("userFound", target.isPresent());
            if (target.isPresent()) {
                model.put("userRows", logFormatter.format(
                        logService.recentByActor(target.get().getId(), true, LIMIT)));
            }
        }
        if (!ipQuery.isEmpty()) {
            model.put("ipRows", logFormatter.format(logService.recentByIp(ipQuery, LIMIT)));
        }
    }
}
