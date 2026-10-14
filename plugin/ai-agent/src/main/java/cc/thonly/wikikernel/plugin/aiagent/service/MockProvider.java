package cc.thonly.wikikernel.plugin.aiagent.service;


import cc.thonly.wikikernel.plugin.aiagent.model.AiException;
import cc.thonly.wikikernel.plugin.aiagent.model.AiMessage;
import cc.thonly.wikikernel.plugin.aiagent.model.AiProviderConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Mock 厂商（{@code type: mock}）：不联网、不需要密钥，按固定剧本走一遍完整流程，
 * 用于**演示、测试与部署验证**（也正是「AI 代理由 mock 驱动」的默认演示方式）。
 * <p>
 * 剧本（依据对话历史里的工具结果推进，自身无状态）：
 * <ol>
 *     <li>第一轮：{@code {"action":"read","title":…}} —— 标题取自用户指令里的「页面：X / 编辑 X / 条目：X」，缺省「首页」；</li>
 *     <li>拿到 read 结果后：{@code {"action":"propose",…}} —— 在原文末尾追加一节「AI 代理演示」，内容含用户的指令摘要；</li>
 *     <li>拿到 propose 结果后：{@code {"action":"final","message":…}}。</li>
 * </ol>
 * 另外支持几个触发词（便于在演示页里试工具）：指令里含「搜索：X」→ {@code search}；
 * 含「列出模板」→ {@code list}（命名空间 10）；含「信息：X」→ {@code info}。
 */
public class MockProvider implements AiProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final String label;

    public MockProvider(String id, AiProviderConfig config) {
        this.id = id;
        // 显示名由 AiProviderRegistry 从插件 i18n 解析后传进来；没有时回退成 id（不是中文兜底）
        this.label = config.label().isBlank() ? id : config.label();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String model() {
        return "mock";
    }

    @Override
    public String type() {
        return "mock";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String complete(List<AiMessage> messages) {
        String instruction = lastUserInstruction(messages);
        JsonNode lastTool = lastToolResult(messages);
        if (lastTool == null) {
            return readJson(instruction);
        }
        String tool = lastTool.path("tool").asText("");
        return switch (tool) {
            case "error" -> json(Map.of("action", "final", "message",
                    "工具调用失败：" + lastTool.path("message").asText("") + "（演示用 Mock 厂商到此结束）"));
            case "read" -> readOutcome(lastTool, instruction);
            case "propose" -> afterPropose(lastTool);
            default -> readJson(instruction);
        };
    }

    /** read 的结果：成功 → 追加一节；页面不存在 → 新建；无权读取 → 结束说明。 */
    private static String readOutcome(JsonNode readResult, String instruction) {
        if (readResult.path("ok").asBoolean(true)) {
            return proposeJson(readResult, instruction);
        }
        if ("missing".equals(readResult.path("reason").asText(""))) {
            return createJson(readResult, instruction);
        }
        return json(Map.of("action", "final", "message",
                "无法读取该页面：" + readResult.path("message").asText("") + "（演示用 Mock 厂商到此结束）"));
    }

    private static String readJson(String instruction) {
        if (instruction.contains("搜索：") || instruction.contains("搜索:")) {
            String query = after(instruction, "搜索：", "搜索:");
            return json(Map.of("action", "search", "query", query, "limit", 5));
        }
        if (instruction.contains("列出模板")) {
            return json(Map.of("action", "list", "namespace", 10, "limit", 30));
        }
        if (instruction.contains("信息：") || instruction.contains("信息:")) {
            return json(Map.of("action", "info", "title", titleFrom(after(instruction, "信息：", "信息:"))));
        }
        return json(Map.of("action", "read", "title", titleFrom(instruction)));
    }

    /** 页面不存在 → 提案新建（演示用；正文会引用一个配套导航模板，演示「一次会话多个条目」）。 */
    private static String createJson(JsonNode readResult, String instruction) {
        String title = readResult.path("title").asText("新页面");
        String summary = summarized(instruction);
        String content = "'''" + title + "'''\n\n"
                + "本页由 '''AI 代理'''（Mock 厂商，演示用）根据指令「" + summary + "」创建。\n\n"
                + "== 说明 ==\n"
                + "* 这是 AI 代理的演示内容；换成真实厂商后这里会是模型生成的真实内容。\n"
                + "* 提交前可以在界面上切换「渲染预览 / 差异 / Wikitext」检查（正文可以直接改）。\n"
                + "* 本页底部用了一个配套导航模板：它会在**同一次会话**里一起提案、一起创建，所以不会留下红链。\n\n"
                + "{{" + companionTitle(title) + "}}\n";
        return json(Map.of("action", "propose", "title", title, "content", content,
                "comment", "AI 代理演示：新建页面（Mock）"));
    }

    /**
     * propose 之后：新建页面的情况下，再顺手创建它引用的「模板:…导航」（**一次会话多个条目**）；
     * 模板提案之后（或编辑现有页面时）收尾。
     */
    private static String afterPropose(JsonNode proposeResult) {
        String title = proposeResult.path("title").asText("");
        boolean creating = proposeResult.path("creating").asBoolean(false);
        if (creating && !title.startsWith("模板:")) {
            return json(Map.of("action", "propose", "title", companionTitle(title),
                    "content", templateContent(title), "comment", "AI 代理演示：创建配套导航模板（Mock）"));
        }
        return json(Map.of("action", "final", "message", creating
                ? "**提案已就绪**（共 2 个页面）：请在下方列表里逐个核对，或直接点「全部确认提交」。\n\n"
                + "- 正文与它引用的模板会**一起**创建，提交后不会有红链\n"
                + "- 每个条目都能单独看预览 / 差异，也可以在「Wikitext」里改\n"
                + "- 提交以你的名义直接保存（内容由你负责核对）\n"
                : "**提案已就绪**：请在下方核对后确认。\n\n"
                + "- **预览**：渲染后的效果（也可以切到「Wikitext」直接改）\n"
                + "- **差异**：与当前版本逐行对比\n"
                + "- **确认提交**：以你的名义直接保存（内容由你负责核对）\n"));
    }

    /** 配套导航模板的标题（页面名去掉命名空间前缀 + 「导航」）。 */
    private static String companionTitle(String pageTitle) {
        return "模板:" + baseName(pageTitle) + "导航";
    }

    /** 配套模板的演示内容（一个简单导航框）。 */
    private static String templateContent(String pageTitle) {
        return "{| class=\"wikitable\" style=\"width:100%; text-align:center;\"\n"
                + "|-\n! colspan=\"3\" | " + baseName(pageTitle) + " 相关页面\n"
                + "|-\n| [[" + pageTitle + "]]\n| [[首页]]\n| [[帮助:编辑]]\n|}\n"
                + "<noinclude>\n本模板由 '''AI 代理'''（Mock 厂商，演示用）创建，"
                + "用于演示「一次会话创建多个页面」：正文与它引用的模板一起提案、一起提交。\n</noinclude>\n";
    }

    private static String baseName(String title) {
        return title.contains(":") ? title.substring(title.indexOf(':') + 1) : title;
    }

    private static String summarized(String instruction) {
        String summary = instruction.replaceAll("\\s+", " ").trim();
        return summary.length() > 60 ? summary.substring(0, 60) + "…" : summary;
    }

    private static String proposeJson(JsonNode readResult, String instruction) {
        String title = readResult.path("title").asText("首页");
        String content = readResult.path("content").asText("");
        String summary = summarized(instruction);
        String appended = content
                + (content.endsWith("\n") || content.isEmpty() ? "" : "\n")
                + "\n== AI 代理演示 ==\n"
                + "本节由 '''AI 代理'''（Mock 厂商，演示用）根据指令「" + summary + "」追加。\n";
        return json(Map.of(
                "action", "propose",
                "title", title,
                "content", appended,
                "comment", "AI 代理演示：追加一节（Mock）"));
    }

    /** 当前需要执行的动作：历史里 read 出现过几次 / propose 出现过几次。 */
    private static JsonNode lastToolResult(List<AiMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            AiMessage message = messages.get(index);
            if (!AiMessage.USER.equals(message.role())) {
                continue;
            }
            String content = message.content();
            int start = content.indexOf('{');
            if (start < 0 || !content.contains("\"tool\"")) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(content.substring(start));
                if (node.has("tool")) {
                    return node;
                }
            } catch (RuntimeException ignored) {
                // 不是工具结果 JSON，继续往前找
            }
        }
        return null;
    }

    private static String lastUserInstruction(List<AiMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            AiMessage message = messages.get(index);
            if (AiMessage.USER.equals(message.role()) && !message.content().contains("\"tool\"")) {
                return message.content();
            }
        }
        return "";
    }

    private static String titleFrom(String instruction) {
        String title = after(instruction, "页面：", "页面:");
        if (title.isEmpty()) {
            title = after(instruction, "页面 ", "页面 ");
        }
        if (title.isEmpty()) {
            title = after(instruction, "编辑 ", "编辑 ");
        }
        if (title.isEmpty()) {
            title = after(instruction, "条目：", "条目:");
        }
        return title.isEmpty() ? "首页" : title;
    }

    private static String after(String text, String primary, String secondary) {
        int index = text.indexOf(primary);
        String marker = primary;
        if (index < 0 && !secondary.equals(primary)) {
            index = text.indexOf(secondary);
            marker = secondary;
        }
        if (index < 0) {
            return "";
        }
        String rest = text.substring(index + marker.length()).trim();
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            // 只按常见分隔符截断（标题里可能有「:」→ 命名空间前缀，不能当终止符）
            if (ch == '，' || ch == '。' || ch == ',' || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end).trim();
    }

    private static String json(Map<String, Object> values) {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (RuntimeException exception) {
            throw new AiException("Mock 生成失败：" + exception.getMessage(), exception);
        }
    }
}
