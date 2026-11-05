package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.web.WikiTitles;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared render output may only include sources readable by every reader of the output page. */
@Component
public final class TransclusionPolicy {
    private final AccessControlService access;

    public TransclusionPolicy(AccessControlService access) {
        this.access = access;
    }

    public boolean canInclude(String title) {
        String source = access.requiredGroups(AccessAction.READ, WikiTitles.normalize(title));
        if (AccessAction.EVERYONE.equals(source)) {
            return true;
        }
        String outputTitle = WikitextRenderer.currentPageTitle();
        if (outputTitle == null || outputTitle.isBlank()) {
            return false;
        }
        Set<String> readers = groups(access.requiredGroups(AccessAction.READ, outputTitle));
        Set<String> sourceReaders = groups(source);
        if (readers.isEmpty() || readers.contains(AccessAction.EVERYONE) || sourceReaders.isEmpty()) {
            return false;
        }
        // Every named group requires a logged-in user, who also has the implicit user group.
        return sourceReaders.contains(PermissionService.GROUP_USER) || sourceReaders.containsAll(readers);
    }

    /** Lua globals and data tables must never cross output reader groups or read-policy changes. */
    public String cacheScope() {
        String outputTitle = WikitextRenderer.currentPageTitle();
        String readers = outputTitle == null || outputTitle.isBlank() ? AccessAction.EVERYONE
                : access.requiredGroups(AccessAction.READ, outputTitle);
        return access.readPolicyFingerprint() + ":" + readers;
    }

    private static Set<String> groups(String value) {
        return value == null ? Set.of() : Arrays.stream(value.split("\\|"))
                .map(String::trim).filter(group -> !group.isEmpty()).collect(Collectors.toSet());
    }
}
