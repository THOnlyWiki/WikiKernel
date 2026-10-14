package cc.thonly.wikikernel.log;

import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.http.IpAddresses;
import cc.thonly.wikikernel.i18n.PermissionGroupLabels;
import cc.thonly.wikikernel.service.LogTypeRegistry;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.time.WikiTime;
import cc.thonly.wikikernel.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@link WikiLog} 格式化成展示行（本地化）：
 * 动作句走 {@code log.entry.<类型id>}，未知类型回退“类型名 目标”；
 * 权限变更详情（{@code grant|管理员}）本地化为“已授予「管理员」”。
 */
@Component
public class LogFormatter {

    private final LogTypeRegistry logTypeRegistry;
    private final UserService userService;
    private final MessageSource messageSource;
    private final PermissionGroupLabels groupLabels;

    public LogFormatter(LogTypeRegistry logTypeRegistry, UserService userService, MessageSource messageSource,
                        PermissionGroupLabels groupLabels) {
        this.logTypeRegistry = logTypeRegistry;
        this.userService = userService;
        this.messageSource = messageSource;
        this.groupLabels = groupLabels;
    }

    public List<LogEntryView> format(List<WikiLog> logs) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (WikiLog log : logs) {
            if (log.getActorUserId() != null) {
                userIds.add(log.getActorUserId());
            }
            if (log.getTargetUserId() != null) {
                userIds.add(log.getTargetUserId());
            }
        }
        Map<Long, String> userNames = userService.usernames(userIds);
        return logs.stream().map(log -> toView(log, userNames)).toList();
    }

    private LogEntryView toView(WikiLog log, Map<Long, String> userNames) {
        LogType type = logTypeRegistry.byId(log.getLogType()).orElse(null);
        LogType.TargetKind kind = type == null ? LogType.TargetKind.TEXT : type.targetKind();
        String targetText = switch (kind) {
            case USER -> log.getTargetUserId() == null
                    ? ""
                    : userNames.getOrDefault(log.getTargetUserId(), "#" + log.getTargetUserId());
            case PAGE, TEXT, FILE -> Strings.trimToEmpty(log.getTarget());
        };
        // 重命名日志：账号已改新名（按 targetUserId 解析会显示新名），动作句里的目标用日志保存的旧用户名
        if (LogTypeConfig.RENAMEUSER.equals(log.getLogType()) && !Strings.trimToEmpty(log.getTarget()).isEmpty()) {
            targetText = Strings.trimToEmpty(log.getTarget());
        }

        LogEntryView view = new LogEntryView();
        view.setLog(log);
        view.setActorName(log.getActorUserId() == null ? null : userNames.get(log.getActorUserId()));
        view.setTargetText(targetText);
        view.setActionText(actionText(log.getLogType(), targetText));
        view.setDetailText(detailText(log));
        view.setTargetUrl(targetUrl(kind, targetText));
        splitAction(view, log.getLogType());
        return view;
    }

    /** 目标链接：用户 → 用户页；页面 / 文件 → 页面；IP / CIDR（封禁等）→ 用户贡献；用户名 → 用户页。 */
    private String targetUrl(LogType.TargetKind kind, String targetText) {
        if (targetText.isEmpty()) {
            return null;
        }
        return switch (kind) {
            case USER -> WikiUrls.pagePath("用户:" + targetText);
            case PAGE -> WikiUrls.pagePath(targetText);
            case FILE -> WikiUrls.pagePath(targetText.startsWith("文件:") || targetText.startsWith("File:")
                    ? targetText : "文件:" + targetText);
            case TEXT -> IpAddresses.isIpOrCidr(targetText)
                    ? WikiUrls.pagePath("特殊:用户贡献") + "?name=" + encode(targetText)
                    : WikiUrls.pagePath("用户:" + targetText);
        };
    }

    /**
     * 把动作句按 {@code {0}} 拆成「目标前 / 目标后」两段（模板把目标渲染成链接，仿 MediaWiki 日志）；
     * 插件自定义类型没有消息键时退化为「类型名 + 目标」。
     */
    private void splitAction(LogEntryView view, String logType) {
        String pattern = messageSource.getMessage("log.entry." + logType, null, null,
                LocaleContextHolder.getLocale());
        if (pattern == null) {
            view.setActionBefore(logTypeRegistry.displayNameOf(logType) + " ");
            view.setActionAfter("");
            return;
        }
        int index = pattern.indexOf("{0}");
        if (index < 0) {
            view.setActionBefore(pattern);
            view.setActionAfter("");
            return;
        }
        view.setActionBefore(pattern.substring(0, index));
        view.setActionAfter(pattern.substring(index + 3));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 权限日志的原因后缀（（原因：…））；没有原因返回空串。 */
    private String reasonSuffix(String reason) {
        return reason.isEmpty() ? ""
                : message("log.rights.reason", new Object[]{reason});
    }

    /** 解码权限日志里 URL 编码的原因；旧数据 / 非法编码原样返回。 */
    private static String decodeReason(String value) {
        String text = Strings.trimToEmpty(value);
        if (text.isEmpty()) {
            return "";
        }
        try {
            return java.net.URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return text;
        }
    }

    private String actionText(String logType, String targetText) {
        String fallback = logTypeRegistry.displayNameOf(logType);
        if (!targetText.isEmpty()) {
            fallback = fallback + " " + targetText;
        }
        return message("log.entry." + logType, new Object[]{targetText}, fallback);
    }

    /**
     * 详情：先问注册的日志类型自己（{@link LogType#detailText(String)}，插件如审核用），
     * 再按 core 的内置规则：权限变更是结构化的 grant|/revoke|，移动是原标题，保护是 on/off，
     * 其余原样展示（如编辑摘要）。
     */
    private String detailText(WikiLog log) {
        String detail = Strings.trimToEmpty(log.getDetail());
        if (detail.isEmpty()) {
            return "";
        }
        LogType type = logTypeRegistry.byId(log.getLogType()).orElse(null);
        if (type != null) {
            String pluginText = type.detailText(detail);
            if (pluginText != null && !pluginText.isBlank()) {
                return pluginText;
            }
        }
        if (LogTypeConfig.RIGHTS.equals(log.getLogType())) {
            if (detail.startsWith(PermissionService.RIGHTS_GRANT_PREFIX)) {
                // grant|组 或 grant|组|过期时间(ISO) 或（带原因时）grant|组||原因 / grant|组|过期时间|原因
                String[] parts = detail.substring(PermissionService.RIGHTS_GRANT_PREFIX.length()).split("\\|", -1);
                String group = parts.length > 0 ? parts[0] : "";
                String expiry = parts.length > 1 ? parts[1].trim() : "";
                String reason = parts.length > 2 ? decodeReason(parts[2]) : "";
                String text = expiry.isEmpty()
                        ? message("log.rights.grant", new Object[]{groupLabels.label(group)}, detail)
                        : message("log.rights.grantExpiry",
                                new Object[]{groupLabels.label(group), formatExpiry(expiry)}, detail);
                return text + reasonSuffix(reason);
            }
            if (detail.startsWith(PermissionService.RIGHTS_REVOKE_PREFIX)) {
                // revoke|组 或 revoke|组|原因
                String[] parts = detail.substring(PermissionService.RIGHTS_REVOKE_PREFIX.length()).split("\\|", -1);
                String group = parts.length > 0 ? parts[0] : "";
                String reason = parts.length > 1 ? decodeReason(parts[1]) : "";
                return message("log.rights.revoke", new Object[]{groupLabels.label(group)}, detail) + reasonSuffix(reason);
            }
        }
        if (LogTypeConfig.MOVE.equals(log.getLogType())) {
            return message("log.move.from", new Object[]{detail}, detail);
        }
        if (LogTypeConfig.IMPORT.equals(log.getLogType())) {
            return message("log.import.revisions", new Object[]{detail}, detail);
        }
        if (LogTypeConfig.PROTECT.equals(log.getLogType())) {
            return message("log.protect." + detail, null, detail);
        }
        // 用户合并 / 重命名用户日志的详情文案已随插件 user-merge / rename-user 走：
        // 类型由插件注册，detailText() 在插件里本地化（见上面的 type.detailText() 分支）。
        return detail;
    }

    /** 本地化文案（只用键，不写中文兜底：缺键时消息源会显示键本身）。 */
    private String message(String key, Object[] args) {
        return messageSource.getMessage(key, args, null, LocaleContextHolder.getLocale());
    }

    private String message(String key, Object[] args, String fallback) {
        return messageSource.getMessage(key, args, fallback, LocaleContextHolder.getLocale());
    }

    /** 权限有效期：ISO 字符串 → 按当前请求时区 / 本地化格式显示；解析失败原样返回。 */
    private static String formatExpiry(String value) {
        try {
            return WikiTime.format(LocalDateTime.parse(value), WikiTime.DEFAULT_DATE_TIME_PATTERN);
        } catch (DateTimeParseException exception) {
            return value;
        }
    }
}
