package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiAccessRule;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.plugin.PluginFeatureSections;
import cc.thonly.wikikernel.i18n.PermissionGroupLabels;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.SpecialWikiPageService;
import cc.thonly.wikikernel.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:权限配置} / {@code Special:AccessControl}：可视化配置访问规则。
 * <p>
 * 「目标 + 动作 → 允许的用户组」；目标支持 {@code *}（全部）、精确标题、前缀（以 {@code *} 结尾）
 * 与特殊页（{@code 特殊:XXX}）。页面本身也受访问规则控制（查看权限），修改规则需要管理员 / 行政员。
 */
@Component
public class AccessControlSpecialPage implements SpecialPage {

    /** 目标输入框的候选页面数上限（性能考虑）。 */
    private static final int PAGE_OPTION_LIMIT = 500;

    private final AccessControlService accessControlService;
    private final PermissionService permissionService;
    private final SiteSettingsService siteSettingsService;
    private final FeatureService featureService;
    /** Plugin-contributed settings sections (core only renders key/value). */
    private final PluginFeatureSections featureSections;
    private final WikiPageMapper wikiPageMapper;
    private final ObjectProvider<SpecialWikiPageService> specialWikiPageService;
    private final MessageSource messageSource;
    private final PermissionGroupLabels groupLabels;
    /** 站点名称（站点设置第一项）。 */
    private final SiteNameService siteNameService;
    /** AI 厂商密钥行（站点密钥）。 */
    /** TypeScript 服务端编译（站点设置里的开关与「编译器是否就绪」摘要）。 */
    private final cc.thonly.wikikernel.typescript.TypeScriptAssetService typeScriptAssetService;
    /** TypeScript 编译器（展示版本）。 */
    private final cc.thonly.wikikernel.typescript.TypeScriptCompiler typeScriptCompiler;

    public AccessControlSpecialPage(AccessControlService accessControlService,
                                    PermissionService permissionService,
                                    SiteSettingsService siteSettingsService,
                                    FeatureService featureService,
                                    PluginFeatureSections featureSections,
                                    WikiPageMapper wikiPageMapper,
                                    ObjectProvider<SpecialWikiPageService> specialWikiPageService,
                                    MessageSource messageSource,
                                    PermissionGroupLabels groupLabels,
                                    SiteNameService siteNameService,
                                    cc.thonly.wikikernel.typescript.TypeScriptAssetService typeScriptAssetService,
                                    cc.thonly.wikikernel.typescript.TypeScriptCompiler typeScriptCompiler) {
        this.accessControlService = accessControlService;
        this.permissionService = permissionService;
        this.siteSettingsService = siteSettingsService;
        this.featureService = featureService;
        this.featureSections = featureSections;
        this.wikiPageMapper = wikiPageMapper;
        this.specialWikiPageService = specialWikiPageService;
        this.messageSource = messageSource;
        this.groupLabels = groupLabels;
        this.siteNameService = siteNameService;
        this.typeScriptAssetService = typeScriptAssetService;
        this.typeScriptCompiler = typeScriptCompiler;
    }

    @Override
    public String name() {
        return "权限配置";
    }

    @Override
    public String displayName() {
        return message("special.accessControl");
    }

    @Override
    public List<String> aliases() {
        return List.of("AccessControl", "AccessRules");
    }

    @Override
    public String template() {
        return "special/access-control";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        model.put("canManage", user != null && permissionService.canManage(user.getId()));
        model.put("siteNameValue", siteNameService.name());
        model.put("allowOtherUserPageEdit",
                siteSettingsService.getBoolean(SiteSettingsService.ALLOW_OTHER_USER_PAGE_EDIT, false));
        model.put("registerEnabled", featureService.registerEnabled());
        // 插件自己注册的设置分区（common.extension.SiteSettingsSection）：core 只渲染「键 → 值」，
        // 不认识这些设置属于哪个功能；值从站点设置现读（插件声明了默认值）
        List<Map<String, Object>> pluginSettings = new ArrayList<>();
        for (SiteSettingsSection section : featureSections.sections().stream()
                .sorted(java.util.Comparator.comparingInt(SiteSettingsSection::order)
                        .thenComparing(SiteSettingsSection::id))
                .toList()) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (SettingField field : section.fields()) {
                String value = siteSettingsService.get(field.key(),
                        field.defaultValue() == null ? "" : field.defaultValue());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", field.key());
                row.put("inputId", "plugin-setting-" + field.key().replace('.', '-'));
                row.put("label", field.label());
                row.put("type", field.type());
                row.put("value", value);
                row.put("options", field.options());
                row.put("checked", "checkbox".equals(field.type()) && truthy(value));
                fields.add(row);
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", section.id());
            map.put("title", section.title());
            map.put("description", section.description());
            map.put("fields", fields);
            pluginSettings.add(map);
        }
        model.put("pluginSettings", pluginSettings);
        // 页面正文缓存（Redis 可选；每个键都现读，改完立即生效）
        model.put("cachePageEnabled", siteSettingsService.getBoolean("cache.page.enabled", false));
        model.put("cachePageBackend", siteSettingsService.get("cache.page.backend", "auto"));
        model.put("cachePageTtl", siteSettingsService.get("cache.page.ttl-seconds", "300"));
        model.put("cachePageMaxEntries", siteSettingsService.get("cache.page.max-entries", "500"));
        model.put("cachePagePreload", siteSettingsService.getBoolean("cache.page.preload-on-template-change", true));
        model.put("cachePageParallelism", siteSettingsService.get("cache.page.preload-parallelism", ""));
        model.put("typescriptFeatureEnabled", featureService.typescriptEnabled());
        model.put("typescriptCompileOnSaveEnabled", featureService.typescriptCompileOnSave());
        model.put("typescriptCompilerAvailable", typeScriptAssetService.enabled());
        model.put("typescriptCompilerVersion", typeScriptCompiler.version());
        // 源码大小上限（KB；-1 或 0 = 不限制）：站点设置里可随时改，立即生效
        model.put("typescriptMaxSourceKilobytes", typeScriptAssetService.maxSourceKilobytes());
        model.put("typescriptMaxSourceText", typeScriptAssetService.maxSourceKilobytesText());

        model.put("actions", List.of(AccessAction.values()).stream()
                .map(action -> Map.<String, Object>of(
                        "id", action.id(),
                        "name", message("access.action." + action.id(), action.id())))
                .toList());
        model.put("groups", permissionService.allTypes().stream()
                .map(type -> type.getName())
                .toList());

        // 默认规则（目标 *）：每个动作一行，值为规则命中值或内置默认值
        List<Map<String, Object>> defaults = new ArrayList<>();
        for (AccessAction action : AccessAction.values()) {
            Map<String, Object> row = ruleRow(action, "*",
                    accessControlService.requiredGroups(action, "*"), null);
            row.put("actionName", message("access.action." + action.id(), action.id()));
            defaults.add(row);
        }
        model.put("defaults", defaults);
        model.put("newRule", ruleRow(AccessAction.READ, "", "", null));
        model.put("rules", accessControlService.allRules().stream()
                .map(rule -> {
                    AccessAction action = AccessAction.byId(rule.getAction()).orElse(null);
                    if (action == null) {
                        return null;
                    }
                    Map<String, Object> row = ruleRow(action, rule.getTarget(), rule.getGroupNames(), rule.getId());
                    row.put("actionName", message("access.action." + action.id(), action.id()));
                    return row;
                })
                .filter(java.util.Objects::nonNull)
                .toList());

        // 目标输入框候选：最近的页面标题 + 已注册特殊页（规范名）
        Set<String> options = new LinkedHashSet<>();
        for (WikiPage page : wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .select(WikiPage::getTitle)
                .eq(WikiPage::getDeleted, false)
                .orderByDesc(WikiPage::getId)
                .last("LIMIT " + PAGE_OPTION_LIMIT))) {
            options.add(page.getTitle());
        }
        specialWikiPageService.getObject().listRegisteredPages()
                .forEach(page -> options.add("特殊:" + page.name()));
        model.put("pageOptions", options.stream()
                .map(title -> Map.of("title", title, "url", WikiUrls.pagePath(title)))
                .toList());
    }

    /** 一行规则的展示形态：scope（everyone/nobody/custom）+ 勾选状态 + 展示文案。 */
    private Map<String, Object> ruleRow(AccessAction action, String target, String groupNames, Long id) {
        String groups = groupNames == null ? "" : groupNames.trim();
        Set<String> groupSet = new LinkedHashSet<>();
        boolean everyone = false;
        boolean nobody = groups.isEmpty();
        if (AccessAction.EVERYONE.equals(groups)) {
            everyone = true;
            nobody = false;
        } else if (!nobody) {
            for (String group : groups.split("\\" + AccessAction.GROUP_SEPARATOR)) {
                if (!group.isBlank()) {
                    groupSet.add(group.trim());
                }
            }
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("target", target);
        row.put("action", action.id());
        row.put("everyone", everyone);
        row.put("nobody", nobody);
        row.put("groupSet", groupSet);
        row.put("scope", everyone ? "everyone" : nobody ? "nobody" : "groups");
        row.put("groupsText", everyone ? message("access.everyone")
                : nobody ? message("access.nobody")
                : String.join(message("common.listSeparator"), groupLabels.labels(groupSet)));
        return row;
    }

    /** 本地化文案（只用键，不写中文兜底：缺键时消息源会显示键本身）。 */
    private String message(String key) {
        return messageSource.getMessage(key, null, null, LocaleContextHolder.getLocale());
    }

    /**
     * 本地化文案 + 兜底值：兜底只允许**数据**（动作 id / 轮换模式名这类），不允许中文文案，
     * 找不到键且没有数据兜底时消息源会显示键本身。
     */
    private String message(String key, String fallback) {
        return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    /** 站点设置的布尔语义：true / 1 / on / yes（大小写不敏感）视为真。 */
    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }
}
