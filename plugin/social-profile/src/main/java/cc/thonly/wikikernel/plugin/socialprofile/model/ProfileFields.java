package cc.thonly.wikikernel.plugin.socialprofile.model;

import cc.thonly.wikikernel.common.util.Strings;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户资料字段（SocialProfile 的 profile 字段，全部可选）：性别、生日、现居地、家乡、职业、
 * 兴趣、个人网站、关于我；头像仍在 {@code wiki_user.avatar}（已有能力）。
 * <p>
 * <b>生日只存「月-日」</b>（{@code MM-DD}，不带年份）：仿 SocialProfile 的隐私取舍——资料页只需要「生日是哪天」，
 * 不暴露年龄；存储值是规范化的 {@code MM-DD}（如 {@code 03-09}），用 {@link MonthDay} 校验真实性
 * （{@code 02-30} 会被拒绝），闰日 {@code 02-29} 合法。
 * <p>
 * 「关于我」存 **wikitext**（展示时交给 core 的 {@code WikiText} 渲染器，与页面正文同一套清洗规则），
 * 其余字段都是纯文本（展示时 HTML 转义）；个人网站只接受 {@code http://} / {@code https://} 链接。
 * 各字段的长度上限见本类的常量（校验在 {@code ProfileService}）。
 */
public record ProfileFields(String gender,
                            String birthday,
                            String location,
                            String hometown,
                            String occupation,
                            String interests,
                            String website,
                            String about) {

    /** 性别取值（与 {@code wiki_user.gender} 一致）：空串 = 未指定 / 中性。 */
    public static final String GENDER_UNSPECIFIED = "";
    public static final String GENDER_MALE = "male";
    public static final String GENDER_FEMALE = "female";

    /** 短字段（现居地 / 家乡 / 职业）长度上限。 */
    public static final int SHORT_LIMIT = 100;
    /** 兴趣长度上限。 */
    public static final int INTERESTS_LIMIT = 255;
    /** 个人网站长度上限。 */
    public static final int WEBSITE_LIMIT = 255;
    /** 关于我（wikitext）长度上限。 */
    public static final int ABOUT_LIMIT = 5000;
    /** 生日格式（月-日，两位补零）。 */
    public static final String BIRTHDAY_PATTERN = "%02d-%02d";

    public ProfileFields {
        gender = gender == null ? GENDER_UNSPECIFIED : gender.trim().toLowerCase(java.util.Locale.ROOT);
        birthday = birthday == null ? "" : birthday.trim();
        location = location == null ? "" : location.trim();
        hometown = hometown == null ? "" : hometown.trim();
        occupation = occupation == null ? "" : occupation.trim();
        interests = interests == null ? "" : interests.trim();
        website = website == null ? "" : website.trim();
        about = about == null ? "" : about.trim();
    }

    /** 空资料（没有填写任何字段）。 */
    public static ProfileFields empty() {
        return new ProfileFields("", "", "", "", "", "", "", "");
    }

    /**
     * 从用户偏好表读出的键值构造。
     * <p>
     * 注意：性别不在 {@code socialProfile.} 前缀下（它用 {@code gender} 键落在 {@code wiki_user.gender} 列），
     * 所以要从 {@link #from(String, Map)} 传进来。
     */
    public static ProfileFields from(Map<String, String> values) {
        return from(null, values);
    }

    /** 从「性别 + 其余带 {@code socialProfile.} 前缀的偏好」构造。 */
    public static ProfileFields from(String gender, Map<String, String> values) {
        Map<String, String> source = values == null ? Map.of() : values;
        return new ProfileFields(
                gender == null ? source.getOrDefault(SocialProfileKeys.GENDER, "") : gender,
                source.getOrDefault(SocialProfileKeys.BIRTHDAY, ""),
                source.getOrDefault(SocialProfileKeys.LOCATION, ""),
                source.getOrDefault(SocialProfileKeys.HOMETOWN, ""),
                source.getOrDefault(SocialProfileKeys.OCCUPATION, ""),
                source.getOrDefault(SocialProfileKeys.INTERESTS, ""),
                source.getOrDefault(SocialProfileKeys.WEBSITE, ""),
                source.getOrDefault(SocialProfileKeys.ABOUT, ""));
    }

    /** 转成用户偏好表的键值（空值也写，便于清空；性别用 {@code gender} 键落在 {@code wiki_user.gender}）。 */
    public Map<String, String> toSettings() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SocialProfileKeys.GENDER, gender);
        values.put(SocialProfileKeys.BIRTHDAY, birthday);
        values.put(SocialProfileKeys.LOCATION, location);
        values.put(SocialProfileKeys.HOMETOWN, hometown);
        values.put(SocialProfileKeys.OCCUPATION, occupation);
        values.put(SocialProfileKeys.INTERESTS, interests);
        values.put(SocialProfileKeys.WEBSITE, website);
        values.put(SocialProfileKeys.ABOUT, about);
        return values;
    }

    /** 是否一个字都没填（资料卡里只显示头像与用户名）。 */
    public boolean isEmpty() {
        return gender.isEmpty() && birthday.isEmpty() && location.isEmpty() && hometown.isEmpty()
                && occupation.isEmpty() && interests.isEmpty() && website.isEmpty() && about.isEmpty();
    }

    /**
     * 规范化生日输入：接受 {@code MM-DD} / {@code M-D} / {@code MM/DD} / {@code M/D}
     * 与 {@code YYYY-MM-DD}（只取月日）。
     *
     * @return 规范化后的 {@code MM-DD}；空输入返回空串；非法返回 {@code null}（调用方报 {@code socialProfile.error.birthday}）
     */
    public static String normalizeBirthday(String input) {
        String text = Strings.trimToEmpty(input).replace('/', '-').replace('.', '-');
        if (text.isEmpty()) {
            return "";
        }
        String[] parts = text.split("-");
        try {
            if (parts.length == 3) {
                // 完整日期（含年份）：只保留月日
                LocalDate date = LocalDate.parse(text);
                return String.format(BIRTHDAY_PATTERN, date.getMonthValue(), date.getDayOfMonth());
            }
            if (parts.length == 2) {
                int month = Integer.parseInt(parts[0]);
                int day = Integer.parseInt(parts[1]);
                MonthDay.of(month, day);
                return String.format(BIRTHDAY_PATTERN, month, day);
            }
        } catch (RuntimeException exception) {
            // NumberFormatException / DateTimeParseException / DateTimeException：都算非法输入
            return null;
        }
        return null;
    }

    /** 生日是否合法（空串合法）。 */
    public static boolean isValidBirthday(String birthday) {
        return birthday == null || birthday.isEmpty() || normalizeBirthday(birthday) != null;
    }
}
