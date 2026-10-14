package cc.thonly.wikikernel.common.util;

import lombok.Getter;

import java.util.Optional;


@Getter
public enum AjaxCode {
    /** 成功。 */
    SUCCESS(200, "操作成功"),
    /** 提示信息（不是错误）。 */
    INFO(600, "提示信息"),
    /** 警告信息。 */
    WARN(601, "警告信息"),
    /** 通用业务失败。 */
    ERROR(500, "操作失败"),
    /** 请求参数错误。 */
    BAD_REQUEST(400, "请求参数错误"),
    /** 未登录或登录已过期。 */
    UNAUTHORIZED(401, "未登录或登录已过期"),
    /** 没有访问权限。 */
    FORBIDDEN(403, "没有访问权限"),
    /** 资源不存在。 */
    NOT_FOUND(404, "资源不存在"),
    /** 请求方法不支持。 */
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    /** 资源冲突（如标题重复）。 */
    CONFLICT(409, "资源冲突"),
    /** 请求过于频繁。 */
    TOO_MANY_REQUESTS(429, "请求过于频繁"),
    /** 服务暂不可用。 */
    SERVICE_UNAVAILABLE(503, "服务暂不可用");

    private final int code;
    private final String message;

    AjaxCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 是否等于给定返回码。 */
    public boolean matches(int code) {
        return this.code == code;
    }

    /** 按 code 反查枚举。 */
    public static Optional<AjaxCode> fromCode(int code) {
        for (AjaxCode value : values()) {
            if (value.code == code) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
