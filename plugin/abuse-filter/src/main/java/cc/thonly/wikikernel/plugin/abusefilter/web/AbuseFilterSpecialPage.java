package cc.thonly.wikikernel.plugin.abusefilter.web;

import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilter;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilterRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code 特殊:滥用过滤器} / {@code Special:AbuseFilter}（仿 MediaWiki；迁移前在 core 的 special 包）：
 * 过滤器列表（{@code ?edit=编号} 编辑、{@code ?history=编号} 历史、{@code ?new=1} 新建、{@code ?test=1} 测试）。
 * 只有管理员 / 行政员可以查看与管理；功能关闭时显示「未启用」提示。
 * <p>
 * 模板在插件 jar 的 {@code templates/special/abuse-filter.html}（模型键与迁移前完全一致）。
 */
public class AbuseFilterSpecialPage implements SpecialPage {

    /** 历史页最多显示的版本数。 */
    private static final int HISTORY_LIMIT = 100;

    private final AbuseFilterService service;
    private final AbuseFilterSupport support;
    private final UserStore userStore;

    public AbuseFilterSpecialPage(AbuseFilterService service, AbuseFilterSupport support, UserStore userStore) {
        this.service = service;
        this.support = support;
        this.userStore = userStore;
    }

    @Override
    public String name() {
        return "滥用过滤器";
    }

    @Override
    public String displayName() {
        return support.message("special.abuseFilter");
    }

    @Override
    public List<String> aliases() {
        return List.of("AbuseFilter", "防滥用过滤器");
    }

    @Override
    public String template() {
        return "special/abuse-filter";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        if (!AbuseFilterSupport.isManager(user, userStore)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    support.message("abusefilter.forbidden"));
        }
        model.put("canManage", true);
        model.put("actionOptions", actionOptions());
        model.put("codeLanguage", null);
        if (!service.enabled()) {
            model.put("mode", "list");
            model.put("disabled", true);
            model.put("abuseFilters", List.of());
            return;
        }
        model.put("disabled", false);
        // 兜底：开启状态下访问管理页时确保系统账号存在（旧版本升级 / 手工改库等场景）
        service.ensureSystemAccount();

        Map<String, String> parameters = request.parameters();
        String editId = Strings.trimToEmpty(parameters.get("edit"));
        String historyId = Strings.trimToEmpty(parameters.get("history"));
        if ("1".equals(Strings.trimToEmpty(parameters.get("new")))) {
            model.put("mode", "edit");
            model.put("abuseFilter", null);
            model.put("selectedActions", List.of());
            model.put("codeLanguage", "abusefilter");
            return;
        }
        if (!editId.isEmpty()) {
            WikiAbuseFilter filter = service.find(parseId(editId)).orElse(null);
            model.put("mode", filter == null ? "list" : "edit");
            model.put("abuseFilter", filter);
            model.put("selectedActions", filter == null ? List.of() : splitCsv(filter.getActions()));
            model.put("codeLanguage", filter == null ? null : "abusefilter");
            if (filter == null) {
                model.put("abuseFilters", listRows());
                model.put("abuseError", support.message("abusefilter.notFound"));
            }
            return;
        }
        if (!historyId.isEmpty()) {
            long filterId = parseId(historyId);
            model.put("mode", "history");
            model.put("abuseFilter", service.find(filterId).orElse(null));
            model.put("abuseHistory", historyRows(filterId));
            return;
        }
        if ("1".equals(Strings.trimToEmpty(parameters.get("test")))) {
            model.put("mode", "test");
            model.put("codeLanguage", "abusefilter");
            return;
        }
        model.put("mode", "list");
        model.put("abuseFilters", listRows());
    }

    private List<Map<String, Object>> listRows() {
        List<WikiAbuseFilter> filters = service.filters(true);
        Map<Long, String> userNames = userNames(filters.stream()
                .map(WikiAbuseFilter::getLastEditorId).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiAbuseFilter filter : filters) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", filter.getId());
            row.put("description", descriptionOf(filter.getDescription(), filter.getId()));
            row.put("enabled", Boolean.TRUE.equals(filter.getEnabled()));
            row.put("deleted", Boolean.TRUE.equals(filter.getDeleted()));
            row.put("hidden", Boolean.TRUE.equals(filter.getHidden()));
            row.put("actionsText", actionsText(filter.getActions()));
            row.put("hits", filter.getHits() == null ? 0 : filter.getHits());
            String lastEditorName = filter.getLastEditorId() == null
                    ? null : userNames.get(filter.getLastEditorId());
            row.put("lastEditorName", lastEditorName);
            row.put("lastEditorUrl", lastEditorName == null
                    ? null : AbuseFilterSupport.pagePath("用户:" + lastEditorName));
            row.put("lastEditText", support.formatTime(filter.getLastEditAt()));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> historyRows(long filterId) {
        List<WikiAbuseFilterRevision> revisions = service.history(filterId);
        if (revisions.size() > HISTORY_LIMIT) {
            revisions = revisions.subList(0, HISTORY_LIMIT);
        }
        Map<Long, String> userNames = userNames(revisions.stream()
                .map(WikiAbuseFilterRevision::getUserId).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiAbuseFilterRevision revision : revisions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("revisionId", revision.getId());
            row.put("time", support.formatTime(revision.getCreateAt()));
            String userName = revision.getUserId() == null
                    ? null : userNames.get(revision.getUserId());
            row.put("userName", userName);
            row.put("userUrl", userName == null ? null : AbuseFilterSupport.pagePath("用户:" + userName));
            row.put("comment", revision.getComment());
            row.put("enabled", Boolean.TRUE.equals(revision.getEnabled()));
            row.put("deleted", Boolean.TRUE.equals(revision.getDeleted()));
            row.put("hidden", Boolean.TRUE.equals(revision.getHidden()));
            row.put("actionsText", actionsText(revision.getActions()));
            row.put("pattern", revision.getPattern());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> actionOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (String action : AbuseFilterService.ACTION_NAMES) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", action);
            option.put("label", support.message("abusefilter.action." + action, action));
            option.put("restricted", List.of("block", "blockautopromote").contains(action));
            options.add(option);
        }
        return options;
    }

    /** 过滤器的显示说明（留空 = 「过滤器 N」）。 */
    public static String descriptionOf(String description, long id) {
        String value = Strings.trimToEmpty(description);
        return value.isEmpty() ? "#" + id : value;
    }

    private String actionsText(String actions) {
        String value = Strings.trimToEmpty(actions);
        if (value.isEmpty()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (String action : value.split(",")) {
            String item = action.trim();
            if (!item.isEmpty()) {
                labels.add(support.message("abusefilter.action." + item, item));
            }
        }
        return String.join(support.message("common.listSeparator"), labels);
    }

    private Map<Long, String> userNames(List<Long> ids) {
        List<Long> filtered = ids.stream().filter(Objects::nonNull).distinct().toList();
        return filtered.isEmpty() || userStore == null ? Map.of() : userStore.names(filtered);
    }

    private static long parseId(String value) {
        try {
            return Long.parseLong(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /** 操作字符串 → 列表（逗号分隔，已保存时已归一化）。 */
    private static List<String> splitCsv(String value) {
        String text = Strings.trimToEmpty(value);
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : text.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }
}
