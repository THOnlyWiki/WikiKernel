package cc.thonly.wikikernel.plugin.moderation;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationServiceImpl;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审核插件的公共支撑：功能开关（{@code features.moderation}，与 core 站点设置同一个键）、
 * 插件文案（core 的 MessageSource 会先查 core 再查插件消息包）、时间格式化、HTML / 路径 / JSON 转义，
 * 以及**用户组语义**（审核人 / 豁免组，组名与 core 的 {@code PermissionService} 一致）。
 * <p>
 * 用户组名常量在这里定义并注释清楚（core 迁移前的 {@code PermissionService.GROUP_*}）：
 * <ul>
 *     <li><b>审核人</b>：「核查员」「管理员」——可查看 特殊:审核 队列、通过 / 拒绝变更；</li>
 *     <li><b>豁免组</b>：「免审查用户」「页面管理员」「小部件管理员」「小部件编辑者」——提交直接生效，不进队列。</li>
 * </ul>
 */
@Component
public final class ModerationSupport {

    /** 功能开关键（迁移前 core 的 {@code FeatureService.MODERATION_KEY}，语义不变：默认关闭）。 */
    public static final String ENABLED_KEY = "features.moderation";

    /** 审核人组：核查员（core 的 {@code PermissionService.GROUP_CHECKUSER}）。 */
    public static final String GROUP_CHECKUSER = "核查员";
    /** 审核人组：管理员（core 的 {@code PermissionService.GROUP_ADMIN}）。 */
    public static final String GROUP_ADMIN = "管理员";
    /** 豁免组：免审查用户（core 的 {@code PermissionService.GROUP_MODERATION_EXEMPT}，本次新增的内置组）。 */
    public static final String GROUP_MODERATION_EXEMPT = "免审查用户";
    /** 豁免组：页面管理员（core 的 {@code PermissionService.GROUP_PAGE_ADMIN}）。 */
    public static final String GROUP_PAGE_ADMIN = "页面管理员";
    /** 豁免组：小部件管理员（core 的 {@code PermissionService.GROUP_GADGET_ADMIN}）。 */
    public static final String GROUP_GADGET_ADMIN = "小部件管理员";
    /** 豁免组：小部件编辑者（core 的 {@code PermissionService.GROUP_WIDGET_EDITOR}）。 */
    public static final String GROUP_WIDGET_EDITOR = "小部件编辑者";

    /** 审核人的用户组集合（核查员 / 管理员）。 */
    public static final Set<String> REVIEWER_GROUPS = Set.of(GROUP_CHECKUSER, GROUP_ADMIN);
    /** 豁免审核的用户组集合（免审查用户 / 页面管理员 / 小部件管理员 / 小部件编辑者）。 */
    public static final Set<String> EXEMPT_GROUPS = Set.of(GROUP_MODERATION_EXEMPT, GROUP_PAGE_ADMIN,
            GROUP_GADGET_ADMIN, GROUP_WIDGET_EDITOR);

    /** 文件页命名空间前缀（core 的 {@code FileTitles.PREFIX}）。 */
    public static final String FILE_PREFIX = "文件:";

    /** 图片扩展名（待审上传的预览用；与 core 的 {@code FileService.isImage} 同一批常见类型）。 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "tif", "tiff", "avif");

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;
    private final WikiPageStore wikiPageStore;

    public ModerationSupport(SiteConfig siteConfig, MessageSource messageSource, WikiPageStore wikiPageStore) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
        this.wikiPageStore = wikiPageStore;
    }

    /** 审核是否启用（默认关闭；可在 /init、站点设置或 LocalSettings.yaml 里开启）。 */
    public boolean enabled() {
        return siteConfig != null && siteConfig.getBoolean(ENABLED_KEY, false);
    }

    /** 插件文案（键名与迁移前 core 一致）；找不到时显示翻译键本身，绝不回落中文。 */
    public String message(String key, String fallback, Object... args) {
        if (messageSource == null) {
            return fallback == null ? key : fallback;
        }
        String value = messageSource.getMessage(key, args, fallback, LocaleContextHolder.getLocale());
        return value == null ? (fallback == null ? key : fallback) : value;
    }

    /** 插件文案（无参数、无兜底）：漏翻时显示翻译键本身。 */
    public String message(String key) {
        return message(key, null);
    }

    /** 时间显示：走消息键 {@code format.dateTime}（core 的本地化格式，与迁移前 {@code WikiTime} 一致）。 */
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

    // ==================== 权限语义（与迁移前的 ModerationServiceImpl 一致） ====================

    /** 是否审核人（核查员 / 管理员；匿名恒为 false）。 */
    public static boolean isReviewer(Viewer viewer) {
        return viewer != null && viewer.loggedIn() && inAnyGroup(viewer, REVIEWER_GROUPS);
    }

    /** 是否豁免审核（免审查用户 / 页面管理员 / 小部件管理员 / 小部件编辑者；匿名恒为 false）。 */
    public static boolean isExempt(Viewer viewer) {
        return viewer != null && viewer.loggedIn() && inAnyGroup(viewer, EXEMPT_GROUPS);
    }

    /** 是否审核人（用户组集合版本：特殊页 / API 用当前登录用户）。 */
    public static boolean isReviewer(Set<String> groups) {
        return groups != null && groups.stream().anyMatch(REVIEWER_GROUPS::contains);
    }

    /** 这次提交是否需要审核（功能开启 && 已登录 && 非豁免）。 */
    public boolean shouldModerate(Viewer viewer) {
        return enabled() && viewer != null && viewer.loggedIn() && !isExempt(viewer);
    }

    /** 某页面是否存在（待审创建判断「新页面」用）。 */
    public boolean pageExists(String title) {
        return wikiPageStore != null && wikiPageStore.byTitle(title).isPresent();
    }

    private static boolean inAnyGroup(Viewer viewer, Set<String> groups) {
        if (viewer.groups() == null) {
            return false;
        }
        return viewer.groups().stream().anyMatch(groups::contains);
    }

    // ==================== 工具 ====================

    /** 文件名 → 文件页标题（{@code 文件:<名>}，与迁移前 {@code FileTitles.titleOf} 一致）。 */
    public static String fileTitle(String fileName) {
        return FILE_PREFIX + (fileName == null ? "" : fileName.trim());
    }

    /** 是否图片（按扩展名，迁移前用 core 的 {@code FileService.isImage}；待审文件还没落盘，所以只看名字）。 */
    public static boolean isImageName(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 人类可读的文件大小（迁移前 core 的队列页格式：KB / MB）。 */
    public static String sizeText(Long bytes) {
        long size = bytes == null ? 0 : bytes;
        if (size >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", size / 1048576.0);
        }
        return Math.max(1, size / 1024) + " KB";
    }

    /** 文案 → JSON 字符串字面量（注入 {@code window.WIKI_MESSAGES} 用）。 */
    public static String json(String value) {
        return "\"" + (value == null ? "" : value
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e")) + "\"";
    }

    /** HTML 转义（插件拼链接 / 用户名时必须转义）。 */
    public static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    /** 站内页面路径：{@code 用户:甲} → {@code /wiki/%E7%94%A8%E6%88%B7:%E7%94%B2}（保留子页面的 {@code /}）。 */
    public static String pagePath(String title) {
        if (title == null || title.isEmpty()) {
            return "/wiki/";
        }
        return "/wiki/" + Arrays.stream(title.split("/", -1))
                .map(ModerationSupport::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    /** 查询参数值编码（空格 → {@code +}）。 */
    public static String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 路径段编码（空格 → {@code %20}，不能用 {@code +}）。 */
    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
