package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.model.ProfileFields;
import cc.thonly.wikikernel.plugin.socialprofile.model.SocialProfileKeys;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 用户资料字段的读写与校验（仿 MediaWiki SocialProfile 的 {@code Special:EditProfile} / {@code UpdateProfile}）：
 * <ul>
 *     <li>读：{@link #of(long)} 从 {@code UserSettings}（性别落 {@code wiki_user.gender}，其余落在
 *     {@code wiki_user_preference}）取全部字段；</li>
 *     <li>写：{@link #save(long, ProfileFields)} 校验通过后逐字段写回（空串 = 清空该字段）；</li>
 *     <li>社交偏好：{@link #preferences(long)} 读 / {@link #savePreferences} 写
 *     （是否显示生日 / 现居地、是否允许他人留言、是否显示礼物清单 / 勋章），键都带
 *     {@code socialProfile.pref.} 前缀，默认值为「显示 / 允许」。</li>
 * </ul>
 * 校验失败抛 {@link ProfileException}（带消息键，由 API / 页面本地化后展示），
 * 错误键见 {@link ProfileException} 的常量。
 */
@Component
public final class ProfileService {

    /** 允许的性别取值（与 {@code wiki_user.gender} 一致）。 */
    public static final List<String> GENDERS = List.of(
            ProfileFields.GENDER_UNSPECIFIED, ProfileFields.GENDER_MALE, ProfileFields.GENDER_FEMALE);

    private final UserSettings settings;
    private final UserStore users;
    private final SocialProfileSupport support;
    private final PointsService points;

    public ProfileService(UserSettings settings, UserStore users, SocialProfileSupport support,
                          PointsService points) {
        this.settings = settings;
        this.users = users;
        this.support = support;
        this.points = points;
    }

    /**
     * 某个用户的资料字段（账号不存在 / 没填过 = 空资料）。
     * <p>
     * 性别单独读：它用 {@code gender} 键落在 {@code wiki_user.gender} 列（与「特殊:参数设置」一致），
     * 不在 {@code socialProfile.} 前缀里，所以不能只靠 {@code UserSettings.list(prefix)}。
     */
    public ProfileFields of(long userId) {
        if (userId <= 0 || settings == null) {
            return ProfileFields.empty();
        }
        String gender = settings.get(userId, SocialProfileKeys.GENDER, "");
        return ProfileFields.from(gender, settings.list(userId, SocialProfileKeys.PREFIX));
    }

    /** 性别显示名（走消息键 {@code socialProfile.gender.<值>}；未指定返回空串）。 */
    public String genderLabel(String gender) {
        String value = Strings.trimToEmpty(gender).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        if (!GENDERS.contains(value)) {
            return value;
        }
        return support.message("socialProfile.gender." + value);
    }

    /**
     * 保存资料字段（校验 → 写回）。
     *
     * @throws ProfileException 校验失败（消息键见 {@link ProfileException}）
     */
    public ProfileFields save(long userId, ProfileFields fields) {
        ProfileFields normalized = validate(fields);
        if (settings == null || userId <= 0) {
            return normalized;
        }
        for (var entry : normalized.toSettings().entrySet()) {
            settings.set(userId, entry.getKey(), entry.getValue());
        }
        if (points != null) {
            points.award(userId, PointsRules.Action.PROFILE_EDIT);
        }
        return normalized;
    }

    /** 校验并规范化（不改库）。 */
    public ProfileFields validate(ProfileFields fields) {
        ProfileFields input = fields == null ? ProfileFields.empty() : fields;
        String gender = input.gender().toLowerCase(Locale.ROOT);
        if (!GENDERS.contains(gender)) {
            throw new ProfileException(ProfileException.GENDER);
        }
        String birthday = ProfileFields.normalizeBirthday(input.birthday());
        if (birthday == null) {
            throw new ProfileException(ProfileException.BIRTHDAY);
        }
        check(input.location(), ProfileFields.SHORT_LIMIT, ProfileException.LOCATION);
        check(input.hometown(), ProfileFields.SHORT_LIMIT, ProfileException.HOMETOWN);
        check(input.occupation(), ProfileFields.SHORT_LIMIT, ProfileException.OCCUPATION);
        check(input.interests(), ProfileFields.INTERESTS_LIMIT, ProfileException.INTERESTS);
        check(input.website(), ProfileFields.WEBSITE_LIMIT, ProfileException.WEBSITE);
        check(input.about(), ProfileFields.ABOUT_LIMIT, ProfileException.ABOUT);
        String website = input.website();
        if (!website.isEmpty() && !isHttpUrl(website)) {
            throw new ProfileException(ProfileException.WEBSITE);
        }
        return new ProfileFields(gender, birthday, input.location(), input.hometown(),
                input.occupation(), input.interests(), website, input.about());
    }

    /** 社交偏好（资料卡的展示开关 / 是否允许留言）。 */
    public SocialPreferences preferences(long userId) {
        if (userId <= 0 || settings == null) {
            return SocialPreferences.DEFAULT;
        }
        return new SocialPreferences(
                bool(userId, SocialProfileKeys.PREF_SHOW_BIRTHDAY, true),
                bool(userId, SocialProfileKeys.PREF_SHOW_LOCATION, true),
                bool(userId, SocialProfileKeys.PREF_ALLOW_BOARD, true),
                bool(userId, SocialProfileKeys.PREF_SHOW_GIFTS, true),
                bool(userId, SocialProfileKeys.PREF_SHOW_AWARDS, true));
    }

    /** 保存社交偏好。 */
    public SocialPreferences savePreferences(long userId, SocialPreferences preferences) {
        SocialPreferences value = preferences == null ? SocialPreferences.DEFAULT : preferences;
        if (settings == null || userId <= 0) {
            return value;
        }
        settings.set(userId, SocialProfileKeys.PREF_SHOW_BIRTHDAY, value.showBirthday() ? "1" : "0");
        settings.set(userId, SocialProfileKeys.PREF_SHOW_LOCATION, value.showLocation() ? "1" : "0");
        settings.set(userId, SocialProfileKeys.PREF_ALLOW_BOARD, value.allowBoard() ? "1" : "0");
        settings.set(userId, SocialProfileKeys.PREF_SHOW_GIFTS, value.showGifts() ? "1" : "0");
        settings.set(userId, SocialProfileKeys.PREF_SHOW_AWARDS, value.showAwards() ? "1" : "0");
        return value;
    }

    /** 某人是否允许他人给自己留言（匿名 / 没有账号时按「允许」处理）。 */
    public boolean allowsBoard(long userId) {
        return preferences(userId).allowBoard();
    }

    /** 用户是否存在（保存资料 / 留言前的账号校验）。 */
    public boolean exists(long userId) {
        return users != null && users.byId(userId).isPresent();
    }

    private boolean bool(long userId, String key, boolean defaultValue) {
        String value = settings.get(userId, key, defaultValue ? "1" : "0");
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "1".equals(normalized) || "true".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }

    private static void check(String value, int limit, String errorKey) {
        if (value != null && value.length() > limit) {
            throw new ProfileException(errorKey);
        }
    }

    /** 只接受 http / https 链接（防止 {@code javascript:} 一类伪协议进资料卡）。 */
    private static boolean isHttpUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return (lower.startsWith("http://") || lower.startsWith("https://"))
                && !value.contains(" ") && !value.contains("\"") && !value.contains("<")
                && !value.contains(">");
    }

    /**
     * 资料校验失败：{@link #getMessage()} 是**消息键**（调用方本地化后展示，绝不写死文案）。
     */
    public static class ProfileException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** 性别取值非法。 */
        public static final String GENDER = "socialProfile.error.gender";
        /** 生日格式非法（只接受月-日）。 */
        public static final String BIRTHDAY = "socialProfile.error.birthday";
        /** 现居地过长。 */
        public static final String LOCATION = "socialProfile.error.location";
        /** 家乡过长。 */
        public static final String HOMETOWN = "socialProfile.error.hometown";
        /** 职业过长。 */
        public static final String OCCUPATION = "socialProfile.error.occupation";
        /** 兴趣过长。 */
        public static final String INTERESTS = "socialProfile.error.interests";
        /** 个人网站非法（必须是 http / https 链接）。 */
        public static final String WEBSITE = "socialProfile.error.website";
        /** 关于我过长。 */
        public static final String ABOUT = "socialProfile.error.about";

        public ProfileException(String messageKey) {
            super(messageKey);
        }
    }

    /** 社交偏好（资料卡展示开关 + 是否允许他人留言）。 */
    public record SocialPreferences(boolean showBirthday,
                                    boolean showLocation,
                                    boolean allowBoard,
                                    boolean showGifts,
                                    boolean showAwards) {

        /** 默认：全部显示 / 允许留言。 */
        public static final SocialPreferences DEFAULT = new SocialPreferences(true, true, true, true, true);
    }
}
