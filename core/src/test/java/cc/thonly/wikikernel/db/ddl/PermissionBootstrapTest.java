package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.config.PermissionProperties;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 首位管理员引导：授予 管理员 + 行政员、保留原有权限、缺用户时静默跳过。 */
class PermissionBootstrapTest {

    private final PermissionProperties properties = new PermissionProperties();
    private final UserService userService = mock(UserService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final PermissionBootstrap bootstrap =
            new PermissionBootstrap(properties, userService, permissionService);

    @Test
    void promotesConfiguredUserKeepingExistingGroups() {
        properties.setBootstrapAdmins(List.of(" Admin "));
        WikiUser user = new WikiUser();
        user.setId(7L);
        when(userService.findByUsername("Admin")).thenReturn(Optional.of(user));
        when(permissionService.namesOf(7L)).thenReturn(Set.of(PermissionService.GROUP_USER));

        bootstrap.run(null);

        verify(permissionService).replaceFor(7L, Set.of(
                PermissionService.GROUP_USER,
                PermissionService.GROUP_ADMIN,
                PermissionService.GROUP_BUREAUCRAT));
    }

    @Test
    void missingUserIsSkipped() {
        properties.setBootstrapAdmins(List.of("Ghost"));
        when(userService.findByUsername("Ghost")).thenReturn(Optional.empty());

        bootstrap.run(null);

        verifyNoInteractions(permissionService);
    }

    @Test
    void alreadyAdminIsNotTouched() {
        properties.setBootstrapAdmins(List.of("Admin"));
        WikiUser user = new WikiUser();
        user.setId(7L);
        when(userService.findByUsername("Admin")).thenReturn(Optional.of(user));
        when(permissionService.namesOf(7L)).thenReturn(
                Set.of(PermissionService.GROUP_ADMIN, PermissionService.GROUP_BUREAUCRAT));

        bootstrap.run(null);

        verify(permissionService, never()).replaceFor(any(), any());
    }
}
