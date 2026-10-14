package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import org.springframework.stereotype.Service;

/**
 * core 自己的功能开关实现（注册 / TypeScript 编译）：读写站点设置（缓存由 {@link SiteSettingsService} 负责）。
 * <p>
 * 插件自己的开关不在这里：插件在描述文件里声明 {@code plugin.featureKey}，core 自动渲染 / 保存，
 * 插件自己用 {@code common.service.SiteConfig} 读（见 {@code PluginFeatureSections}）。
 */
@Service
public class FeatureServiceImpl implements FeatureService {

    private final SiteSettingsService siteSettingsService;

    public FeatureServiceImpl(SiteSettingsService siteSettingsService) {
        this.siteSettingsService = siteSettingsService;
    }

    @Override
    public boolean registerEnabled() {
        return siteSettingsService.getBoolean(REGISTER_KEY, true);
    }

    @Override
    public boolean typescriptEnabled() {
        return siteSettingsService.getBoolean(TYPESCRIPT_KEY, false);
    }

    @Override
    public boolean typescriptCompileOnSave() {
        return siteSettingsService.getBoolean(TYPESCRIPT_COMPILE_ON_SAVE_KEY, false);
    }

    @Override
    public void setRegister(boolean enabled) {
        siteSettingsService.set(REGISTER_KEY, Boolean.toString(enabled));
    }

    @Override
    public void setTypescript(boolean enabled) {
        siteSettingsService.set(TYPESCRIPT_KEY, Boolean.toString(enabled));
    }

    @Override
    public void setTypescriptCompileOnSave(boolean enabled) {
        siteSettingsService.set(TYPESCRIPT_COMPILE_ON_SAVE_KEY, Boolean.toString(enabled));
    }
}
