package cc.thonly.wikikernel.service;

/**
 * 参数设置表单值（设置页提交；皮肤单独走 {@code /api/user/skin}）。
 * {@code timeZone} 为 IANA 时区 id（空 = 服务器时区），校验与换算见 {@code time.WikiTime}；
 * {@code country} 为 ISO 3166-1 alpha-2 大写代码（空 = 未指定），校验与名称见 {@code i18n.CountryNames}。
 * 邮件相关字段目前只存储（发信功能尚未实现）。
 */
public record UserPreferences(String realName,
                              String gender,
                              String language,
                              String timeZone,
                              String country,
                              String signature,
                              boolean signatureWikitext,
                              String email,
                              boolean emailOnPasswordReset,
                              boolean emailAllowOthers,
                              boolean emailAllowNewUsers,
                              boolean emailCopy,
                              int rcLimit,
                              int rcDays,
                              int searchLimit,
                              String gadgets) {
}
