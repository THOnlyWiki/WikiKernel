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
import cc.thonly.wikikernel.plugin.aiagent.data.WikiUserAiKey;
import cc.thonly.wikikernel.plugin.aiagent.hook.AiAgentHeadProvider;
import cc.thonly.wikikernel.plugin.aiagent.hook.AiKeysPreferenceSection;
import cc.thonly.wikikernel.plugin.aiagent.model.AiProperties;
import cc.thonly.wikikernel.plugin.aiagent.service.AiAgentService;
import cc.thonly.wikikernel.plugin.aiagent.service.AiConfig;
import cc.thonly.wikikernel.plugin.aiagent.service.AiConfigService;
import cc.thonly.wikikernel.plugin.aiagent.service.AiProviderRegistry;
import cc.thonly.wikikernel.plugin.aiagent.service.MarkdownRenderer;
import cc.thonly.wikikernel.plugin.aiagent.web.AiAgentApiController;
import cc.thonly.wikikernel.plugin.aiagent.web.AiAgentSpecialPage;
import cc.thonly.wikikernel.plugin.aiagent.web.AiKeysApiController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * AI 代理移植（原 core 内置功能拆出）：把 {@code ai/*} 包（厂商适配器、对话与工具循环、提案）、
 * {@code 特殊:AI代理}、{@code /api/ai-agent/*}、{@code /api/ai-keys/*}、参数设置里的「AI 代理（我的密钥）」
 * 分区、站点密钥管理员入口、前端脚本与全部文案迁入插件。
 * <p>
 * 迁移后的分工：
 * <ul>
 *     <li><b>插件</b>：{@link AiProviderRegistry}（厂商目录与按用户解析）、{@link AiAgentService}（会话 / 工具 /
 *     提案 / 确认提交）、{@link AiAgentApiController}、{@link AiKeysApiController}、{@link AiAgentSpecialPage}、
 *     {@link AiKeysPreferenceSection}、前端脚本与文案；</li>
 *     <li><b>core</b>：站点设置开关（{@code features.ai-agent}，默认关闭）、AI 版本的隐藏水印
 *     （{@code wiki_revision.ai_assisted} / {@code ai_model} 的渲染与页面元数据）；</li>
 *     <li><b>插件自己的东西</b>：{@code wikikernel.ai.*} 配置（{@link AiProperties}，插件子容器绑定）、
 *     {@code wiki_user_ai_key} 表与实体（{@link WikiUserAiKey}，{@code schemaEntities()} 声明、core 自动建表）、
 *     {@link AiConfig} 桥与 {@link AiConfigService}
 *     （用户密钥走 {@code common.service.PluginDatabase}，站点密钥 / 运行时设置走 {@code common.service.SiteConfig}
 *     读写）；</li>
 *     <li><b>平台桥</b>：提交 {@code common.service.EditSubmitter}（先跑 {@code EditGuard}——插件版滥用过滤器等，
 *     来源 {@code source=ai}，再保存并打 AI 水印）、页面 / 版本 {@code WikiPageStore} + {@code PageQueryStore}、
 *     渲染 {@code WikiText}、差异 {@code WikiDiff}、搜索 {@code SearchStore}、链接 {@code PageLinkStore}、
 *     分类 {@code CategoryStore}、权限 {@code AccessCheck} + {@code CurrentUser}、用户名 {@code UserStore}、
 *     限流 {@code RateLimit}。</li>
 * </ul>
 * 插件未加载时：{@code 特殊:AI代理} 与 {@code /api/ai-agent/*}、{@code /api/ai-keys/*} 都不存在，
 * 参数设置里没有「AI 代理」标签页，{@code features.ai-agent} 开关仍在（但打开也没有任何功能）。
 * <p>
 * {@link AiAgentService} / {@link AiProviderRegistry} / {@link AiAgentSupport} / {@link MarkdownRenderer}
 * 都是 {@code @Component}，由插件的 Spring 子容器创建（构造器注入 core 的桥）；入口类 {@code @Autowired}
 * 它们后再注册成扩展点——这样 {@code @RestController} / 特殊页构造器依赖的对象都是 Bean。
 */
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

    @Autowired
    private AiProperties properties;

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
        return List.of(WikiUserAiKey.class);
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

        // LocalSettings.yaml 写了 wikikernel.ai.allow-normal-users 时导入站点设置
        //（与迁移前 core 的导入一致：每次启动以 yaml 为准覆盖一次）
        if (properties != null && siteConfig != null && properties.getAllowNormalUsers() != null) {
            siteConfig.set(AiConfig.ALLOW_NORMAL_KEY, properties.getAllowNormalUsers().toString());
        }

        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用 AI 代理：特殊:AI代理 可用（/plugins/ai-agent/），确认提交走 EditSubmitter（source=ai）"
                : "AI 代理已关闭（features.ai-agent=false）：特殊:AI代理 只显示未启用提示，/api/ai-agent/* 返回 403");
    }
}
