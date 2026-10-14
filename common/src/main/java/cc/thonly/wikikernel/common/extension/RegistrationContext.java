package cc.thonly.wikikernel.common.extension;

import java.util.Map;

/**
 * 一次「注册账号」的上下文，供 {@link RegistrationGuard}（保存前校验）与
 * {@link RegistrationFormField}（表单字段）使用。
 * <p>
 * 渲染注册表单时还没有用户名 / 邮箱，{@link #username()} / {@link #email()} 返回空串；
 * 提交时（校验）是用户填写的值。
 */
public interface RegistrationContext {

    /** 来源：网页注册表单（{@code 特殊:注册} → {@code POST /api/user/register}）。 */
    String SOURCE_WEB = "web";
    /** 来源：外部 API 调用（Action API 的账号创建等；目前只有网页注册表单这一条路径）。 */
    String SOURCE_API = "api";

    /** 提交的用户名（渲染表单时为空串）。 */
    String username();

    /** 提交的电子邮件（可空；渲染表单时为空串）。 */
    String email();

    /** 表单 / API 参数（只读）。 */
    Map<String, String> parameters();

    /** 会话属性读写（注册问答这类「一次一题」的状态）。 */
    SessionAttributes session();

    /** 客户端 IP（取不到为空串）。 */
    String clientIp();

    /** 来源：{@link #SOURCE_WEB} / {@link #SOURCE_API}。 */
    String source();
}
