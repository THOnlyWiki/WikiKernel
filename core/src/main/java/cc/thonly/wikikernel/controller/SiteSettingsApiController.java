package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.typescript.TypeScriptAssetService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.plugin.PluginFeatureSections;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点设置 API（{@code @RestController}）：目前只有「是否允许编辑其他用户的用户页」；
 * 只有管理员 / 行政员可改。
 */
@RestController
@RequestMapping("/api/site-settings")
public class SiteSettingsApiController {

    private final SiteSettingsService siteSettingsService;
    private final FeatureService featureService;
    /** Plugin-contributed settings sections (core only renders key/value). */
    private final PluginFeatureSections featureSections;
    private final PermissionService permissionService;
    /** TypeScript 编译产物服务（读取生效的源码大小上限，用于校验设置值）。 */
    private final TypeScriptAssetService typescriptAssetService;
    private final MessageSource messageSource;

    public SiteSettingsApiController(SiteSettingsService siteSettingsService,
                                     FeatureService featureService,
                                     PluginFeatureSections featureSections,
                                     PermissionService permissionService,
                                     TypeScriptAssetService typescriptAssetService,
                                     MessageSource messageSource) {
        this.siteSettingsService = siteSettingsService;
        this.featureService = featureService;
        this.featureSections = featureSections;
        this.permissionService = permissionService;
        this.typescriptAssetService = typescriptAssetService;
        this.messageSource = messageSource;
    }

    /** 保存站点设置：站点名称（siteName）+ 复选框（allowOtherUserPageEdit / userInteraction / comments / multiUpload / widgets / register / abuseFilter）+ 注册问答（registerQuestion / registerAnswers 文本）。 */
    @PostMapping
    public AjaxResult<Void> save(@RequestParam(value = "siteName", required = false) String siteName,
                                 @RequestParam(value = "allowOtherUserPageEdit", required = false) String allow,
                                 @RequestParam(value = "register", required = false) String register,
                                 @RequestParam(value = "cachePageEnabled", required = false) String cachePageEnabled,
                                 @RequestParam(value = "cachePageBackend", required = false) String cachePageBackend,
                                 @RequestParam(value = "cachePageTtl", required = false) String cachePageTtl,
                                 @RequestParam(value = "cachePageMaxEntries", required = false) String cachePageMaxEntries,
                                 @RequestParam(value = "cachePagePreload", required = false) String cachePagePreload,
                                 @RequestParam(value = "cachePageParallelism", required = false) String cachePageParallelism,
                                 @RequestParam(value = "typescript", required = false) String typescript,
                                 @RequestParam(value = "typescriptCompileOnSave", required = false) String typescriptCompileOnSave,
                                 @RequestParam(value = "typescriptMaxSourceKilobytes", required = false) String typescriptMaxSourceKilobytes,
                                 @RequestParam MultiValueMap<String, String> allParams,
                                 HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        if (!permissionService.canManage(user.getId())) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("access.forbidden"));
        }
        if (siteName != null) {
            // 只有提交了该字段才改：老客户端 / 只改开关的调用不会动站点名称；空 = 回到默认名
            siteSettingsService.set(SiteNameService.KEY, SiteNameService.normalize(siteName));
        }
        siteSettingsService.set(SiteSettingsService.ALLOW_OTHER_USER_PAGE_EDIT,
                Boolean.toString(checked(allow)));
        featureService.setRegister(checked(register));
        // 页面正文缓存（core 的 PageCacheService 每次现读这些键，改完立即生效）
        if (cachePageEnabled != null) {
            siteSettingsService.set("cache.page.enabled", Boolean.toString(checked(cachePageEnabled)));
        }
        if (cachePageBackend != null) {
            siteSettingsService.set("cache.page.backend", Strings.trimToEmpty(cachePageBackend));
        }
        if (cachePageTtl != null) {
            siteSettingsService.set("cache.page.ttl-seconds", Strings.trimToEmpty(cachePageTtl));
        }
        if (cachePageMaxEntries != null) {
            siteSettingsService.set("cache.page.max-entries", Strings.trimToEmpty(cachePageMaxEntries));
        }
        if (cachePagePreload != null) {
            siteSettingsService.set("cache.page.preload-on-template-change", Boolean.toString(checked(cachePagePreload)));
        }
        if (cachePageParallelism != null) {
            siteSettingsService.set("cache.page.preload-parallelism", Strings.trimToEmpty(cachePageParallelism));
        }
        featureService.setTypescript(checked(typescript));
        featureService.setTypescriptCompileOnSave(checked(typescriptCompileOnSave));
        if (typescriptMaxSourceKilobytes != null) {
            // 源码大小上限：空 = 用配置文件里的默认值；数字原样；-1 / 0 = 不限制；其它非法值报错
            String raw = typescriptMaxSourceKilobytes.trim();
            if (raw.isEmpty()) {
                siteSettingsService.set(TypeScriptAssetService.MAX_SOURCE_KB_KEY, "");
            } else {
                try {
                    Long.parseLong(raw);
                } catch (NumberFormatException exception) {
                    return AjaxResult.error(AjaxCode.BAD_REQUEST, message("settings.typescriptMaxSourceInvalid"));
                }
                siteSettingsService.set(TypeScriptAssetService.MAX_SOURCE_KB_KEY,
                        String.valueOf(TypeScriptAssetService.normalizeMaxSourceKilobytes(
                                raw, typescriptAssetService.maxSourceKilobytes())));
            }
        }
        // 滥用过滤器：core 只写开关；系统账号「滥用过滤器」由插件 abuse-filter 在加载 / 首次评估时创建
        // （迁移前 core 的 AbuseFilterAccountSeeder 认识这个名字，现已迁到插件）
        // 插件自己注册的设置分区（common.extension.SiteSettingsSection）：表单里每个分区带一个隐藏的
        // plugin-section 标记，core 按分区声明的字段写站点设置（checkbox 未勾选 = false），
        // 不认识这些键属于哪个功能
        for (String sectionId : allParams.getOrDefault("plugin-section", List.of())) {
            SiteSettingsSection section = featureSections.sections().stream()
                    .filter(candidate -> candidate.id().equals(sectionId))
                    .findFirst()
                    .orElse(null);
            if (section == null) {
                continue;
            }
            for (SettingField field : section.fields()) {
                if ("checkbox".equals(field.type())) {
                    String value = allParams.getFirst(field.key());
                    siteSettingsService.set(field.key(),
                            Boolean.toString(checked(value == null ? "" : value)));
                } else if (allParams.containsKey(field.key())) {
                    siteSettingsService.set(field.key(),
                            Strings.trimToEmpty(allParams.getFirst(field.key())));
                }
            }
        }
        // 浏览器指纹登录（插件 fingerprint-login）：core 只写开关；指纹表 wiki_fingerprint
        // 与读写都在插件内（core 按插件声明的实体建表，插件走 PluginDatabase 访问）
        return AjaxResult.success(message("settings.saved"), null);
    }

    /** 复选框：on/1/true/yes = 勾选；缺省 / 其它 = 未勾选。 */
    private static boolean checked(String value) {
        return "on".equalsIgnoreCase(value) || "1".equals(value)
                || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
