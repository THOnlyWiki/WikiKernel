package cc.thonly.wikikernel.plugin.abusefilter.web;

import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilter;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseLog;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code 特殊:滥用日志} / {@code Special:AbuseLog}（仿 MediaWiki；迁移前在 core 的 special 包）：
 * 过滤器命中记录（自己的表 {@code wiki_abuse_log}）。列表对所有人可见；
 * 隐藏条目与变量详情只有管理员 / 行政员可见。
 * <p>
 * 模板在插件 jar 的 {@code templates/special/abuse-log.html}（模型键与迁移前完全一致）。
 */
public class AbuseLogSpecialPage implements SpecialPage {

    /** 列表最多显示条数。 */
    private static final int LOG_LIMIT = 200;

    private final AbuseFilterService service;
    private final AbuseFilterSupport support;
    private final UserStore userStore;

    public AbuseLogSpecialPage(AbuseFilterService service, AbuseFilterSupport support, UserStore userStore) {
        this.service = service;
        this.support = support;
        this.userStore = userStore;
    }

    @Override
    public String name() {
        return "滥用日志";
    }

    @Override
    public String displayName() {
        return support.message("special.abuseLog");
    }

    @Override
    public List<String> aliases() {
        return List.of("AbuseLog", "滥用过滤器日志");
    }

    @Override
    public String template() {
        return "special/abuse-log";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        boolean manager = AbuseFilterSupport.isManager(user, userStore);
        model.put("canManage", manager);

        String idValue = Strings.trimToEmpty(request.parameters().get("id"));
        if (!idValue.isEmpty()) {
            Optional<WikiAbuseLog> found = service.log(parseId(idValue));
            if (found.isEmpty() || (Boolean.TRUE.equals(found.get().getHided()) && !manager)) {
                model.put("mode", "missing");
                return;
            }
            WikiAbuseLog entry = found.get();
            model.put("mode", "detail");
            model.put("log", entry);
            model.put("filterDescription", filterDescription(entry.getFilterId(), manager));
            String userName = userName(entry.getUserId());
            model.put("userName", userName);
            model.put("userUrl", userName == null ? null : AbuseFilterSupport.pagePath("用户:" + userName));
            model.put("timeText", support.formatTime(entry.getCreateAt()));
            model.put("variables", sortedVariables(entry));
            return;
        }

        Map<Long, String> filterNames = new LinkedHashMap<>();
        for (WikiAbuseFilter filter : service.filters(manager)) {
            filterNames.put(filter.getId(),
                    AbuseFilterSpecialPage.descriptionOf(filter.getDescription(), filter.getId()));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiAbuseLog entry : service.logs(manager, LOG_LIMIT)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.getId());
            row.put("time", support.formatTime(entry.getCreateAt()));
            row.put("filterId", entry.getFilterId());
            row.put("filterDescription", filterNames.getOrDefault(entry.getFilterId(),
                    support.message("abusefilter.hiddenFilter", null, entry.getFilterId())));
            String logUserName = userName(entry.getUserId());
            row.put("userText", entry.getUserId() == null
                    ? entry.getIp() : (logUserName == null ? entry.getIp() : logUserName));
            // 记名用户才有用户页可链（匿名 / 找不到用户名时按 IP 显示，不链接）
            row.put("userUrl", logUserName == null ? null : AbuseFilterSupport.pagePath("用户:" + logUserName));
            row.put("pageTitle", entry.getPageTitle());
            row.put("action", entry.getAction());
            row.put("actionsText", actionsText(entry.getActionsTaken()));
            row.put("hided", Boolean.TRUE.equals(entry.getHided()));
            rows.add(row);
        }
        model.put("mode", "list");
        model.put("logs", rows);
    }

    private List<Map<String, Object>> sortedVariables(WikiAbuseLog entry) {
        Map<String, Object> variables = service.logVariables(entry);
        List<Map<String, Object>> rows = new ArrayList<>();
        variables.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", item.getKey());
                    row.put("value", String.valueOf(item.getValue()));
                    rows.add(row);
                });
        return rows;
    }

    private String userName(Long userId) {
        if (userId == null || userStore == null) {
            return null;
        }
        return userStore.names(List.of(userId)).get(userId);
    }

    private String filterDescription(long filterId, boolean manager) {
        return service.find(filterId)
                .filter(filter -> manager || !Boolean.TRUE.equals(filter.getHidden()))
                .map(filter -> AbuseFilterSpecialPage.descriptionOf(filter.getDescription(), filter.getId()))
                .orElseGet(() -> support.message("abusefilter.hiddenFilter", null, filterId));
    }

    private String actionsText(String actions) {
        String value = Strings.trimToEmpty(actions);
        if (value.isEmpty()) {
            return support.message("abusefilter.noActions");
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

    private static long parseId(String value) {
        try {
            return Long.parseLong(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
