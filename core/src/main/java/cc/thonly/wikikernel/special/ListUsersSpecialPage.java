package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.common.web.WikiUrls;
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

    /** 每页用户数（MediaWiki ListUsers 的默认分页大小附近）。 */
    private static final int PAGE_SIZE = 50;

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
        long total = wikiUserMapper.selectCount(Wrappers.<WikiUser>lambdaQuery());
        int pages = total == 0 ? 1 : (int) ((total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = parsePage(request.parameters().get("page"), pages);
        int offset = (page - 1) * PAGE_SIZE;
        List<WikiUser> users = wikiUserMapper.selectList(Wrappers.<WikiUser>lambdaQuery()
                .orderByAsc(WikiUser::getId)
                .last("LIMIT " + PAGE_SIZE + " OFFSET " + offset));
        List<List<String>> rows = new ArrayList<>();
        for (WikiUser user : users) {
            var groups = permissionService.namesOf(user.getId());
            rows.add(List.of(
                    link("用户:" + user.getUsername(), user.getUsername()),
                    escape(String.join(message("common.listSeparator"),
                            groupLabels.labels(groups)))));
        }
        if (rows.isEmpty()) {
            return page("<p class=\"report-empty\">" + escape(message("report.listUsers.empty"))
                    + "</p>" + pager(page, pages, total));
        }
        return page(pager(page, pages, total)
                + table(List.of(message("report.col.user"), message("report.col.groups")), rows)
                + pager(page, pages, total));
    }

    private int parsePage(String raw, int pages) {
        try {
            return Math.max(1, Math.min(pages, Integer.parseInt(Strings.trimToEmpty(raw))));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    /** 首页 / 上一页 / 下一页 / 末页 + 当前页信息。 */
    private String pager(int page, int pages, long total) {
        int prev = page > 1 ? page - 1 : -1;
        int next = page < pages ? page + 1 : -1;
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"report-pager\">")
                .append("<span>").append(escape(message("report.listUsers.total", total)))
                .append("</span>")
                .append("<span class=\"report-pager-nav\">");
        if (prev > 0) {
            pageLink(html, 1, message("report.listUsers.first"));
            pageLink(html, prev, message("report.listUsers.prev"));
        } else {
            html.append("<span class=\"report-pager-disabled\">")
                    .append(escape(message("report.listUsers.first"))).append("</span>")
                    .append("<span class=\"report-pager-disabled\">")
                    .append(escape(message("report.listUsers.prev"))).append("</span>");
        }
        html.append("<span class=\"report-pager-current\">")
                .append(escape(message("report.listUsers.page", page, pages)))
                .append("</span>");
        if (next > 0) {
            pageLink(html, next, message("report.listUsers.next"));
            pageLink(html, pages, message("report.listUsers.last"));
        } else {
            html.append("<span class=\"report-pager-disabled\">")
                    .append(escape(message("report.listUsers.next"))).append("</span>")
                    .append("<span class=\"report-pager-disabled\">")
                    .append(escape(message("report.listUsers.last"))).append("</span>");
        }
        return html.append("</span></div>").toString();
    }

    private void pageLink(StringBuilder html, int target, String label) {
        html.append("<a href=\"")
                .append(escape(WikiUrls.pagePath("特殊:用户列表") + "?page=" + target))
                .append("\">").append(escape(label)).append("</a>");
    }
}
