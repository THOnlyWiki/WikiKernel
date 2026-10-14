package cc.thonly.wikikernel.web;

/**
 * 标题不符合 MediaWiki 规则（非法字符 / 过长 / 相对路径 / 空标题等）：
 * 服务层抛出，由网页控制器 / Action API / 表单转成对应错误提示。
 */
public class InvalidTitleException extends IllegalArgumentException {

    private final String code;
    private final String messageKey;
    private final String detail;

    public InvalidTitleException(WikiTitles.TitleError error) {
        this(error.code(), error.messageKey(), error.detail());
    }

    public InvalidTitleException(String code, String messageKey, String detail) {
        super(messageKey + (detail == null || detail.isEmpty() ? "" : " [" + detail + "]"));
        this.code = code;
        this.messageKey = messageKey;
        this.detail = detail == null ? "" : detail;
    }

    /** MediaWiki 风格的错误码：empty / characters / relative / magic-tilde / too-long / leading-colon。 */
    public String code() {
        return code;
    }

    /** 本地化消息键（如 {@code error.titleInvalidChars}）。 */
    public String messageKey() {
        return messageKey;
    }

    /** 命中的字符 / 片段（可空）。 */
    public String detail() {
        return detail;
    }
}
