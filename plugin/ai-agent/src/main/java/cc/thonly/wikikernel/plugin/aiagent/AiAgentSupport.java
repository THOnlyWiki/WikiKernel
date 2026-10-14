package cc.thonly.wikikernel.plugin.aiagent;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.aiagent.service.AiAgentService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 代理插件的公共支撑：功能开关（{@code features.ai-agent}，与 core 站点设置同一个键）、
 * 插件文案、HTML / 路径 / JSON 转义，以及**用户组语义**（与原 core 的 {@code PermissionService} 一致）。
 * <p>
 * 用户组名常量在这里定义并注释清楚（core 迁移前的 {@code PermissionService.GROUP_*}）：
 * 「AI代理用户」组可无条件使用代理；管理员 / 行政员始终可用；其余登录用户是否可用由站点设置
 * {@code ai-agent.allow-normal-users}（经 {@code AiConfig.settings()} 读取）决定。
 */
@Component
public final class AiAgentSupport {

    /** 功能开关键（迁移前 core 的 {@code FeatureService.AI_AGENT_KEY}，语义不变：默认关闭）。 */
    public static final String ENABLED_KEY = "features.ai-agent";

    /** 站点名称设置键（core 的 {@code SiteNameService.KEY}，只读）。 */
    public static final String SITE_NAME_KEY = "site.name";

    /** 站点名称兜底（core 的 {@code WikiKernelInfo.NAME}）。 */
    public static final String DEFAULT_SITE_NAME = "WikiKernel";

    /** 可用组：AI代理用户（core 的 {@code PermissionService.GROUP_AI_AGENT}）。 */
    public static final String GROUP_AI_AGENT = "AI代理用户";
    /** 管理组：管理员（core 的 {@code PermissionService.GROUP_ADMIN}）。 */
    public static final String GROUP_ADMIN = "管理员";
    /** 管理组：行政员（core 的 {@code PermissionService.GROUP_BUREAUCRAT}）。 */
    public static final String GROUP_BUREAUCRAT = "行政员";
    /** 机器人组（core 的 {@code PermissionService.GROUP_BOT}；只用于构造 {@code Viewer} 的 bot 标记）。 */
    public static final String GROUP_BOT = "机器人";

    /** 管理员的用户组集合（可改站点密钥 / 放开普通用户）。 */
    public static final Set<String> MANAGER_GROUPS = Set.of(GROUP_ADMIN, GROUP_BUREAUCRAT);

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public AiAgentSupport(SiteConfig siteConfig, MessageSource messageSource) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    /** AI 代理是否启用（默认关闭；可在 /init、站点设置或 LocalSettings.yaml 里开启）。 */
    public boolean enabled() {
        return siteConfig != null && siteConfig.getBoolean(ENABLED_KEY, false);
    }

    /** 站点名称（系统提示里用）。 */
    public String siteName() {
        String name = siteConfig == null ? "" : siteConfig.get(SITE_NAME_KEY, DEFAULT_SITE_NAME);
        return name == null || name.isBlank() ? DEFAULT_SITE_NAME : name;
    }

    /** 插件文案（键名与迁移前 core 一致）；没有 MessageSource（独立运行 / 单元测试）时返回兜底文案。 */
    public String message(String key, String fallback, Object... args) {
        if (messageSource == null) {
            return fallback == null ? key : fallback;
        }
        String value = messageSource.getMessage(key, args, fallback, LocaleContextHolder.getLocale());
        return value == null ? (fallback == null ? key : fallback) : value;
    }

    /** 插件文案（无兜底：缺失时回显键名）。 */
    public String message(String key) {
        return message(key, key);
    }

    // ==================== 权限语义（与迁移前的 AiAgentService 一致） ====================

    /** 是否「AI代理用户」组成员。 */
    public static boolean inAgentGroup(Viewer viewer) {
        return viewer != null && viewer.loggedIn() && viewer.inGroup(GROUP_AI_AGENT);
    }

    /** 是否管理员 / 行政员。 */
    public static boolean isManager(Viewer viewer) {
        return viewer != null && viewer.loggedIn()
                && viewer.groups() != null && viewer.groups().stream().anyMatch(MANAGER_GROUPS::contains);
    }

    // ==================== 工具 ====================

    /** 时间显示：走消息键 {@code format.dateTime}（core 的本地化格式）。 */
    public String formatTime(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        String pattern = message("format.dateTime", "yyyy-MM-dd HH:mm");
        try {
            return time.format(DateTimeFormatter.ofPattern(pattern));
        } catch (IllegalArgumentException exception) {
            return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    /** 文案 → JSON 字符串字面量（注入 {@code window.WIKI_MESSAGES} 用）。 */
    public static String json(String value) {
        return "\"" + (value == null ? "" : value
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e")) + "\"";
    }

    /** HTML 转义（插件拼 HTML 时必须转义）。 */
    public static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    /** 站内页面路径：{@code 用户:甲} → {@code /wiki/%E7%94%A8%E6%88%B7:%E7%94%B2}（保留子页面的 {@code /}）。 */
    public static String pagePath(String title) {
        if (title == null || title.isEmpty()) {
            return "/wiki/";
        }
        return "/wiki/" + Arrays.stream(title.split("/", -1))
                .map(AiAgentSupport::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    /** 查询参数值编码（空格 → {@code +}）。 */
    public static String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 路径段编码（空格 → {@code %20}，不能用 {@code +}）。 */
    public static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
