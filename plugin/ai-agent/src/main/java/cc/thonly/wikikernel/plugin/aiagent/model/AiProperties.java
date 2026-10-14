package cc.thonly.wikikernel.plugin.aiagent.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 代理配置（LocalSettings.yaml 的 {@code wikikernel.ai}）。示例：
 *
 * <pre>
 * wikikernel:
 *   ai:
 *     default-provider: deepseek
 *     allow-normal-users: false
 *     providers:
 *       deepseek: {type: openai, base-url: https://api.deepseek.com/v1, model: deepseek-chat, api-key: sk-…}
 *       groq:     {type: openai, base-url: https://api.groq.com/openai/v1, model: llama-3.3-70b-versatile, api-key: gsk_…}
 *       gemini:   {type: gemini, model: gemini-2.0-flash, api-key: …}
 *       claude:   {type: anthropic, model: claude-3-5-sonnet-latest, api-key: sk-ant-…}
 *       ollama:   {type: openai, base-url: http://127.0.0.1:11434/v1, model: llama3.1}
 *       mock:     {type: mock}   # 不需要密钥：用于演示 / 测试 / 验证流程
 * </pre>
 *
 * 优先级与其它配置一致：命令行参数 &gt; 环境变量 &gt; LocalSettings.yaml。
 * 例：{@code --wikikernel.ai.providers.deepseek.api-key=sk-…}、环境变量 {@code WIKIKERNEL_AI_PROVIDERS_DEEPSEEK_APIKEY}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wikikernel.ai")
public class AiProperties {

    /** 默认厂商 id（空 = 使用 providers 里的第一个）。 */
    private String defaultProvider = "";

    /** 单次对话里工具调用的最大轮数（**0 或负数 = 不限制**，默认不限制：一直做到代理给出结论）。 */
    private int maxSteps = 0;

    /** 一次读取页面正文的字符上限（超出截断并在结果里说明）。 */
    private int maxReadChars = 24000;

    /** 单次模型请求超时（秒）。 */
    private int timeoutSeconds = 180;

    /** 会话保留时长（分钟）。 */
    private int sessionTtlMinutes = 120;

    /** 同时在内存里保留的会话数上限（超出按最久未用淘汰）。 */
    private int maxSessions = 200;

    /** 会话里保留的对话条数上限（超出丢弃最早的非系统消息）。 */
    private int maxMessages = 60;

    /** 是否允许普通用户（未加入「AI代理用户」组）使用；null = 由站点设置决定，缺省 false。 */
    private Boolean allowNormalUsers;

    /**
     * 访问 AI 服务的代理（可选）：如 {@code socks5://127.0.0.1:10808} 或 {@code http://127.0.0.1:7890}；
     * 单个厂商可用 {@link Provider#getProxy()} 覆盖（例如只有 Gemini / Anthropic 需要代理）。
     */
    private String proxy = "";

    /** 厂商表：id → 配置（{@link Provider}）。 */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /** 单个 AI 厂商的配置。 */
    @Data
    public static class Provider {

        /** 协议类型：{@code openai}（OpenAI 兼容）/ {@code anthropic} / {@code gemini} / {@code mock}。 */
        private String type = "openai";

        /** API 基地址（openai 兼容请带 {@code /v1}；anthropic / gemini 留空 = 官方地址）。 */
        private String baseUrl = "";

        /** API 密钥（本地服务如 Ollama 可留空）。 */
        private String apiKey = "";

        /** 模型名（必填，mock 除外）。 */
        private String model = "";

        /** 界面显示名（空 = 用 id）。 */
        private String label = "";

        /** 采样温度（空 = 用各家默认）。 */
        private Double temperature;

        /** 单次回复的最大 token（默认 4096；Anthropic 协议必填）。 */
        private Integer maxTokens = 4096;

        /** 该厂商的代理（空 = 用全局 {@code wikikernel.ai.proxy}；再空 = 直连）。 */
        private String proxy = "";
    }
}
