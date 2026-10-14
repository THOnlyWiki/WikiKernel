package cc.thonly.wikikernel.common.extension;

/**
 * {@link SetupStep#apply} 的结果。
 *
 * @param success 是否成功
 * @param message 展示给用户的提示（可为空）
 */
public record SetupResult(boolean success, String message) {

    public static SetupResult ok(String message) {
        return new SetupResult(true, message == null ? "" : message);
    }

    public static SetupResult error(String message) {
        return new SetupResult(false, message == null ? "" : message);
    }
}
