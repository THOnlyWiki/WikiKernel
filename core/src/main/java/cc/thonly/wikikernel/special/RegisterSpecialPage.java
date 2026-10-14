package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.RegistrationHookService;
import cc.thonly.wikikernel.web.RequestRegistrationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:注册} / {@code Special:CreateAccount}：MediaWiki 风格的注册页。
 * 表单由 {@code /js/auth.js} 提交到 {@code POST /api/user/register}（成功后自动登录）。
 * <p>
 * 注册功能可用站点设置 {@code features.register}（{@code 特殊:权限配置 → 站点设置} 或 {@code /init}）开关：
 * 关闭后只有管理员 / 行政员能进入本页代为创建账号（不会切换当前登录），其他人访问返回 403。
 * <p>
 * 注册页的额外字段（注册问答这类验证）由插件的 {@code RegistrationFormField} 提供，
 * 校验由插件的 {@code RegistrationGuard} 在 {@code POST /api/user/register} 里做
 * （见 {@link RegistrationHookService}）；core 只提供槽位，没有插件时注册页与注册流程与以前完全一致。
 */
@Component
public class RegisterSpecialPage implements SpecialPage {

    /** 规范页名（特殊页面索引的功能开关判断用）。 */
    public static final String PAGE_NAME = "注册";

    private final MessageSource messageSource;
    private final FeatureService featureService;
    private final PermissionService permissionService;
    /** 插件注册页字段（RegistrationFormField）。 */
    private final RegistrationHookService registrationHookService;

    public RegisterSpecialPage(MessageSource messageSource,
                               FeatureService featureService,
                               PermissionService permissionService,
                               RegistrationHookService registrationHookService) {
        this.messageSource = messageSource;
        this.featureService = featureService;
        this.permissionService = permissionService;
        this.registrationHookService = registrationHookService;
    }

    @Override
    public String name() {
        return PAGE_NAME;
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("auth.register", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Register", "CreateAccount", "创建账号", "注册账号");
    }

    @Override
    public String template() {
        return "special/register";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        boolean manager = user != null && permissionService.canManage(user.getId());
        boolean enabled = featureService.registerEnabled();
        if (!enabled && !manager) {
            // 注册已关闭：非管理员不能进入创建账号页面（错误页显示原因）
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message("auth.registerDisabled"));
        }
        model.put("returnto", LoginSpecialPage.safeReturnTo(request.parameters().get("returnto")));
        // 管理员 / 行政员代为创建：提交后不切换登录、留在本页
        model.put("adminCreate", manager);
        model.put("registerDisabled", !enabled);
        // 插件注册页字段（RegistrationFormField）：如注册问答的题目 + 隐藏下标 + 答案输入框
        List<String> fields = this.registrationHookService.formFields(
                RequestRegistrationContext.form(currentRequest(), request.parameters()));
        if (!fields.isEmpty()) {
            model.put("registerFormFields", fields);
        }
    }

    /** 当前请求（特殊页渲染时由 core 设置；没有请求上下文时返回 null，插件拿到空会话）。 */
    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
