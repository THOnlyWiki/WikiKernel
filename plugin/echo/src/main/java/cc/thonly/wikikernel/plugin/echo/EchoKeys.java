package cc.thonly.wikikernel.plugin.echo;

import java.util.List;
import java.util.Set;

/**
 * Echo 插件的类型 / 偏好 / 配置键常量（集中定义，避免各处写字符串）。
 */
public final class EchoKeys {

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）：{@code features.echo}。 */
    public static final String ENABLED_KEY = "features.echo";

    /** 通知类型：有人编辑了你的用户讨论页。 */
    public static final String TYPE_EDIT_USER_TALK = "edit-user-talk";

    /** 通知类型：有人在页面里 {@code [[用户:你]]} / {@code @你} 提及了你。 */
    public static final String TYPE_MENTION = "mention";

    /** 通知类型：你的编辑被回退（rollback）。 */
    public static final String TYPE_REVERT = "revert";

    /** 通知类型：新用户欢迎。 */
    public static final String TYPE_WELCOME = "welcome";

    /** 全部通知类型（顺序 = 特殊:通知 页过滤标签与偏好里的展示顺序）。 */
    public static final List<String> TYPES = List.of(
            TYPE_EDIT_USER_TALK, TYPE_MENTION, TYPE_REVERT, TYPE_WELCOME);

    /**
     * Echo 的两个铃铛分组（与 MediaWiki Echo 一致）：
     * <ul>
     *     <li>{@code alert} 提醒：提及 / 用户讨论页 / 被回退（{@code oo-ui-icon-bell} 铃铛）；</li>
     *     <li>{@code notice} 一般通知：欢迎等站点消息（{@code oo-ui-icon-tray} 托盘）。</li>
     * </ul>
     */
    public static final String GROUP_ALERT = "alert";
    public static final String GROUP_NOTICE = "notice";

    /** 提醒（alert）包含的类型。 */
    public static final List<String> ALERT_TYPES = List.of(TYPE_EDIT_USER_TALK, TYPE_MENTION, TYPE_REVERT);

    /** 一般通知（notice）包含的类型。 */
    public static final List<String> NOTICE_TYPES = List.of(TYPE_WELCOME);

    /** 类型 → 分组（未知类型归提醒）。 */
    public static String groupOf(String type) {
        return NOTICE_TYPES.contains(type) ? GROUP_NOTICE : GROUP_ALERT;
    }

    /** 分组 → 类型列表（空 / 未知 = 全部类型）。 */
    public static List<String> typesOf(String group) {
        if (GROUP_NOTICE.equals(group)) {
            return NOTICE_TYPES;
        }
        if (GROUP_ALERT.equals(group)) {
            return ALERT_TYPES;
        }
        return TYPES;
    }

    /** 用户偏好的前缀（{@code echo.pref.*}，存在 {@code common.service.UserSettings} 里）。 */
    public static final String PREF_PREFIX = "echo.pref.";

    /** 用户偏好键：某一类通知是否启用（{@code echo.pref.<type>}，默认 true）。 */
    public static final String PREF_TYPE_PREFIX = "echo.pref.enable.";

    /** 用户偏好键：是否通过邮件接收通知（{@code echo.pref.email}，默认 false = opt-in）。 */
    public static final String PREF_EMAIL = "echo.pref.email";

    /** 用户偏好键前缀：同类邮件的节流水位（{@code echo.pref.email.last.<type>}，存毫秒时间戳）。 */
    public static final String PREF_EMAIL_LAST_PREFIX = "echo.pref.email.last.";

    /** Echo 的邮件类型 id（core 的邮件类型中心 / {@code wikikernel.email.types.<id>}）。 */
    public static final String EMAIL_TYPE = "echo-notification";

    /** 邮件里站点链接的覆盖配置（站点设置键；留空 = 用当前请求的 host）。 */
    public static final String EMAIL_BASE_URL_KEY = "echo.email.baseUrl";

    /** 每个收件人的通知上限（特殊:通知 列表页翻页大小）。 */
    public static final int PAGE_SIZE = 50;

    /** 顶栏铃铛下拉面板的条数上限。 */
    public static final int FLYOUT_LIMIT = 15;

    /** 时间显示键（core 的本地化格式，缺失时回落 yyyy-MM-dd HH:mm）。 */
    public static final String FORMAT_TIME_KEY = "format.dateTime";

    private EchoKeys() {
    }
}