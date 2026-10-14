package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.extension.RegistrationContext;
import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.http.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

/**
 * {@link RegistrationContext} 的请求实现：注册页表单字段渲染与 {@code POST /api/user/register} 校验共用。
 * <p>
 * 渲染表单时用 {@link #form}（用户名 / 邮箱为空），提交时用 {@link #web}（来源 {@code web}）。
 */
public final class RequestRegistrationContext implements RegistrationContext {

    private final HttpServletRequest request;
    private final String username;
    private final String email;
    private final Map<String, String> parameters;
    private final String source;
    private final SessionAttributes session;

    private RequestRegistrationContext(HttpServletRequest request, String username, String email,
                                       Map<String, String> parameters, String source) {
        this.request = request;
        this.username = Strings.trimToEmpty(username);
        this.email = Strings.trimToEmpty(email);
        this.parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        this.source = source;
        this.session = RequestSessionAttributes.of(request);
    }

    /** 渲染注册表单（还没有用户名 / 邮箱）。 */
    public static RequestRegistrationContext form(HttpServletRequest request, Map<String, String> parameters) {
        return new RequestRegistrationContext(request, "", "", parameters, SOURCE_WEB);
    }

    /** 提交注册（网页注册表单）。 */
    public static RequestRegistrationContext web(HttpServletRequest request, String username, String email,
                                                 Map<String, String> parameters) {
        return new RequestRegistrationContext(request, username, email, parameters, SOURCE_WEB);
    }

    /** 提交注册（外部 API 调用）。 */
    public static RequestRegistrationContext api(HttpServletRequest request, String username, String email,
                                                 Map<String, String> parameters) {
        return new RequestRegistrationContext(request, username, email, parameters, SOURCE_API);
    }

    @Override
    public String username() {
        return username;
    }

    @Override
    public String email() {
        return email;
    }

    @Override
    public Map<String, String> parameters() {
        return parameters;
    }

    @Override
    public SessionAttributes session() {
        return session;
    }

    @Override
    public String clientIp() {
        String ip = RequestMetadata.ip();
        if (!Strings.isBlank(ip)) {
            return ip;
        }
        return request == null ? "" : Strings.trimToEmpty(request.getRemoteAddr());
    }

    @Override
    public String source() {
        return source;
    }
}
