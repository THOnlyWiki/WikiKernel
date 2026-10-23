package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 用户（含参数设置 / 偏好；邮件发送相关的发信能力尚未实现）。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class WikiUser {
    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    @DbColumn(nullable = false)
    private String username;

    /** 密码哈希（MediaWiki 格式，如 {@code :pbkdf2:sha512:30000:64:…}；见 core 的 {@code MediaWikiPassword}）。 */
    @DbColumn(nullable = false)
    private String password;

    @DbColumn(type = DbType.TEXT, defaultValue = "")
    private String email = "";

    /** 电子邮件是否已确认（点击确认邮件里的链接后为 true；此时自动获得「邮箱确认用户」权限）。 */
    @DbColumn(defaultValue = "0")
    private Boolean emailConfirmed = false;

    /** 电子邮件确认时间（未确认为空）。 */
    @DbColumn
    private LocalDateTime emailConfirmedAt;

    /** 最近一次发送邮箱确认邮件的时间（发送冷却用，防止被反复刷邮箱）。 */
    @DbColumn
    private LocalDateTime emailConfirmSentAt;

    /** 皮肤偏好 id（null / 空 = 用站点默认皮肤）。 */
    @DbColumn(length = 64)
    private String skin;

    /** 头像文件名（存 {@code run/files/avatars/}；空 = 默认头像）。 */
    @DbColumn(length = 255)
    private String avatar = "";

    /** 注册时间（旧数据可能为空）。 */
    @DbColumn
    private LocalDateTime createAt;

    /** 真实姓名（选填，展示 / 署名用）。 */
    @DbColumn(length = 255)
    private String realName = "";

    /** 性别称呼偏好：""（中性）/ female / male。 */
    @DbColumn(length = 16, defaultValue = "")
    private String gender = "";

    /** 界面语言偏好（null / 空 = 站点默认；支持 zh / en）。 */
    @DbColumn(length = 16)
    private String language;

    /** 时区偏好（IANA id，如 Asia/Shanghai；null / 空 = 服务器时区）。注册时默认记下浏览器时区。 */
    @DbColumn(length = 64)
    private String timeZone;

    /** 国家 / 地区偏好（ISO 3166-1 alpha-2 大写代码，如 CN / TW；null / 空 = 未指定）。 */
    @DbColumn(length = 3, defaultValue = "")
    private String country = "";

    /** 签名（可含 wikitext；讨论页的 ~~~ / ~~~~ / ~~~~~ 会按此展开）。 */
    @DbColumn(length = 255)
    private String signature = "";

    /** 签名是否按 wikitext 渲染。 */
    @DbColumn
    private Boolean signatureWikitext = false;

    /** 重置密码邮件开关（发信功能尚未实现）。 */
    @DbColumn
    private Boolean emailOnPasswordReset = true;

    /** 允许其他用户用电子邮件联系我（发信功能尚未实现）。 */
    @DbColumn
    private Boolean emailAllowOthers = true;

    /** 允许新手（非自动确认）用户邮件联系（发信功能尚未实现）。 */
    @DbColumn
    private Boolean emailAllowNewUsers = true;

    /** 向他人发信时给我副本（发信功能尚未实现）。 */
    @DbColumn
    private Boolean emailCopy = false;

    /** 最近更改默认条数（0 = 站点默认 50；可选 50/100/250/500）。 */
    @DbColumn
    private Integer rcLimit = 0;

    /** 最近更改默认天数（0 = 站点默认 7；可选 1/3/7/30）。 */
    @DbColumn
    private Integer rcDays = 0;

    /** 搜索结果条数（0 = 站点默认 20）。 */
    @DbColumn
    private Integer searchLimit = 0;

    /** Enabled MediaWiki Gadgets, stored as a comma-separated name list. */
    @DbColumn(type = DbType.TEXT, defaultValue = "")
    private String gadgets = "";

    public boolean hasEmail() {
        return email != null && !email.isEmpty();
    }

    /** 脱敏副本：密码替换为等长星号，用于 API 响应 / Session（不暴露哈希）。 */
    public WikiUser toTransferCopy() {
        WikiUser copy = new WikiUser();
        copy.setId(id);
        copy.setUsername(username);
        copy.setPassword(password == null ? "" : "*".repeat(password.length()));
        copy.setEmail(email);
        copy.setEmailConfirmed(emailConfirmed);
        copy.setEmailConfirmedAt(emailConfirmedAt);
        copy.setSkin(skin);
        copy.setAvatar(avatar);
        copy.setCreateAt(createAt);
        copy.setRealName(realName);
        copy.setGender(gender);
        copy.setLanguage(language);
        copy.setTimeZone(timeZone);
        copy.setCountry(country);
        copy.setSignature(signature);
        copy.setSignatureWikitext(signatureWikitext);
        copy.setEmailOnPasswordReset(emailOnPasswordReset);
        copy.setEmailAllowOthers(emailAllowOthers);
        copy.setEmailAllowNewUsers(emailAllowNewUsers);
        copy.setEmailCopy(emailCopy);
        copy.setRcLimit(rcLimit);
        copy.setRcDays(rcDays);
        copy.setSearchLimit(searchLimit);
        copy.setGadgets(gadgets);
        return copy;
    }
}
