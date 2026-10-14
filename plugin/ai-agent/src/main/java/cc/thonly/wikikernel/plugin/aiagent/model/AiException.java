package cc.thonly.wikikernel.plugin.aiagent.model;

/**
 * AI 调用失败（网络 / 鉴权 / 响应格式 / 模型返回空等）。
 * <p>
 * {@code status} 为可选的 HTTP 状态码（0 = 未知），控制器据此给出更友好的提示。
 */
public class AiException extends RuntimeException {

    private final int status;

    public AiException(String message) {
        this(0, message);
    }

    public AiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public AiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() {
        return status;
    }
}
