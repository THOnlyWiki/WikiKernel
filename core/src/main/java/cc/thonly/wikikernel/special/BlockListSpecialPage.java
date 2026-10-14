package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.UserService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:封禁列表} / {@code Special:BlockList}：当前生效中的封禁，可解封。
 * 权限与 {@code 特殊:封禁} 共用 {@code block} 动作。
 */
@Component
public class BlockListSpecialPage implements SpecialPage {

    private final BlockService blockService;
    private final AccessControlService accessControlService;
    private final UserService userService;
    private final MessageSource messageSource;

    public BlockListSpecialPage(BlockService blockService,
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
        return "封禁列表";
    }

    @Override
    public String displayName() {
        return message("special.blockList");
    }

    @Override
    public List<String> aliases() {
        return List.of("BlockList", "BlockedIPs", "Ipblocklist");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/block-list";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        model.put("canBlock", accessControlService.can(AccessAction.BLOCK, BlockSpecialPage.SPECIAL_TITLE, user));
        model.put("blockLogin", user == null);
        model.put("unblockedSuccess", "1".equals(request.parameters().get("unblocked")));

        List<WikiBlock> blocks = blockService.activeBlocks();
        Set<Long> userIds = new LinkedHashSet<>();
        for (WikiBlock block : blocks) {
            if (block.getByUserId() != null) {
                userIds.add(block.getByUserId());
            }
        }
        Map<Long, String> names = userService.usernames(userIds);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiBlock block : blocks) {
            List<String> options = new ArrayList<>();
            if (Boolean.TRUE.equals(block.getPreventEmail())) {
                options.add(message("block.option.email"));
            }
            if (Boolean.TRUE.equals(block.getPreventCreate())) {
                options.add(message("block.option.create"));
            }
            if (Boolean.TRUE.equals(block.getAnonOnly())) {
                options.add(message("block.option.anonOnly"));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("block", block);
            row.put("blocker", names.get(block.getByUserId()));
            row.put("optionsText", options.isEmpty()
                    ? message("block.option.none") : String.join("、", options));
            rows.add(row);
        }
        model.put("blocks", rows);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
