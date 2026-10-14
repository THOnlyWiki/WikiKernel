package cc.thonly.wikikernel.plugin.aiagent.model;

/**
 * 一条对话消息。
 * <p>
 * {@code role} 取 {@code system} / {@code user} / {@code assistant}；**工具结果**以 user 身份回填
 * （这样对 Gemini 这类严格要求角色交替的接口也能直接映射）。
 */
public record AiMessage(String role, String content) {

    public static final String SYSTEM = "system";
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public static AiMessage system(String content) {
        return new AiMessage(SYSTEM, content == null ? "" : content);
    }

    public static AiMessage user(String content) {
        return new AiMessage(USER, content == null ? "" : content);
    }

    public static AiMessage assistant(String content) {
        return new AiMessage(ASSISTANT, content == null ? "" : content);
    }
}
