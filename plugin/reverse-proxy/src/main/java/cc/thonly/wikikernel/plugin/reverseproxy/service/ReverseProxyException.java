package cc.thonly.wikikernel.plugin.reverseproxy.service;

/** 反向代理请求的可预期失败（状态码 + 文案键），由 Web 层转换成用户可读页面。 */
public class ReverseProxyException extends RuntimeException {

    private final int status;
    private final String messageKey;

    public ReverseProxyException(int status, String messageKey) {
        super(messageKey);
        this.status = status;
        this.messageKey = messageKey;
    }

    public int status() {
        return status;
    }

    public String messageKey() {
        return messageKey;
    }
}
