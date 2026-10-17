package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:已删除页面} / {@code Special:Undelete}（仿 MediaWiki 的 {@code Special:Undelete}）：
 * <ul>
 *     <li>不带参数：列出所有已删除的页面（删除时间倒序），每行可「查看 / 恢复」；</li>
 *     <li>{@code ?page=标题}：查看该页面被删除的版本（含原始 wikitext），并提供「恢复」按钮。</li>
 * </ul>
 * 恢复 = {@code POST /api/pages/restore}（{@code WikiPageService.restorePage}：deleted → false + restore 日志）。
 * <p>
 * 权限：只有管理员 / 行政员可以查看与恢复（与 MediaWiki 的 {@code undelete} 权限一致）；
 * 其他人显示「需要管理员权限」提示。
 */
@Component
public class DeletedPagesSpecialPage implements SpecialPage {

    /** 列出最多多少个已删除页面。 */
    private static final int LIST_LIMIT = 200;

    /** 单个页面的被删版本最多显示多少条（内容原文按版本显示）。 */
    private static final int REVISION_LIMIT = 50;

    private final WikiPageService wikiPageService;
    private final UserService userService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public DeletedPagesSpecialPage(WikiPageService wikiPageService, UserService userService,
                                   PermissionService permissionService, MessageSource messageSource) {
        this.wikiPageService = wikiPageService;
        this.userService = userService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "已删除页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.deletedPages", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Undelete", "DeletedPages", "已删除页面");
    }

    @Override
    public String category() {
        return SpecialPageCategories.PAGETOOLS;
    }

    @Override
    public String template() {
        return "special/deleted-pages";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        model.put("deletedPagesLogin", user == null);
        boolean canRestore = user != null && permissionService.canManage(user.getId());
        model.put("canRestore", canRestore);
        if (!canRestore) {
            return; // 模板显示「需要管理员权限」提示
        }
        String title = Strings.trimToEmpty(request.parameters().get("page"));
        if (!title.isEmpty()) {
            populateDetail(title, model);
            return;
        }
        populateList(model);
    }

    /** 列表：所有已删除页面（删除时间倒序）。 */
    private void populateList(Map<String, Object> model) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiPage page : wikiPageService.deletedPages(LIST_LIMIT)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", page.getTitle());
            row.put("pageUrl", WikiUrls.pagePath(page.getTitle()));
            row.put("detailUrl", WikiUrls.pagePath("特殊:已删除页面") + "?page=" + encodeQueryValue(page.getTitle()));
            row.put("deletedAt", formatTime(page.getUpdateAt()));
            rows.add(row);
        }
        model.put("deletedPages", rows);
    }

    /** 单个页面：被删版本（含原始 wikitext）。 */
    private void populateDetail(String title, Map<String, Object> model) {
        WikiPage page = wikiPageService.findDeletedByTitle(title).orElse(null);
        if (page == null) {
            model.put("deletedPageNotFound", true);
            model.put("lookupTitle", title);
            return;
        }
        List<WikiRevision> history = wikiPageService.getHistory(page.getId());
        Set<Long> userIds = new LinkedHashSet<>();
        for (WikiRevision revision : history) {
            if (revision.getUserId() != null) {
                userIds.add(revision.getUserId());
            }
        }
        Map<Long, String> names = userService.usernames(userIds);
        List<Map<String, Object>> rows = new ArrayList<>();
        int count = 0;
        for (WikiRevision revision : history) {
            if (count++ >= REVISION_LIMIT) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", revision.getId());
            row.put("timeText", formatTime(revision.getCreateAt()));
            row.put("userName", revision.getUserId() == null
                    ? Strings.trimToEmpty(revision.getUserText())
                    : names.getOrDefault(revision.getUserId(), "#" + revision.getUserId()));
            row.put("comment", revision.getComment());
            row.put("content", revision.getContent());
            rows.add(row);
        }
        model.put("deletedPage", page);
        model.put("deletedPageTitle", page.getTitle());
        model.put("deletedPageUrl", WikiUrls.pagePath(page.getTitle()));
        model.put("deletedAt", formatTime(page.getUpdateAt()));
        model.put("revisions", rows);
        model.put("revisionTotal", history.size());
    }

    private String formatTime(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        String pattern = messageSource.getMessage("format.dateTime", null, "yyyy-MM-dd HH:mm",
                LocaleContextHolder.getLocale());
        try {
            return time.format(DateTimeFormatter.ofPattern(pattern));
        } catch (IllegalArgumentException exception) {
            return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    /** 查询参数值编码（标题里的特殊字符按 %XX 编码）。 */
    private static String encodeQueryValue(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
