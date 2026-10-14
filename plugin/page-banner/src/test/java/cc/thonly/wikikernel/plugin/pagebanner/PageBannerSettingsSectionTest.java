package cc.thonly.wikikernel.plugin.pagebanner;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.SetupField;
import cc.thonly.wikikernel.common.extension.SetupResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面横幅的「站点设置分区 + 安装向导步骤」：字段键必须与 {@link PageBannerSettings} 读的设置键一致
 * （core 只按声明的键渲染 / 保存），向导 apply 写的是迁移前 core 用的同一批 YAML 路径。
 */
class PageBannerSettingsSectionTest {

    private final PageBannerSettingsSection section = new PageBannerSettingsSection(
            new org.springframework.context.support.StaticMessageSource());

    @Test
    void sectionDeclaresExactlyTheSettingsKeys() {
        List<String> keys = section.fields().stream().map(SettingField::key).toList();
        assertTrue(keys.contains(PageBannerSettings.ENABLED_KEY));
        assertTrue(keys.contains(PageBannerSettings.FILES_KEY));
        assertTrue(keys.contains(PageBannerSettings.WHITELIST_ENABLED_KEY));
        assertTrue(keys.contains(PageBannerSettings.WHITELIST_KEY));
        assertTrue(keys.contains(PageBannerSettings.ROTATION_KEY));
        assertTrue(keys.contains(PageBannerSettings.PER_PAGE_KEY));
        assertTrue(keys.contains(PageBannerSettings.INTERVAL_KEY));
        assertTrue(keys.contains(PageBannerSettings.TRANSITION_KEY));
        assertTrue(keys.contains(PageBannerSettings.CONTROLS_KEY));
        assertTrue(keys.contains(PageBannerSettings.PAUSE_ON_HOVER_KEY));
        assertEquals("page-banner", section.id());
        assertEquals(10, keys.size());
    }

    @Test
    void setupStepWritesTheSameYamlPathsAsBeforeTheMigration() {
        Map<String, Object> globals = new LinkedHashMap<>();
        SetupContext context = new SetupContext() {
            @Override
            public void putGlobal(String path, Object value) {
                globals.put(path, value);
            }

            @Override
            public void putPluginSetting(String key, Object value) {
            }
        };
        PageBannerSetupStep step = new PageBannerSetupStep(
                new org.springframework.context.support.StaticMessageSource());
        List<String> keys = step.fields().stream().map(SetupField::key).toList();
        assertEquals(List.of("enabled", "files", "whitelistEnabled", "whitelist", "rotation", "perPage"), keys);

        SetupResult result = step.apply(Map.of(
                "enabled", "true",
                "files", "甲.png|乙.png",
                "whitelistEnabled", "true",
                "whitelist", "首页",
                "rotation", "daily",
                "perPage", "首页=甲.png"), context);
        assertTrue(result.success());
        assertEquals(Boolean.TRUE, globals.get("wikikernel.features.page-banner.enabled"));
        assertEquals(List.of("甲.png", "乙.png"), globals.get("wikikernel.features.page-banner.files"));
        assertEquals(Boolean.TRUE, globals.get("wikikernel.features.page-banner.whitelist-enabled"));
        assertEquals(List.of("首页"), globals.get("wikikernel.features.page-banner.whitelist"));
        assertEquals("daily", globals.get("wikikernel.features.page-banner.rotation"));
        assertEquals(List.of("首页=甲.png"), globals.get("wikikernel.features.page-banner.per-page"));
    }
}
