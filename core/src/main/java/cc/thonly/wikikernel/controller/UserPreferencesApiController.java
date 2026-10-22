package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.WikiLocaleResolver;
import cc.thonly.wikikernel.i18n.CountryNames;
import cc.thonly.wikikernel.mail.EmailTokenService;
import cc.thonly.wikikernel.mail.EmailTypeConfig;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.LoginTokenService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.RateLimiterService;
import cc.thonly.wikikernel.service.UserPreferences;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.skin.SkinRegistry;
import cc.thonly.wikikernel.time.WikiTime;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 参数设置 API（{@code @RestController}，仿 MediaWiki Special:Preferences）：
 * 保存 / 重置用户偏好；{@code GET} 返回账号数据（“访问账号数据”链接）。
 * 邮件相关字段只存储（发信功能尚未实现）。
 */
@RestController
@RequestMapping("/api/user/preferences")
public class UserPreferencesApiController {

    private static final Set<String> LANGUAGES = Set.of("", "zh", "en");
    private static final Set<String> GENDERS = Set.of("", "female", "male");
    private static final Set<Integer> RC_LIMITS = Set.of(0, 50, 100, 250, 500);
    private static final Set<Integer> RC_DAYS = Set.of(0, 1, 3, 7, 30);

    private final UserService userService;
    private final SkinRegistry skinRegistry;
    private final PermissionService permissionService;
    private final EmailTokenService emailTokenService;
    private final LoginTokenService loginTokenService;
    private final MessageSource messageSource;
    private final FeatureService featureService;

    public UserPreferencesApiController(UserService userService,
                                        SkinRegistry skinRegistry,
                                        PermissionService permissionService,
                                         EmailTokenService emailTokenService,
                                         LoginTokenService loginTokenService,
                                         MessageSource messageSource,
                                          FeatureService featureService) {
        this.userService = userService;
        this.skinRegistry = skinRegistry;
        this.permissionService = permissionService;
        this.emailTokenService = emailTokenService;
        this.loginTokenService = loginTokenService;
        this.messageSource = messageSource;
        this.featureService = featureService;
    }

    /** 保存参数设置。 */
    @PostMapping
    public AjaxResult<Void> save(@RequestParam(value = "realName", required = false) String realName,
                                 @RequestParam(value = "gender", required = false) String gender,
                                 @RequestParam(value = "language", required = false) String language,
                                 @RequestParam(value = "timeZone", required = false) String timeZone,
                                 @RequestParam(value = "country", required = false) String country,
                                 @RequestParam(value = "signature", required = false) String signature,
                                 @RequestParam(value = "signatureWikitext", required = false) String signatureWikitext,
                                 @RequestParam(value = "email", required = false) String email,
                                 @RequestParam(value = "emailOnPasswordReset", required = false) String emailOnPasswordReset,
                                 @RequestParam(value = "emailAllowOthers", required = false) String emailAllowOthers,
                                 @RequestParam(value = "emailAllowNewUsers", required = false) String emailAllowNewUsers,
                                 @RequestParam(value = "emailCopy", required = false) String emailCopy,
                                 @RequestParam(value = "rcLimit", required = false, defaultValue = "0") int rcLimit,
                                 @RequestParam(value = "rcDays", required = false, defaultValue = "0") int rcDays,
                                 @RequestParam(value = "searchLimit", required = false, defaultValue = "0") int searchLimit,
                                 @RequestParam(value = "skin", required = false) String skin,
                                 HttpServletRequest request) {
        WikiUser current = UserSession.current(request);
        if (current == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        String languageValue = Strings.trimToEmpty(language);
        String genderValue = Strings.trimToEmpty(gender);
        String emailValue = Strings.trimToEmpty(email);
        String realNameValue = Strings.trimToEmpty(realName);
        String signatureValue = Strings.trimToEmpty(signature);
        if (!LANGUAGES.contains(languageValue)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.invalidLanguage"));
        }
        if (!GENDERS.contains(genderValue)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.invalidGender"));
        }
        String timeZoneValue = WikiTime.normalizeZone(timeZone);
        if (!Strings.trimToEmpty(timeZone).isEmpty() && timeZoneValue.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.invalidTimeZone"));
        }
        // 国家 / 地区：空 = 未指定；否则必须是大写后仍在 ISO 3166-1 列表里的代码（非法值拒绝，不静默丢弃）
        String countryInput = Strings.trimToEmpty(country);
        String countryValue = CountryNames.normalize(countryInput);
        if (countryValue.isEmpty() && !countryInput.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.countryInvalid"));
        }
        if (!emailValue.isEmpty() && (!emailValue.contains("@") || emailValue.length() > 255)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.invalidEmail"));
        }
        if (realNameValue.length() > 255 || signatureValue.length() > 255) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.tooLong"));
        }
        if (!RC_LIMITS.contains(rcLimit) || !RC_DAYS.contains(rcDays)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.invalidRecentChanges"));
        }
        if (searchLimit < 0 || searchLimit > 50) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.invalidSearchLimit"));
        }
        String skinValue = Strings.trimToEmpty(skin);
        if (!skinValue.isEmpty() && skinRegistry.byId(skinValue).isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("appearance.invalidSkin"));
        }

        // Gadget 偏好不在这里更新：Gadgets 已迁到插件 gadget，它有自己的参数设置分区与接口
        // （/api/gadgets/preferences），这里始终保留用户已有值。
        String gadgetValue = current.getGadgets() == null ? "" : current.getGadgets().trim();
        WikiUser updated = userService.updatePreferences(current.getId(), new UserPreferences(
                realNameValue, genderValue, languageValue, timeZoneValue, countryValue,
                signatureValue, isOn(signatureWikitext),
                emailValue, isOn(emailOnPasswordReset), isOn(emailAllowOthers),
                isOn(emailAllowNewUsers), isOn(emailCopy), rcLimit, rcDays, searchLimit, gadgetValue));
        if (updated == null) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("permissions.userNotFound"));
        }
        updated = userService.updateSkin(current.getId(), skinValue).orElse(updated);
        // 换绑邮箱：清除确认状态、撤销「邮箱确认用户」权限与旧的确认令牌
        if (!emailValue.equals(Strings.trimToEmpty(current.getEmail()))) {
            userService.clearEmailConfirmation(current.getId());
            permissionService.revoke(current.getId(), PermissionService.GROUP_EMAIL_CONFIRMED, null);
            emailTokenService.revokeAll(current.getId(), EmailTypeConfig.CONFIRM_EMAIL);
            updated = userService.findById(current.getId()).orElse(updated);
        }
        UserSession.refresh(request, updated.toTransferCopy());
        // 语言偏好立即生效：清掉会话里的临时语言（?uselang 选择）
        WikiLocaleResolver.clearSessionLocale(request);
        return AjaxResult.success(message("preferences.saved"), null);
    }

    /** 还原所有默认设置。 */
    @PostMapping("/reset")
    public AjaxResult<Void> reset(HttpServletRequest request) {
        WikiUser current = UserSession.current(request);
        if (current == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        WikiUser updated = userService.resetPreferences(current.getId());
        if (updated == null) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("permissions.userNotFound"));
        }
        UserSession.refresh(request, updated.toTransferCopy());
        WikiLocaleResolver.clearSessionLocale(request);
        return AjaxResult.success(message("preferences.reset.done"), null);
    }

    /** 防爆破：改密接口里校验旧密码也算一次「密码尝试」，与登录共用限流规则（默认 5 次/5 分钟 → 锁 15 分钟）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private RateLimiterService rateLimiterService;

    /** 修改密码（仿 MediaWiki 参数设置里的「修改密码」）：校验旧密码 → 更新。 */
    @PostMapping("/password")
    public AjaxResult<Void> changePassword(@RequestParam(value = "oldPassword", required = false) String oldPassword,
                                           @RequestParam(value = "newPassword", required = false) String newPassword,
                                           @RequestParam(value = "confirmPassword", required = false) String confirmPassword,
                                           HttpServletRequest request) {
        WikiUser current = UserSession.current(request);
        if (current == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        String limitKey = "password-change|" + current.getUsername().toLowerCase(java.util.Locale.ROOT);
        RateLimiterService.Decision decision =
                rateLimiterService.check(RateLimiterService.LOGIN, limitKey);
        if (decision.limited()) {
            return AjaxResult.error(AjaxCode.TOO_MANY_REQUESTS,
                    message("rateLimit.login", decision.retryAfterSeconds()));
        }
        String oldValue = Strings.trimToEmpty(oldPassword);
        String newValue = Strings.trimToEmpty(newPassword);
        if (newValue.length() < 6 || newValue.length() > 128) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.password.tooShort"));
        }
        if (!newValue.equals(Strings.trimToEmpty(confirmPassword))) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.password.mismatch"));
        }
        if (userService.login(current.getUsername(), oldValue) == null) {
            rateLimiterService.failure(RateLimiterService.LOGIN, limitKey);
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("preferences.password.oldWrong"));
        }
        rateLimiterService.success(RateLimiterService.LOGIN, limitKey);
        if (!userService.updatePassword(current.getId(), newValue)) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("permissions.userNotFound"));
        }
        // 改密后撤销该用户的全部持久登录令牌，并轮换当前会话 ID。
        loginTokenService.revokeAll(current.getId());
        if (request.getSession(false) != null) {
            try {
                request.changeSessionId();
            } catch (IllegalStateException | UnsupportedOperationException ignored) {
                // 容器不支持时保留当前会话；持久令牌已撤销。
            }
        }
        return AjaxResult.success(message("preferences.password.changed"), null);
    }

    /** 访问账号数据：当前用户存储的偏好（JSON）。 */
    @GetMapping
    public AjaxResult<Map<String, Object>> data(HttpServletRequest request) {
        WikiUser current = UserSession.current(request);
        if (current == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        Optional<WikiUser> user = userService.findById(current.getId());
        if (user.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("permissions.userNotFound"));
        }
        WikiUser value = user.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", value.getUsername());
        data.put("email", value.getEmail());
        data.put("emailConfirmed", Boolean.TRUE.equals(value.getEmailConfirmed()));
        data.put("emailConfirmedAt", value.getEmailConfirmedAt());
        data.put("realName", value.getRealName());
        data.put("gender", value.getGender());
        data.put("language", value.getLanguage());
        data.put("timeZone", value.getTimeZone());
        data.put("country", value.getCountry());
        data.put("signature", value.getSignature());
        data.put("signatureWikitext", value.getSignatureWikitext());
        data.put("emailOnPasswordReset", value.getEmailOnPasswordReset());
        data.put("emailAllowOthers", value.getEmailAllowOthers());
        data.put("emailAllowNewUsers", value.getEmailAllowNewUsers());
        data.put("emailCopy", value.getEmailCopy());
        data.put("skin", value.getSkin());
        data.put("rcLimit", value.getRcLimit());
        data.put("rcDays", value.getRcDays());
        data.put("searchLimit", value.getSearchLimit());
        data.put("gadgets", value.getGadgets());
        return AjaxResult.success(data);
    }

    private static boolean isOn(String value) {
        return "on".equalsIgnoreCase(value) || "1".equals(value)
                || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
