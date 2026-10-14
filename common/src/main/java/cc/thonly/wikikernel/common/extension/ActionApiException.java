package cc.thonly.wikikernel.common.extension;

/**
 * Action API 模块错误（{@code {"error":{"code":…,"info":…}}}，HTTP 200）。
 */
public class ActionApiException extends RuntimeException {

    private final String code;

    public ActionApiException(String code, String info) {
        super(info);
        this.code = code == null || code.isBlank() ? "internal_api_error" : code;
    }

    /** 错误码（如 {@code badvalue} / {@code nosuchpage}）。 */
    public String code() {
        return code;
    }

    /** 错误说明。 */
    public String info() {
        return getMessage() == null ? "" : getMessage();
    }
}
