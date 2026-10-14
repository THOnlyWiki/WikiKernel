package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.i18n.PermissionGroupLabels;
import cc.thonly.wikikernel.service.PermissionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 特殊:用户列表} / {@code Special:ListUsers}：注册用户与所属用户组。
 */
@Component
public class ListUsersSpecialPage extends AbstractReportPage {

    /** 最多展示多少个用户。 */
    private static final int MAX_USERS = 100;

    private final WikiUserMapper wikiUserMapper;
    private final PermissionService permissionService;
    private final PermissionGroupLabels groupLabels;

    public ListUsersSpecialPage(MessageSource messageSource,
                                WikiUserMapper wikiUserMapper,
                                PermissionService permissionService,
                                PermissionGroupLabels groupLabels) {
        super(messageSource);
        this.wikiUserMapper = wikiUserMapper;
        this.permissionService = permissionService;
        this.groupLabels = groupLabels;
    }

    @Override
    public String name() {
        return "用户列表";
    }

    @Override
    public List<String> aliases() {
        return List.of("ListUsers", "Users");
    }

    @Override
    public String messageKey() {
        return "listUsers";
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String render(SpecialPageRequest request) {
        List<WikiUser> users = wikiUserMapper.selectList(Wrappers.<WikiUser>lambdaQuery()
                .orderByAsc(WikiUser::getId)
                .last("LIMIT " + MAX_USERS));
        List<List<String>> rows = new ArrayList<>();
        for (WikiUser user : users) {
            var groups = permissionService.namesOf(user.getId());
            rows.add(List.of(
                    link("用户:" + user.getUsername(), user.getUsername()),
                    escape(String.join(message("common.listSeparator"),
                            groupLabels.labels(groups)))));
        }
        return page(table(List.of(message("report.col.user"), message("report.col.groups")), rows));
    }
}
