package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.service.SiteSettingsService;
import org.springframework.stereotype.Service;

/**
 * 站点设置桥（{@code SiteSettingsService} 的视图，对插件暴露 common 接口）：
 * 读取 + 写入（写入与 core 的 /init、站点设置页同一个存储）。
 */
@Service
public class SiteConfigImpl implements SiteConfig {

    private final SiteSettingsService siteSettingsService;

    public SiteConfigImpl(SiteSettingsService siteSettingsService) {
        this.siteSettingsService = siteSettingsService;
    }

    @Override
    public String get(String key, String defaultValue) {
        return siteSettingsService.get(key, defaultValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return siteSettingsService.getBoolean(key, defaultValue);
    }

    @Override
    public void set(String key, String value) {
        siteSettingsService.set(key, value);
    }
}
