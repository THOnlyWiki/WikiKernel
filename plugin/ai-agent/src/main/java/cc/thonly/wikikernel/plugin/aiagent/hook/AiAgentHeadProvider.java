package cc.thonly.wikikernel.plugin.aiagent.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;

import java.util.List;

/**
 * AI 代理的前端资源（站点级注入，原 core 的 {@code fragments/head.html} 脚本行 + {@code base.css} 样式）：
 * <ul>
 *     <li>{@code /plugins/ai-agent/ai-agent.css}（对话 / 提案 / 密钥行样式，原在 core 的 {@code base.css}）；</li>
 *     <li>{@code /plugins/ai-agent/ai-agent.js}（特殊:AI代理 的对话与提案面板）；</li>
 *     <li>{@code /plugins/ai-agent/ai-keys.js}（参数设置里的「AI 代理」分区与特殊页的站点密钥面板，
 *         POST {@code /api/ai-keys}）。</li>
 * </ul>
 * 与迁移前一致：脚本始终站点级注入（密钥面板在功能关闭时也能配置；页面里没有对应元素时脚本不会有动作）。
 */
public final class AiAgentHeadProvider implements HtmlHeadProvider {

    public AiAgentHeadProvider() {
    }

    @Override
    public int order() {
        return 660;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/ai-agent/ai-agent.css\">",
                "<script defer src=\"/plugins/ai-agent/ai-keys.js\"></script>",
                "<script defer src=\"/plugins/ai-agent/ai-agent.js\"></script>");
    }
}
