package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.log.LogEntryView;
import cc.thonly.wikikernel.log.LogFormatter;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.LogTypeRegistry;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code 特殊:日志} / {@code Special:Log}：通用操作日志页（筛选栏仿 MediaWiki Special:Log）。
 * <p>
 * 支持组合筛选：日志类型下拉（{@code ?type=create}）、用户（{@code ?name=用户名}，含 TA 操作的和关于 TA 的）、
 * 目标页面片段（{@code ?page=标题}）、时间范围（{@code ?start=yyyy-MM-ddTHH:mm&end=...}，含当天）。
 * 分页：{@code ?limit=10|50|100|500}（默认 50）+ {@code ?offset=N}（跳过条数，上一页 / 下一页链接保留全部筛选条件）。
 * 已隐藏日志只有管理员 / 行政员可见（并显示隐藏标记与开关）。日志类型来自注册中心，可被插件扩展。
 */
@Component
public class LogSpecialPage implements SpecialPage {

    public static final String PARAM_NAME = "name";
    public static final String PARAM_TYPE = "type";
    public static final String PARAM_PAGE = "page";
    public static final String PARAM_START = "start";
    public static final String PARAM_END = "end";
    public static final String PARAM_LIMIT = "limit";
    public static final String PARAM_OFFSET = "offset";

    /** 每页条数选项（与 MediaWiki 的日志分页一致：10 / 50 / 100 / 500）。 */
    private static final List<Integer> PAGE_SIZES = List.of(10, 50, 100, 500);

    /** 默认每页条数。 */
    private static final int DEFAULT_LIMIT = 50;

    private final LogService logService;
    private final LogTypeRegistry logTypeRegistry;
    private final LogFormatter logFormatter;
    private final UserService userService;
    private final PermissionService permissionService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public LogSpecialPage(LogService logService,
                          LogTypeRegistry logTypeRegistry,
                          LogFormatter logFormatter,
                          UserService userService,
                          PermissionService permissionService,
                          AccessControlService accessControlService,
                          MessageSource messageSource) {
        this.logService = logService;
        this.logTypeRegistry = logTypeRegistry;
        this.logFormatter = logFormatter;
        this.userService = userService;
        this.permissionService = permissionService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "日志";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.log", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Log", "RightsLog");
    }

    @Override
    public String template() {
        return "special/log";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> parameters = request.parameters();
        String type = Strings.trimToEmpty(parameters.get(PARAM_TYPE));
        String name = Strings.trimToEmpty(parameters.get(PARAM_NAME));
        String page = Strings.trimToEmpty(parameters.get(PARAM_PAGE));
        String startRaw = Strings.trimToEmpty(parameters.get(PARAM_START));
        String endRaw = Strings.trimToEmpty(parameters.get(PARAM_END));
        WikiUser viewer = request.user();
        boolean canManage = viewer != null && permissionService.canManage(viewer.getId());

        LocalDateTime start = parseDateTime(startRaw, false);
        LocalDateTime end = parseDateTime(endRaw, true);
        boolean timeError = (!startRaw.isEmpty() && start == null) || (!endRaw.isEmpty() && end == null);
        int limit = parseLimit(parameters.get(PARAM_LIMIT));
        int offset = parseOffset(parameters.get(PARAM_OFFSET));

        boolean userNotFound = false;
        Long relatedUserId = null;
        if (!name.isEmpty()) {
            Optional<WikiUser> target = userService.findByUsername(name);
            if (target.isEmpty()) {
                userNotFound = true;
            } else {
                relatedUserId = target.get().getId();
                model.put("targetUser", target.get());
            }
        }

        model.put("userNotFound", userNotFound);
        model.put("timeError", timeError);
        model.put("selectedType", type);
        model.put("selectedName", name);
        model.put("selectedPage", page);
        model.put("startValue", startRaw);
        model.put("endValue", endRaw);
        model.put("canManage", canManage);
        model.put("userOptions", userService.allUsernames());
        model.put("logTypes", logTypeRegistry.all().stream()
                .map(logType -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", logType.id());
                    item.put("name", logTypeRegistry.displayNameOf(logType.id()));
                    return item;
                })
                .toList());

        List<WikiLog> fetched = userNotFound ? List.of() : logService.search(
                type.isEmpty() ? null : type, relatedUserId, page, start, end, canManage,
                limit + 1, offset);
        boolean hasNext = fetched.size() > limit;
        List<LogEntryView> rows = logFormatter.format(hasNext ? fetched.subList(0, limit) : fetched);
        // 目标页无权查看的日志不出现（标题不泄露）
        model.put("rows", rows.stream()
                .filter(row -> visible(row, viewer))
                .toList());

        // 分页（仿 MediaWiki：每页 10 / 50 / 100 / 500 + 最新 / 上一页 / 下一页；链接保留全部筛选条件）
        model.put("limit", limit);
        model.put("offset", offset);
        model.put("hasNext", hasNext);
        model.put("newestUrl", offset > 0
                ? logUrl(type, name, page, startRaw, endRaw, 0, limit) : null);
        model.put("prevUrl", offset > 0
                ? logUrl(type, name, page, startRaw, endRaw, Math.max(0, offset - limit), limit) : null);
        model.put("nextUrl", hasNext
                ? logUrl(type, name, page, startRaw, endRaw, offset + limit, limit) : null);
        model.put("limitOptions", PAGE_SIZES.stream().map(size -> {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("size", size);
            option.put("selected", size == limit);
            option.put("url", logUrl(type, name, page, startRaw, endRaw, 0, size));
            return option;
        }).toList());
    }

    /** 每页条数：只接受 10 / 50 / 100 / 500，其余（含非法输入）回退默认值。 */
    private static int parseLimit(String raw) {
        try {
            int value = Integer.parseInt(Strings.trimToEmpty(raw));
            return PAGE_SIZES.contains(value) ? value : DEFAULT_LIMIT;
        } catch (NumberFormatException exception) {
            return DEFAULT_LIMIT;
        }
    }

    /** 偏移量：负数 / 非法输入按 0 处理。 */
    private static int parseOffset(String raw) {
        try {
            return Math.max(0, Integer.parseInt(Strings.trimToEmpty(raw)));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** 日志页 URL：保留全部筛选条件，附加分页参数（默认值不写进 URL）。 */
    private String logUrl(String type, String name, String page, String startRaw, String endRaw,
                          int offset, int limit) {
        StringBuilder url = new StringBuilder(WikiUrls.pagePath("特殊:" + name()));
        List<String> params = new java.util.ArrayList<>();
        if (!type.isEmpty()) {
            params.add("type=" + encode(type));
        }
        if (!name.isEmpty()) {
            params.add("name=" + encode(name));
        }
        if (!page.isEmpty()) {
            params.add("page=" + encode(page));
        }
        if (!startRaw.isEmpty()) {
            params.add("start=" + encode(startRaw));
        }
        if (!endRaw.isEmpty()) {
            params.add("end=" + encode(endRaw));
        }
        if (limit != DEFAULT_LIMIT) {
            params.add("limit=" + limit);
        }
        if (offset > 0) {
            params.add("offset=" + offset);
        }
        if (!params.isEmpty()) {
            url.append('?').append(String.join("&", params));
        }
        return url.toString();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** 页面 / 文件类目标的日志需要查看权限；用户 / 文本目标不受页面规则影响。 */
    private boolean visible(LogEntryView row, WikiUser viewer) {
        LogType logType = logTypeRegistry.byId(row.getLog().getLogType()).orElse(null);
        if (logType == null) {
            return true;
        }
        return switch (logType.targetKind()) {
            case PAGE -> accessControlService.canRead(row.getLog().getTarget(), viewer);
            case FILE -> accessControlService.canRead("文件:" + row.getLog().getTarget(), viewer);
            default -> true;
        };
    }

    /**
     * 解析时间筛选参数：支持 {@code yyyy-MM-dd}（结束日期按当天 23:59:59 处理）与
     * {@code datetime-local} 的 {@code yyyy-MM-ddTHH:mm[:ss]}；空白或非法返回 null。
     */
    private LocalDateTime parseDateTime(String raw, boolean endOfDay) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            if (raw.length() == 10) {
                LocalDate date = LocalDate.parse(raw);
                return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
