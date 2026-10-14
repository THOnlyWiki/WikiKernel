package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:权限列表} / {@code Special:ListUserRights}：
 * 列出所有用户及其权限，支持按权限筛选（{@code ?group=管理员}）。
 */
@Component
public class UserRightsListSpecialPage implements SpecialPage {

    /** 查询参数名：按权限筛选。 */
    public static final String PARAM_GROUP = "group";

    /** 单次最多列出多少用户。 */
    private static final int USER_LIMIT = 500;

    private final WikiUserMapper wikiUserMapper;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public UserRightsListSpecialPage(WikiUserMapper wikiUserMapper,
                                     PermissionService permissionService,
                                     MessageSource messageSource) {
        this.wikiUserMapper = wikiUserMapper;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "权限列表";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.userRightsList", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("ListUserRights", "UserRightsList", "用户权限列表");
    }

    @Override
    public String template() {
        return "special/user-rights-list";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String group = Strings.trimToEmpty(request.parameters().get(PARAM_GROUP));
        Map<Long, Set<String>> permissionsByUser = permissionService.allUserPermissions();

        List<Map<String, Object>> rows = wikiUserMapper.selectList(Wrappers.<WikiUser>lambdaQuery()
                        .orderByAsc(WikiUser::getId)
                        .last("LIMIT " + USER_LIMIT))
                .stream()
                .map(user -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("user", user);
                    row.put("names", permissionsByUser.getOrDefault(user.getId(), Set.of()));
                    return row;
                })
                .filter(row -> group.isEmpty() || ((Set<?>) row.get("names")).contains(group))
                .toList();

        model.put("rows", rows);
        model.put("permissionTypes", permissionService.allTypes());
        model.put("selectedGroup", group);
    }
}
