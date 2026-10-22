package cc.thonly.wikikernel.plugin.aiagent.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.aiagent.service.AiConfig;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentSupport;
import cc.thonly.wikikernel.plugin.aiagent.model.AiVendorCatalog;
import cc.thonly.wikikernel.plugin.aiagent.service.AiProviderRegistry;
import cc.thonly.wikikernel.plugin.aiagent.service.AiUrlPolicy;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AI 厂商密钥 API（插件自己的 {@code @RestController}）：网页填密钥，即时生效、不用改 yaml / 不用重启。
 * <ul>
 *     <li>{@code POST /api/ai-keys} —— {@code scope=user}（任意登录用户）存「我的密钥」，只用于自己的 AI 对话；
 *         {@code scope=site}（管理员 / 行政员）存站点密钥，全站共用；传 {@code clear=1} 清空该厂商配置。
 *         响应只回传尾号掩码（密钥绝不回传）；</li>
 *     <li>{@code POST /api/ai-keys/allow-normal} —— 管理员 / 行政员：站点是否放开普通用户使用
 *         （站点设置 {@code ai-agent.allow-normal-users}，即 {@code AiConfig.ALLOW_NORMAL_KEY}）。</li>
 * </ul>
 * 迁移前这是 core 的 {@code AiProviderKeysApiController}（{@code /api/ai-provider-keys} + 站点设置里的
 * 「允许普通用户使用」复选框）；密钥读写与优先级判断走 {@code common.service.AiConfig} 桥。
 */
@RestController
@RequestMapping("/api/ai-keys")
public class AiKeysApiController {

    private final AiProviderRegistry registry;
    private final AiConfig aiConfig;
    private final AiAgentSupport support;
    private final CurrentUser currentUser;

    public AiKeysApiController(AiProviderRegistry registry, AiConfig aiConfig, AiAgentSupport support,
                               CurrentUser currentUser) {
        this.registry = registry;
        this.aiConfig = aiConfig;
        this.support = support;
        this.currentUser = currentUser;
    }

    /** 保存 / 清除某厂商的密钥（{@code scope=user} 或 {@code scope=site}）。 */
    @PostMapping
    public AjaxResult<Map<String, Object>> save(@RequestParam(value = "scope", required = false) String scope,
                                                @RequestParam(value = "provider", required = false) String provider,
                                                @RequestParam(value = "apiKey", required = false) String apiKey,
                                                @RequestParam(value = "baseUrl", required = false) String baseUrl,
                                                @RequestParam(value = "model", required = false) String model,
                                                @RequestParam(value = "proxy", required = false) String proxy,
                                                @RequestParam(value = "clear", required = false) String clear) {
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        boolean siteScope = "site".equalsIgnoreCase(Strings.trimToEmpty(scope));
        if (siteScope && !AiAgentSupport.isManager(viewer)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("aiAgent.keys.siteForbidden"));
        }
        String vendorId = Strings.trimToEmpty(provider);
        Optional<AiVendorCatalog.Vendor> vendor = AiVendorCatalog.byId(vendorId);
        if (vendor.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("aiAgent.keys.unknownVendor", vendorId));
        }
        boolean clearKey = "1".equals(Strings.trimToEmpty(clear)) || "true".equalsIgnoreCase(Strings.trimToEmpty(clear));
        String key = apiKey == null ? null : apiKey.trim();
        if (key != null && key.isEmpty() && !clearKey) {
            key = null; // 留空 = 不修改（要清空请点「清除」）
        }
        String baseValue = baseUrl == null ? null : baseUrl.trim();
        String modelValue = model == null ? null : model.trim();
        String proxyValue = proxy == null ? null : proxy.trim();
        if (!siteScope && baseValue != null && !baseValue.isBlank()
                && !AiUrlPolicy.isPublicHttpUrl(baseValue)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("aiAgent.keys.baseUrlPrivate"));
        }

        if (siteScope) {
            aiConfig.saveSite(vendorId, key, baseValue, modelValue, proxyValue, clearKey);
        } else {
            aiConfig.saveUser(viewer.id(), vendorId, key, baseValue, modelValue, proxyValue, clearKey);
        }

        AiConfig.Entry entry = siteScope ? aiConfig.site(vendorId) : aiConfig.user(viewer.id(), vendorId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", siteScope ? "site" : "user");
        data.put("provider", vendorId);
        data.put("configured", entry.configured());
        data.put("mask", AiConfig.mask(entry.apiKey()));
        data.put("baseUrl", entry.baseUrl());
        data.put("model", entry.model());
        data.put("proxy", entry.proxy());
        data.put("source", aiConfig.sourceOf(viewer.id(), vendorId));
        // 提示语里的厂商名走插件 i18n（缺键回退成厂商 id），不要用目录里的消息键
        String label = registry.vendorLabel(vendorId);
        String text = clearKey ? message("aiAgent.keys.cleared", label)
                : message("aiAgent.keys.saved", label);
        return AjaxResult.success(text, data);
    }

    /**
     * 站点是否放开普通用户（管理员 / 行政员）：写站点设置，与迁移前站点设置里的「允许普通用户使用」
     * 复选框、{@code /init} 的同一项是同一个键，保存即生效。
     */
    @PostMapping("/allow-normal")
    public AjaxResult<Map<String, Object>> allowNormal(@RequestParam(value = "allow", required = false) String allow) {
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        if (!AiAgentSupport.isManager(viewer)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("aiAgent.keys.siteForbidden"));
        }
        boolean value = "1".equals(Strings.trimToEmpty(allow)) || "true".equalsIgnoreCase(Strings.trimToEmpty(allow));
        aiConfig.saveAllowNormalUsers(value);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("allowNormalUsers", value);
        return AjaxResult.success(message(value ? "aiAgent.keys.allowNormalOn" : "aiAgent.keys.allowNormalOff"), data);
    }

    private String message(String key, Object... args) {
        return support.message(key, key, args);
    }
}
