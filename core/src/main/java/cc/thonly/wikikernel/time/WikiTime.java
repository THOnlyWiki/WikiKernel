package cc.thonly.wikikernel.time;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 全站时间显示中心（时区）：
 * <ul>
 *     <li><b>请求时区</b>：拦截器（{@code WikiTimeConfig}）在每个请求开始时按登录用户的
 *     {@code wiki_user.time_zone} 偏好设置 {@link #setZone(ZoneId)}，请求结束清理；
 *     未登录 / 未设置 / 非法时用服务器时区（{@link ZoneId#systemDefault()}）；</li>
 *     <li><b>模板入口</b>：{@code ${@wikiTime.format(时间)}}——按请求时区换算 + 本地化格式
 *     （messages 的 {@code format.dateTime}）；所有日志 / 最近更改 / 历史 / 文件页等都用它；</li>
 *     <li><b>Java 侧</b>：用静态 {@link #convert(LocalDateTime)}（换算到当前请求时区）或
 *     {@link #format(LocalDateTime, String)}（换算 + 指定格式），不要再直接 {@code DateTimeFormatter.format}。</li>
 * </ul>
 * 数据库里的 {@link LocalDateTime} 一律按服务器时区存储，显示时才转换。
 */
@Component("wikiTime")
public class WikiTime {

    /** {@code format.dateTime} 缺失时的兜底格式（与 messages 默认值一致）。 */
    public static final String DEFAULT_DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm";

    /** {@code format.dateTimeWeekday} 缺失时的兜底格式（纯技术默认值，不是中文文案）。 */
    public static final String DEFAULT_DATE_TIME_WEEKDAY_PATTERN = "yyyy-MM-dd (EEEE) HH:mm";

    /** 请求线程当前用户的时区（拦截器设置；无请求时回退服务器时区）。 */
    private static final ThreadLocal<ZoneId> REQUEST_ZONE = new ThreadLocal<>();

    /** 全部可用时区 id（IANA，按字母序；时区下拉框与校验共用）。 */
    private static final List<String> AVAILABLE_ZONE_IDS = buildAvailableZoneIds();

    private static final Set<String> AVAILABLE_ZONE_SET = Set.copyOf(AVAILABLE_ZONE_IDS);

    private final MessageSource messageSource;

    public WikiTime(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 模板入口：按当前请求时区 + 站点语言格式（{@code format.dateTime}）格式化。 */
    public String format(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        String pattern = messageSource.getMessage("format.dateTime", null,
                DEFAULT_DATE_TIME_PATTERN, LocaleContextHolder.getLocale());
        return format(time, pattern);
    }

    /**
     * 模板入口：带星期几的完整时间，页脚「此页面最后编辑于…」用（{@code format.dateTimeWeekday}）。
     * 星期几按界面语言显示（中文「星期三」/ English "Wednesday"）。
     */
    public String formatWeekday(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        String pattern = messageSource.getMessage("format.dateTimeWeekday", null,
                DEFAULT_DATE_TIME_WEEKDAY_PATTERN, LocaleContextHolder.getLocale());
        return java.time.format.DateTimeFormatter.ofPattern(pattern, LocaleContextHolder.getLocale())
                .format(convert(time));
    }

    // ==================== 请求时区上下文（拦截器调用） ====================

    /** 设置当前请求的显示时区（请求开始时由拦截器调用）。 */
    public static void setZone(ZoneId zone) {
        REQUEST_ZONE.set(zone);
    }

    /** 清理当前请求的显示时区（请求结束时由拦截器调用，避免线程复用串数据）。 */
    public static void clearZone() {
        REQUEST_ZONE.remove();
    }

    /** 当前请求的显示时区（不在请求线程里 / 未登录时为服务器时区）。 */
    public static ZoneId zone() {
        ZoneId zone = REQUEST_ZONE.get();
        return zone != null ? zone : ZoneId.systemDefault();
    }

    /** 用户偏好的时区；未设置或非法时回退服务器时区。 */
    public static ZoneId zoneOf(WikiUser user) {
        String id = user == null ? "" : normalizeZone(user.getTimeZone());
        return id.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(id);
    }

    // ==================== 换算与格式化 ====================

    /** 服务器时区时间 → 当前请求时区时间（同一天分组 / 动态更新键也用它，保证前后一致）。 */
    public static LocalDateTime convert(LocalDateTime time) {
        return convert(time, zone());
    }

    /** 服务器时区时间 → 指定时区时间。 */
    public static LocalDateTime convert(LocalDateTime time, ZoneId zone) {
        if (time == null) {
            return null;
        }
        return time.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).toLocalDateTime();
    }

    /** 按当前请求时区换算后用指定格式格式化（Java 侧预格式化文本用；模板请直接用 {@link #format(LocalDateTime)}）。 */
    public static String format(LocalDateTime time, String pattern) {
        if (time == null) {
            return "";
        }
        return java.time.format.DateTimeFormatter.ofPattern(pattern).format(convert(time));
    }

    // ==================== 时区校验与列表 ====================

    /** 校验并规范化时区 id：合法的 IANA id 去空格后返回，非法 / 空返回空串（= 跟随服务器时区）。 */
    public static String normalizeZone(String value) {
        String id = Strings.trimToEmpty(value);
        if (id.isEmpty() || id.length() > 64 || !AVAILABLE_ZONE_SET.contains(id)) {
            return "";
        }
        return id;
    }

    /** 全部可用时区 id（字母序；参数设置的下拉框用）。 */
    public static List<String> availableZoneIds() {
        return AVAILABLE_ZONE_IDS;
    }

    private static List<String> buildAvailableZoneIds() {
        List<String> ids = new ArrayList<>(ZoneId.getAvailableZoneIds());
        ids.sort(Comparator.naturalOrder());
        return List.copyOf(ids);
    }

    /** 时区 id 在当前时刻的 UTC 偏移秒数（参数设置下拉框按偏移排序显示用）。 */
    public static int offsetSeconds(String zoneId) {
        return ZoneId.of(zoneId).getRules().getOffset(Instant.now()).getTotalSeconds();
    }
}
