package cc.thonly.wikikernel.plugin.aiagent;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.aiagent.hook.AiAgentHeadProvider;
import cc.thonly.wikikernel.plugin.aiagent.hook.AiKeysPreferenceSection;
import cc.thonly.wikikernel.plugin.aiagent.service.AiAgentService;
import cc.thonly.wikikernel.common.ai.AiConfig;
import cc.thonly.wikikernel.common.ai.AiProviderRegistry;
import cc.thonly.wikikernel.plugin.aiagent.service.MarkdownRenderer;
import cc.thonly.wikikernel.plugin.aiagent.web.AiAgentApiController;
import cc.thonly.wikikernel.plugin.aiagent.web.AiAgentSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/** AI 词条编辑界面与提案工作流。配置、密钥和协议调用由 core 提供。 */
public class AiAgentPlugin implements WikiPlugin {

    /** 插件 id（与 {@code META-INF/wiki-plugin.properties} 一致）。 */
    public static final String ID = "ai-agent";

    /** 功能开关键（与 core 站点设置 / LocalSettings 一致）：{@code features.ai-agent}。 */
    public static final String ENABLED_KEY = AiAgentSupport.ENABLED_KEY;

    @Autowired
    private AiAgentService agentService;

    @Autowired
    private AiProviderRegistry providerRegistry;

    @Autowired
    private AiAgentSupport support;

    @Autowired
    private AiConfig aiConfig;

    @Autowired
    private UserStore userStore;

    @Autowired
    private SiteConfig siteConfig;

@Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(ID, "AiAgent", "1.0.0",
                "Multi-vendor AI chat agent with read-only wiki tools and user-confirmed edit proposals"
                        + " (Special:AiAgent + /api/ai-agent/* + /api/ai-keys/*)");
    }

    /**
     * 插件自己的表：{@code wiki_user_ai_key}（用户个人厂商密钥）。
     * core 在 {@code onStart} 前按与 core 相同的 Auto-DDL 规则建表 / 补列；数据访问见
     * {@link AiConfigService}（走 JDBC 桥）。
     */
    @Override
    public List<Class<?>> schemaEntities() {
        return List.of();
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new AiAgentMessages());
        context.register(SpecialPage.class, new AiAgentSpecialPage(agentService, providerRegistry, support,
                aiConfig, userStore));
        // 参数设置里的「AI 代理」分区（我的厂商密钥；表单 POST /api/ai-keys）
        context.register(PreferenceSection.class, new AiKeysPreferenceSection(providerRegistry, support));
        // 前端资源（原 core 的 head.html 脚本行 + base.css 样式）
        context.register(HtmlHeadProvider.class, new AiAgentHeadProvider());

        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用 AI 代理：特殊:AI代理 可用（/plugins/ai-agent/），确认提交走 EditSubmitter（source=ai）"
                : "AI 代理已关闭（features.ai-agent=false）：特殊:AI代理 只显示未启用提示，/api/ai-agent/* 返回 403");
    }
}
