package cc.thonly.wikikernel.plugin.socialprofile.model;

/**
 * 用户资料（SocialProfile 的 profile 字段）在 {@code common.service.UserSettings} 里的键名。
 * <p>
 * 键都带 {@code socialProfile.} 前缀（与站点设置 / 插件配置里的 {@code socialProfile.*} 同名，
 * 便于对照）。性别直接用 {@code UserSettings} 已有的 {@code gender} 键，
 * 它落在 {@code wiki_user.gender} 列上（取值 {@code ""} / {@code male} / {@code female}），
 * 与「特殊:参数设置」里的性别保持一致；其余字段落在通用偏好表
 * （{@code wiki_user_preference}，键值对），插件不需要自己建表。
 */
public final class SocialProfileKeys {

    /** 偏好键前缀（{@code UserSettings.list(userId, PREFIX)} 一次取全部资料字段）。 */
    public static final String PREFIX = "socialProfile.";

    /** 性别：{@code ""} / {@code male} / {@code female}（落在 {@code wiki_user.gender} 列）。 */
    public static final String GENDER = "gender";

    /** 生日（{@code MM-DD}，只存月日，见 {@link ProfileFields#birthday()}）。 */
    public static final String BIRTHDAY = PREFIX + "birthday";

    /** 现居地。 */
    public static final String LOCATION = PREFIX + "location";

    /** 家乡。 */
    public static final String HOMETOWN = PREFIX + "hometown";

    /** 职业。 */
    public static final String OCCUPATION = PREFIX + "occupation";

    /** 兴趣 / 爱好。 */
    public static final String INTERESTS = PREFIX + "interests";

    /** 个人网站（http / https 链接）。 */
    public static final String WEBSITE = PREFIX + "website";

    /** 关于我（wikitext，展示时用 core 的渲染器渲染）。 */
    public static final String ABOUT = PREFIX + "about";

    /** 是否在资料卡显示生日（社交偏好，默认「显示」）。 */
    public static final String PREF_SHOW_BIRTHDAY = PREFIX + "pref.showBirthday";

    /** 是否在资料卡显示现居地（社交偏好，默认「显示」）。 */
    public static final String PREF_SHOW_LOCATION = PREFIX + "pref.showLocation";

    /** 是否允许他人给自己留言（社交偏好，默认「允许」）。 */
    public static final String PREF_ALLOW_BOARD = PREFIX + "pref.allowBoard";

    /** 是否在资料卡显示礼物清单（社交偏好，默认「显示」）。 */
    public static final String PREF_SHOW_GIFTS = PREFIX + "pref.showGifts";

    /** 是否在资料卡显示勋章（社交偏好，默认「显示」）。 */
    public static final String PREF_SHOW_AWARDS = PREFIX + "pref.showAwards";

    /** 上次查看礼物页的时间（顶栏「礼物（N）」徽标的已读水位；ISO-8601 字符串）。 */
    public static final String GIFT_SEEN_AT = PREFIX + "gifts.seenAt";

    private SocialProfileKeys() {
    }
}
