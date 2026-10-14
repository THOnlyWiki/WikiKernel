package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.http.IpAddresses;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.BlockExpiry;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.UserService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code 特殊:封禁?target=用户名或IP} / {@code Special:Block}：封禁用户或 IP（可永久 / 定时、可禁邮件）。
 * 表单由 {@code /js/block.js} 提交到 {@code POST /api/blocks}；权限动作 {@code block}（默认管理员，可在 特殊:权限配置 调整）。
 */
@Component
public class BlockSpecialPage implements SpecialPage {

    /** 权限规则的目标（与 特殊:封禁列表 共用）：封禁 / 解封都检查它。 */
    public static final String SPECIAL_TITLE = "特殊:封禁";

    private final BlockService blockService;
    private final AccessControlService accessControlService;
    private final UserService userService;
    private final MessageSource messageSource;

    public BlockSpecialPage(BlockService blockService,
                            AccessControlService accessControlService,
                            UserService userService,
                            MessageSource messageSource) {
        this.blockService = blockService;
        this.accessControlService = accessControlService;
        this.userService = userService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "封禁";
    }

    @Override
    public String displayName() {
        return message("special.block");
    }

    @Override
    public List<String> aliases() {
        return List.of("Block", "BlockIP", "BlockUser");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/block";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        boolean canBlock = accessControlService.can(AccessAction.BLOCK, SPECIAL_TITLE, user);
        model.put("canBlock", canBlock);
        model.put("blockLogin", user == null);

        String target = Strings.trimToEmpty(request.parameters().get("target"));
        model.put("target", target);
        model.put("blockedSuccess", "1".equals(request.parameters().get("blocked")));
        model.put("unblockedSuccess", "1".equals(request.parameters().get("unblocked")));
        model.put("expiryOptions", BlockExpiry.IDS.stream()
                .map(id -> Map.<String, Object>of("id", id, "label", message("block.expiry." + id)))
                .toList());
        if (!target.isEmpty()) {
            Optional<WikiBlock> existing = blockService.findActiveByTarget(target);
            existing.ifPresent(block -> {
                model.put("existingBlock", block);
                Long by = block.getByUserId();
                model.put("existingBlocker", by == null ? null : userService.usernames(List.of(by)).get(by));
            });
            String type = existing.map(WikiBlock::getTargetType)
                    .orElse(IpAddresses.isIpOrCidr(target) ? "ip" : "user");
            model.put("targetIsUser", "user".equals(type));
        }
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
