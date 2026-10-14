package cc.thonly.wikikernel.plugin.aiagent.service;

import cc.thonly.wikikernel.plugin.aiagent.model.AiMessage;
import cc.thonly.wikikernel.plugin.aiagent.model.AiProposal;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 AI 代理会话（仅存内存，按会话 id 保留）：
 * 对话历史、读过的页面版本（并发检测的基准）、待确认提案、界面用的步骤轨迹。
 * <p>
 * 会话本身不做鉴权；所有读写都通过 {@link AiAgentService}（校验会话属于当前用户）。
 */
public class AiAgentSession {

    private final String id;
    private final Long userId;
    private final LocalDateTime createdAt = LocalDateTime.now();
    private final List<AiMessage> messages = new ArrayList<>();
    /** 标题 → 代理读到时的版本 id（null 值不放入，用 containsKey 区分）。 */
    private final Map<String, Long> readRevisions = new LinkedHashMap<>();
    /** 标题 → 读到时的正文（差异与冲突提示用，仅在内存中保留，大小受读取上限约束）。 */
    private final Map<String, String> readContents = new LinkedHashMap<>();
    /** 待确认提案：id → 提案。 */
    private final Map<String, AiProposal> proposals = new LinkedHashMap<>();
    /** 界面展示的步骤轨迹（最新的在后）。 */
    private final List<Map<String, Object>> steps = new ArrayList<>();
    /**
     * 界面用的对话记录（只放 user / assistant / notice；不含发给模型的 system 与工具结果），
     * 刷新页面后由 {@code GET /api/ai-agent/session} 还原。
     */
    private final List<Map<String, String>> transcript = new ArrayList<>();

    private volatile LocalDateTime lastUsedAt = LocalDateTime.now();
    private volatile String providerId = "";
    private volatile String model = "";

    public AiAgentSession(String id, Long userId) {
        this.id = id;
        this.userId = userId;
    }

    public String id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime lastUsedAt() {
        return lastUsedAt;
    }

    public void touch() {
        this.lastUsedAt = LocalDateTime.now();
    }

    public String providerId() {
        return providerId;
    }

    public void provider(String providerId, String model) {
        this.providerId = providerId;
        this.model = model;
    }

    public String model() {
        return model;
    }

    public List<AiMessage> messages() {
        return messages;
    }

    public Map<String, Long> readRevisions() {
        return readRevisions;
    }

    public Map<String, String> readContents() {
        return readContents;
    }

    public Map<String, AiProposal> proposals() {
        return proposals;
    }

    public List<Map<String, Object>> steps() {
        return steps;
    }

    /** 记录一条界面轨迹（{@code kind}: model / tool / proposal / final / notice）。 */
    public void step(String kind, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("detail", detail == null ? "" : detail);
        steps.add(row);
        if (steps.size() > 200) {
            steps.remove(0);
        }
    }

    /** 界面用的对话记录（role: user / assistant / notice）。 */
    public List<Map<String, String>> transcript() {
        return transcript;
    }

    /** 追加一条界面对话记录（刷新后可还原）。 */
    public void say(String role, String text) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("role", role == null ? "notice" : role);
        row.put("text", text == null ? "" : text);
        transcript.add(row);
        if (transcript.size() > 200) {
            transcript.remove(0);
        }
    }
}
