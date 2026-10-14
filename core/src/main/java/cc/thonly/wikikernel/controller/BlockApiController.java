package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.ActionHookService;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.BlockExpiry;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.special.BlockSpecialPage;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.RequestActionContext;
import cc.thonly.wikikernel.web.Viewers;
import cc.thonly.wikikernel.web.WikiUrls;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封禁 API（{@code @RestController}）：封禁 / 更新封禁（{@code POST /api/blocks}）与解封
 * （{@code POST /api/blocks/unblock}）。权限动作 {@code block}（默认管理员，可配置）。
 */
@RestController
@RequestMapping("/api/blocks")
public class BlockApiController {

    private final BlockService blockService;
    private final AccessControlService accessControlService;
    private final PermissionService permissionService;
    private final ActionHookService actionHookService;
    private final MessageSource messageSource;

    public BlockApiController(BlockService blockService,
                              AccessControlService accessControlService,
                              PermissionService permissionService,
                              ActionHookService actionHookService,
                              MessageSource messageSource) {
        this.blockService = blockService;
        this.accessControlService = accessControlService;
        this.permissionService = permissionService;
        this.actionHookService = actionHookService;
        this.messageSource = messageSource;
    }

    /** 封禁用户 / IP（同一目标已有生效封禁时更新它）。 */
    @PostMapping
    public AjaxResult<Map<String, Object>> block(
            @RequestParam("target") String target,
            @RequestParam(value = "expiry", required = false, defaultValue = "infinite") String expiry,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "preventEmail", required = false, defaultValue = "false") boolean preventEmail,
            @RequestParam(value = "preventCreate", required = false, defaultValue = "false") boolean preventCreate,
            @RequestParam(value = "anonOnly", required = false, defaultValue = "false") boolean anonOnly,
            HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        if (!accessControlService.can(AccessAction.BLOCK, BlockSpecialPage.SPECIAL_TITLE, user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("block.forbidden"));
        }
        String value = Strings.trimToEmpty(target);
        if (value.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("block.targetRequired"));
        }
        if (!BlockExpiry.isKnown(expiry)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("block.invalidExpiry"));
        }
        LocalDateTime expiresAt = BlockExpiry.resolve(expiry, LocalDateTime.now());
        // 动作前钩子（ActionGuard，如插件版滥用过滤器的 action=block）
        ActionHookService.Decision blockDecision = actionHookService.beforeAction(RequestActionContext.builder(
                        request, Viewers.of(user, permissionService), "block")
                .target(value)
                .summary(reason)
                .data("target", value)
                .data("expiry", expiry)
                .data("expiresAt", expiresAt)
                .data("reason", Strings.trimToEmpty(reason))
                .data("preventEmail", preventEmail)
                .data("preventCreate", preventCreate)
                .data("anonOnly", anonOnly)
                .build());
        if (blockDecision.blocked()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, blockDecision.message());
        }
        WikiBlock block = blockService.block(user, value, expiresAt, reason, preventEmail, preventCreate, anonOnly);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", block.getId());
        data.put("target", block.getTarget());
        data.put("targetType", block.getTargetType());
        data.put("expiresAt", block.getExpiresAt());
        data.put("listUrl", WikiUrls.pagePath("特殊:封禁列表"));
        if (!blockDecision.warnings().isEmpty()) {
            data.put("warnings", blockDecision.warnings());
        }
        // 封禁后回到封禁页（带成功提示；同页即可看到解封按钮）
        data.put("redirectUrl", WikiUrls.pagePath("特殊:封禁")
                + "?target=" + encodeParameter(block.getTarget()) + "&blocked=1");
        return AjaxResult.success(message("block.success", block.getTarget()), data);
    }

    private static String encodeParameter(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 解封。 */
    @PostMapping("/unblock")
    public AjaxResult<Void> unblock(@RequestParam("id") long id, HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        if (!accessControlService.can(AccessAction.BLOCK, BlockSpecialPage.SPECIAL_TITLE, user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("block.forbidden"));
        }
        if (!blockService.unblock(user, id)) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("block.notFound"));
        }
        return AjaxResult.success(message("block.unblockSuccess"), null);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
