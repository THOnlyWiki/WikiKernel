package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.BlockService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 访问控制桥：把 core 的访问规则与封禁判断暴露给插件（{@code common.service.AccessCheck}）。
 */
@Service
public class AccessCheckImpl implements AccessCheck {

    private final AccessControlService accessControlService;
    private final BlockService blockService;

    public AccessCheckImpl(AccessControlService accessControlService, BlockService blockService) {
        this.accessControlService = accessControlService;
        this.blockService = blockService;
    }

    @Override
    public boolean can(AccessAction action, String target, Viewer viewer) {
        return accessControlService.can(action, target, toUser(viewer));
    }

    @Override
    public Optional<String> blockNotice(Viewer viewer) {
        WikiUser user = toUser(viewer);
        if (user == null) {
            return Optional.empty();
        }
        return blockService.currentBlock(user).map(blockService::notice);
    }

    /**
     * 访问规则只看用户 ID 与用户组（被封禁的用户仍然能读，封禁由 {@link #blockNotice} 单独判断），
     * 所以这里用一个只带 ID / 用户名的轻量实体即可，不必再查库。
     */
    private static WikiUser toUser(Viewer viewer) {
        if (viewer == null || viewer.anonymous() || viewer.id() <= 0) {
            return null;
        }
        WikiUser user = new WikiUser();
        user.setId(viewer.id());
        user.setUsername(viewer.name());
        return user;
    }
}
