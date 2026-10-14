package cc.thonly.wikikernel.plugin.aiagent.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.aiagent.service.AiConfig;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentSupport;
import cc.thonly.wikikernel.plugin.aiagent.service.AiAgentService;
import cc.thonly.wikikernel.plugin.aiagent.service.AiProviderRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:AI代理} / {@code Special:AiAgent}（别名 {@code Agent} / {@code AIAgent}；原 core 的
 * {@code AiAgentSpecialPage} 迁入，模板随之放到插件 jar 的 {@code templates/} 下）：
 * <ul>
 *     <li>默认：AI 协作编辑界面（对话 → 渲染预览 / 差异 / 可改正文 → 用户核对确认 → 直接保存）；</li>
 *     <li>{@code ?keys=1}：**站点级密钥**（管理员 / 行政员）——每个厂商一行（保存 / 清除）+「允许普通用户使用」
 *         开关，保存走 {@code POST /api/ai-keys}；原先这些表单位于 core 的
 *         {@code 特殊:权限配置 → 站点设置} 与 {@code /init}，迁移后由插件自己的管理员入口承担。</li>
 * </ul>
 * 页面只负责渲染与组装，真正的模型调用与工具循环在 {@link AiAgentService}；提交走
 * {@code POST /api/ai-agent/apply}（复查权限与基准版本后经 {@code common.service.EditSubmitter}
 * 跑平台保存前钩子并以用户本人名义**直接保存**，打上 AI 水印标记）。
 */
public class AiAgentSpecialPage implements SpecialPage {

    /** 查询参数：{@code keys=1} 显示站点密钥面板（管理员）。 */
    public static final String PARAM_KEYS = "keys";

    private final AiAgentService agentService;
    private final AiProviderRegistry providerRegistry;
    private final AiAgentSupport support;
    private final AiConfig aiConfig;
    private final UserStore userStore;

    public AiAgentSpecialPage(AiAgentService agentService, AiProviderRegistry providerRegistry,
                              AiAgentSupport support, AiConfig aiConfig, UserStore userStore) {
        this.agentService = agentService;
        this.providerRegistry = providerRegistry;
        this.support = support;
        this.aiConfig = aiConfig;
        this.userStore = userStore;
    }

    @Override
    public String name() {
        return "AI代理";
    }

    @Override
    public String displayName() {
        return support.message("special.aiAgent");
    }

    @Override
    public List<String> aliases() {
        return List.of("AiAgent", "AIAgent", "Agent");
    }

    @Override
    public String category() {
        return SpecialPageCategories.OTHER;
    }

    @Override
    public String template() {
        return "special/ai-agent";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        Viewer viewer = viewerOf(user);
        boolean canManage = AiAgentSupport.isManager(viewer);
        boolean keysPanel = canManage && truthy(request.parameters().get(PARAM_KEYS));

        model.put("enabled", agentService.enabled());
        model.put("allowed", agentService.allowed(viewer));
        model.put("normalUsersAllowed", agentService.normalUsersAllowed());
        model.put("agentGroup", AiAgentSupport.GROUP_AI_AGENT);
        model.put("loggedIn", viewer.loggedIn());
        model.put("canManage", canManage);
        model.put("keysPanel", keysPanel);
        model.put("chatVisible", !keysPanel && agentService.enabled() && agentService.allowed(viewer));

        List<Map<String, Object>> providers = new ArrayList<>();
        if (!keysPanel) {
            for (AiProviderRegistry.Selection selection : providerRegistry.selections(viewer.id())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", selection.vendorId());
                row.put("label", selection.label());
                row.put("model", selection.model());
                row.put("source", selection.source());
                row.put("sourceLabel", support.message("aiAgent.source." + selection.source(), selection.source()));
                providers.add(row);
            }
        }
        model.put("providers", providers);
        model.put("defaultProvider", providerRegistry.defaultVendorId(viewer.id()));
        model.put("hasProvider", !providers.isEmpty());
        // 演示厂商（目录里 type=mock 的 id 列表）：模板注入到 #ai-agent-root 的 data-demo-providers，
        // 前端据此**锁住提示词输入**（id 不在 JS 里写死）
        model.put("demoProviders", String.join(",", providerRegistry.demoVendorIds()));
        int maxSteps = aiConfig.settings().maxSteps();
        model.put("maxSteps", maxSteps);
        model.put("maxStepsUnlimited", maxSteps <= 0);

        if (keysPanel) {
            model.put("siteVendors", providerRegistry.settingsRows(viewer.id()));
            model.put("allowNormalUsers", aiConfig.settings().allowNormalUsers());
            model.put("keysUrl", AiAgentSupport.pagePath("特殊:" + name()) + "?keys=1");
        }
    }

    /** 特殊页请求里的登录用户 → 钩子 / 权限判断用的视图（含用户组）。 */
    private Viewer viewerOf(WikiUser user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return Viewer.ANONYMOUS;
        }
        Set<String> groups = userStore == null ? Set.of() : new LinkedHashSet<>(userStore.groups(user.getId()));
        return new Viewer(user.getId(), user.getUsername(), false, groups,
                groups.contains(AiAgentSupport.GROUP_BOT));
    }

    private static boolean truthy(String value) {
        String text = Strings.trimToEmpty(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text);
    }
}
