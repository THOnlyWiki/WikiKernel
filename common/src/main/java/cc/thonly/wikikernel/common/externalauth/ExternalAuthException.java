package cc.thonly.wikikernel.common.externalauth;

/** 第三方登录流程中的可展示错误（消息不包含 token / secret）。 */
public class ExternalAuthException extends RuntimeException {

    public ExternalAuthException(String message) {
        super(message);
    }

    public ExternalAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
