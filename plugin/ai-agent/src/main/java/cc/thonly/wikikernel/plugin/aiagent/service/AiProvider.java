package cc.thonly.wikikernel.plugin.aiagent.service;

import cc.thonly.wikikernel.plugin.aiagent.model.AiException;
import cc.thonly.wikikernel.plugin.aiagent.model.AiMessage;

import java.util.List;

/**
 * AI 厂商适配层：把「完整对话历史」发出去、把「模型的这一轮回复文本」拿回来。
 * <p>
 * 厂商之间只差协议（OpenAI 兼容 / Anthropic / Gemini），Agent 的工具循环、提示词与
 * JSON 动作协议都在 {@link AiAgentService} 里，各 Provider 保持无状态、可互换。
 */
public interface AiProvider {

    /** 配置里的 id（如 {@code deepseek}）。 */
    String id();

    /** 界面显示名（缺省 = id）。 */
    String label();

    /** 模型名（显示 / 追溯用）。 */
    String model();

    /** 协议类型：{@code openai} / {@code anthropic} / {@code gemini} / {@code mock}。 */
    String type();

    /** 是否已配置好（有密钥或本地服务；mock 恒为 true）。 */
    boolean available();

    /**
     * 单轮对话：messages 为完整历史（首条通常是 system），返回模型的这一轮文本回复。
     *
     * @throws AiException 网络 / 鉴权 / 响应解析失败
     */
    String complete(List<AiMessage> messages);
}
