package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.i18n.CountryNames;
import cc.thonly.wikikernel.mail.EmailTypeConfig;
import cc.thonly.wikikernel.mail.MailService;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.skin.ActiveSkin;
import cc.thonly.wikikernel.skin.SkinRegistry;
import cc.thonly.wikikernel.time.WikiTime;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:参数设置} / {@code Special:Preferences}：仿 MediaWiki 的参数设置页。
 * <p>
 * 标签：用户资料（基本信息 / 国际化 / 签名 / 电子邮件选项）、外观、编辑、最近更改、监视列表、搜索、其他。
 * 保存走 {@code POST /api/user/preferences}（邮件发送未实现，相关选项只存储）；
 * 「最近更改」「搜索」的默认值会实际生效（见 RecentChangesSpecialPage / SearchSpecialPage）。
 */
@Component
public class PreferencesSpecialPage implements SpecialPage {

    /** 「最近更改」默认条数 / 天数可选项（0 = 站点默认）。 */
    private static final List<Integer> RC_LIMIT_OPTIONS = List.of(0, 50, 100, 250, 500);
    private static final List<Integer> RC_DAYS_OPTIONS = List.of(0, 1, 3, 7, 30);

    private final UserService userService;
    private final PermissionService permissionService;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final SkinRegistry skinRegistry;
    private final MailService mailService;
    private final CountryNames countryNames;
    private final MessageSource messageSource;
    private final FeatureService featureService;
    private final PluginManager pluginManager;

    public PreferencesSpecialPage(UserService userService,
                                  PermissionService permissionService,
                                  WikiRevisionMapper wikiRevisionMapper,
                                   SkinRegistry skinRegistry,
                                   MailService mailService,
                                   CountryNames countryNames,
                                   MessageSource messageSource,
                                   FeatureService featureService,
                                   PluginManager pluginManager) {
        this.userService = userService;
        this.permissionService = permissionService;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.skinRegistry = skinRegistry;
        this.mailService = mailService;
        this.countryNames = countryNames;
        this.messageSource = messageSource;
        this.featureService = featureService;
        this.pluginManager = pluginManager;
    }

    @Override
    public String name() {
        return "参数设置";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.preferences", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Preferences");
    }

    @Override
    public String template() {
        return "special/preferences";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser viewer = request.user();
        if (viewer == null) {
            model.put("loginRequired", true);
            return;
        }
        WikiUser user = userService.findById(viewer.getId()).orElse(viewer);
        model.put("user", user);
        model.put("groups", permissionService.namesOf(user.getId()));
        model.put("emailConfirmed", Boolean.TRUE.equals(user.getEmailConfirmed()));
        model.put("emailConfirmedAt", user.getEmailConfirmedAt());
        model.put("emailConfirmedGroup", permissionService.has(user.getId(),
                PermissionService.GROUP_EMAIL_CONFIRMED));
        model.put("mailConfigured", mailService.isConfigured());
        model.put("mailConfirmEnabled", mailService.isEnabled(EmailTypeConfig.CONFIRM_EMAIL));
        model.put("editCount", wikiRevisionMapper.selectCount(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getUserId, user.getId())));
        model.put("userSkin", user.getSkin() == null ? "" : user.getSkin());
        int rcLimit = user.getRcLimit() == null ? 0 : user.getRcLimit();
        int rcDays = user.getRcDays() == null ? 0 : user.getRcDays();
        int searchLimit = user.getSearchLimit() == null ? 0 : user.getSearchLimit();
        model.put("rcLimitValue", rcLimit);
        model.put("rcDaysValue", rcDays);
        model.put("searchLimitValue", searchLimit);
        model.put("rcLimitOptions", RC_LIMIT_OPTIONS.stream()
                .map(value -> option(value, value == 0
                        ? message("preferences.rcDefault")
                        : message("recentChanges.changeCount", String.valueOf(value))))
                .toList());
        model.put("rcDaysOptions", RC_DAYS_OPTIONS.stream()
                .map(value -> option(value, value == 0
                        ? message("preferences.rcDefault")
                        : message("recentChanges.lastDays", String.valueOf(value))))
                .toList());
        model.put("defaultSkinId", skinRegistry.defaultSkinId());
        model.put("timeZoneValue", WikiTime.normalizeZone(user.getTimeZone()));
        model.put("timeZoneOptions", timeZoneOptions(user.getTimeZone()));
        String countryValue = Strings.trimToEmpty(user.getCountry());
        model.put("country", countryValue);
        model.put("countries", countryOptions(countryValue));
        model.put("skins", skinRegistry.available().stream()
                .map(skin -> {
                    ActiveSkin view = skinRegistry.view(skin);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", skin.id());
                    row.put("name", view.name());
                    row.put("description", view.description());
                    return row;
                })
                .toList());
        // 插件贡献的参数设置分区（PreferenceSection：Gadgets、AI 厂商密钥等由插件自己渲染表单）
        model.put("pluginPreferenceSections", pluginPreferenceSections(user));
        // AI 代理：我的厂商密钥（只用于自己的对话；网页保存、即时生效）
    }

    /**
     * 收集插件注册的参数设置分区（{@code common.extension.PreferenceSection}）：
     * id / 标签名 / 面板 HTML；插件自己负责表单提交（POST 到插件自己的接口）。
     */
    private List<Map<String, Object>> pluginPreferenceSections(WikiUser user) {
        if (this.pluginManager == null) {
            return List.of();
        }
        PreferenceContext context = new PreferenceContext() {
            @Override
            public Viewer viewer() {
                return new Viewer(user.getId(), user.getUsername(), false,
                        permissionService.namesOf(user.getId()), false);
            }

            @Override
            public String siteName() {
                return messageSource.getMessage("sitename", null, "WikiKernel", LocaleContextHolder.getLocale());
            }

            @Override
            public String baseUrl() {
                return currentBaseUrl();
            }

            @Override
            public String message(String key, String fallback) {
                return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
            }
        };
        List<Map<String, Object>> sections = new ArrayList<>();
        for (PreferenceSection section : this.pluginManager.extensions(PreferenceSection.class).stream()
                .sorted(Comparator.comparingInt(PreferenceSection::order))
                .toList()) {
            try {
                String html = section.html(context);
                if (html == null || html.isBlank()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", section.id());
                row.put("label", messageSource.getMessage(section.labelKey(), null,
                        section.fallbackLabel(), LocaleContextHolder.getLocale()));
                row.put("html", html);
                sections.add(row);
            } catch (RuntimeException ignored) {
                // 插件异常不影响参数设置页
            }
        }
        return sections;
    }

    /** 当前请求的站点根地址（供插件分区拼链接；无请求上下文时返回空串）。 */
    private static String currentBaseUrl() {
        if (!(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes)) {
            return "";
        }
        return cc.thonly.wikikernel.web.RequestHeadContext.buildBaseUrl(attributes.getRequest());
    }

    private Map<String, Object> option(int value, String label) {        Map<String, Object> option = new LinkedHashMap<>();
        option.put("value", value);
        option.put("label", label);
        return option;
    }

    private Map<String, Object> option(String value, String label) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("value", value);
        option.put("label", label);
        return option;
    }

    /**
     * 时区下拉框：第一项是「服务器时区（默认）」，其余为全部 IANA 时区，
     * 按当前 UTC 偏移排序、同偏移按区域 id 排序，显示为 {@code (UTC+08:00) Asia/Shanghai}。
     */
    private List<Map<String, Object>> timeZoneOptions(String current) {
        List<String> ids = new ArrayList<>(WikiTime.availableZoneIds());
        ids.sort(Comparator.comparingInt(WikiTime::offsetSeconds).thenComparing(Comparator.naturalOrder()));
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option("", message("preferences.timezoneDefault")));
        Instant now = Instant.now();
        for (String id : ids) {
            ZoneOffset offset = ZoneId.of(id).getRules().getOffset(now);
            String utc = offset.getTotalSeconds() == 0 ? "UTC+00:00" : "UTC" + offset.getId();
            options.add(option(id, "(" + utc + ") " + id));
        }
        return options;
    }

    /** 本地化文案（只用键：缺键时消息源会显示键本身，不写中文兜底）。 */
    private String message(String key, String... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    /**
     * 国家 / 地区下拉框的选项（249 个 ISO 3166-1 地区）。
     * <p>
     * 名称来自 JDK 自带的 CLDR 数据（只有 TW / HK / MO 用 {@code country.*} 消息键覆盖，
     * 见 {@code i18n.CountryNames}），按当前语言下的名称排序；
     * 「未指定」（空值）那一项在模板里写死，不属于这个列表。
     */
    private List<Map<String, Object>> countryOptions(String current) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (CountryNames.LocalizedName entry : countryNames.localizedNames(LocaleContextHolder.getLocale())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", entry.code());
            row.put("name", entry.name());
            row.put("selected", entry.code().equals(current));
            options.add(row);
        }
        return options;
    }
}
