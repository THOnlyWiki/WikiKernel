package cc.thonly.wikikernel.plugin.aiagent.model;

/**
 * AI 代理业务异常：携带 i18n 消息键，由 {@code AiAgentApiController} 转成 {@code AjaxResult} 的错误码。
 */
public class AiAgentException extends RuntimeException {

    private final String messageKey;

    public AiAgentException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
