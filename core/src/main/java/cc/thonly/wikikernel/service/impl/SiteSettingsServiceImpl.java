package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiSetting;
import cc.thonly.wikikernel.config.FeatureProperties;
import cc.thonly.wikikernel.config.PermissionProperties;
import cc.thonly.wikikernel.config.SiteProperties;
import cc.thonly.wikikernel.db.mapper.WikiSettingMapper;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 站点设置实现：全表缓存 + 写时刷新；启动时把 LocalSettings 里显式配置的默认值导入数据库
 * （与访问规则同一约定：文件里写了的每次启动覆盖，非 null 才导入）。
 */
@Service
@Order(3) // Auto-DDL（0）与迁移（1）、访问规则导入（2）之后
public class SiteSettingsServiceImpl implements SiteSettingsService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SiteSettingsServiceImpl.class);

    private final WikiSettingMapper settingMapper;
    private final PermissionProperties properties;
    private final FeatureProperties featureProperties;
    private final SiteProperties siteProperties;

    private volatile Map<String, String> cache;

    public SiteSettingsServiceImpl(WikiSettingMapper settingMapper,
                                   PermissionProperties properties,
                                   FeatureProperties featureProperties,
                                   SiteProperties siteProperties) {
        this.settingMapper = settingMapper;
        this.properties = properties;
        this.featureProperties = featureProperties;
        this.siteProperties = siteProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (siteProperties.getName() != null) {
            set(SiteNameService.KEY, SiteNameService.normalize(siteProperties.getName()));
            log.info("已导入站点名称：{} = {}", SiteNameService.KEY,
                    SiteNameService.normalize(siteProperties.getName()));
        }
        if (siteProperties.getFavicon() != null) {
            set(SiteSettingsService.FAVICON_KEY, normalizeFavicon(siteProperties.getFavicon()));
            log.info("已导入站点图标：{} = {}", SiteSettingsService.FAVICON_KEY,
                    normalizeFavicon(siteProperties.getFavicon()));
        }
        if (siteProperties.getNoReferrer() != null) {
            set(SiteSettingsService.NO_REFERRER_KEY, siteProperties.getNoReferrer().toString());
            log.info("已导入 Referrer 策略：{} = {}", SiteSettingsService.NO_REFERRER_KEY,
                    siteProperties.getNoReferrer());
        }
        if (properties.getAllowOtherUserPageEdit() != null) {
            set(ALLOW_OTHER_USER_PAGE_EDIT, properties.getAllowOtherUserPageEdit().toString());
            log.info("已导入站点设置：{} = {}", ALLOW_OTHER_USER_PAGE_EDIT,
                    properties.getAllowOtherUserPageEdit());
        }
        if (properties.getAllowAnonymousRecentChanges() != null) {
            set(ALLOW_ANONYMOUS_RECENT_CHANGES_KEY,
                    properties.getAllowAnonymousRecentChanges().toString());
            log.info("已导入站点设置：{} = {}", ALLOW_ANONYMOUS_RECENT_CHANGES_KEY,
                    properties.getAllowAnonymousRecentChanges());
        }
        if (featureProperties.getRegister() != null) {
            set(FeatureService.REGISTER_KEY, featureProperties.getRegister().toString());
            log.info("已导入功能开关：{} = {}", FeatureService.REGISTER_KEY, featureProperties.getRegister());
        }
        if (featureProperties.getTypescript() != null) {
            set(FeatureService.TYPESCRIPT_KEY, featureProperties.getTypescript().toString());
            log.info("已导入功能开关：{} = {}", FeatureService.TYPESCRIPT_KEY, featureProperties.getTypescript());
        }
        if (featureProperties.getTypescriptCompileOnSave() != null) {
            set(FeatureService.TYPESCRIPT_COMPILE_ON_SAVE_KEY,
                    featureProperties.getTypescriptCompileOnSave().toString());
            log.info("已导入功能开关：{} = {}", FeatureService.TYPESCRIPT_COMPILE_ON_SAVE_KEY,
                    featureProperties.getTypescriptCompileOnSave());
        }
        if (featureProperties.getCodexExample() != null) {
            set(FeatureService.CODEX_EXAMPLE_KEY, featureProperties.getCodexExample().toString());
            log.info("已导入功能开关：{} = {}", FeatureService.CODEX_EXAMPLE_KEY,
                    featureProperties.getCodexExample());
        }
    }

    @Override
    public String get(String key, String defaultValue) {
        String value = loaded().get(key);
        return value == null ? defaultValue : value;
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = loaded().get(key);
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }

    @Override
    public void set(String key, String value) {
        WikiSetting existing = settingMapper.selectOne(Wrappers.<WikiSetting>lambdaQuery()
                .eq(WikiSetting::getSettingKey, key)
                .last("LIMIT 1"));
        if (existing == null) {
            WikiSetting setting = new WikiSetting(null, key, value == null ? "" : value);
            settingMapper.insert(setting);
        } else {
            existing.setSettingValue(value == null ? "" : value);
            settingMapper.updateById(existing);
        }
        reload();
    }

    @Override
    public void reload() {
        synchronized (this) {
            cache = null;
        }
    }

    /** 站点图标 / 默认头像只接受空串、站内绝对路径或 http(s) URL，避免 javascript: 等进入页面。 */
    public static String normalizeFavicon(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.startsWith("/") || lower.startsWith("http://") || lower.startsWith("https://")) {
            return value;
        }
        return "";
    }

    /** 默认头像 URL 的归一化规则与 favicon 相同（留空 = 使用内置默认头像）。 */
    public static String normalizeAvatar(String raw) {
        return normalizeFavicon(raw);
    }

    /**
     * robots.txt 文本归一化：统一换行、去掉首尾空白；留空表示使用内置默认规则。
     * 限制在 64KB 以内，避免管理员误粘贴超大文本影响每个爬虫请求。
     */
    public static String normalizeRobots(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (value.length() > 64 * 1024) {
            value = value.substring(0, 64 * 1024);
        }
        return value;
    }

    private Map<String, String> loaded() {
        Map<String, String> current = cache;
        if (current == null) {
            synchronized (this) {
                current = cache;
                if (current == null) {
                    Map<String, String> values = new LinkedHashMap<>();
                    List<WikiSetting> settings = settingMapper.selectList(null);
                    for (WikiSetting setting : settings) {
                        values.put(setting.getSettingKey(), setting.getSettingValue());
                    }
                    current = Map.copyOf(values);
                    cache = current;
                }
            }
        }
        return current;
    }
}
