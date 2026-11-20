package cc.thonly.wikikernel.common.ai;

public class AiBusyException extends AiException {
    public AiBusyException() { super("AI 请求已达到并发上限，请稍后再试。"); }
}
