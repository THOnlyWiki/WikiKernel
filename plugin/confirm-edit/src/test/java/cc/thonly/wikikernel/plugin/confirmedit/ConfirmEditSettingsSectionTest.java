package cc.thonly.wikikernel.plugin.confirmedit;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.SetupField;
import cc.thonly.wikikernel.common.extension.SetupResult;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑验证码的「站点设置分区 + 安装向导步骤」：字段键与 {@link ConfirmEditSettings} 一致，
 * 向导 apply 写迁移前 core 用的同一批 YAML 路径。
 */
class ConfirmEditSettingsSectionTest {

    @Test
    void sectionDeclaresExactlyTheSettingsKeys() {
        ConfirmEditSettingsSection section = new ConfirmEditSettingsSection(new StaticMessageSource());
        List<String> keys = section.fields().stream().map(SettingField::key).toList();
        assertEquals(List.of(ConfirmEditSettings.ENABLED_KEY, ConfirmEditSettings.BYPASS_GROUPS_KEY), keys);
        assertEquals("confirm-edit", section.id());
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
        ConfirmEditSetupStep step = new ConfirmEditSetupStep(new StaticMessageSource());
        List<String> keys = step.fields().stream().map(SetupField::key).toList();
        assertEquals(List.of("enabled", "bypassGroups"), keys);

        SetupResult result = step.apply(Map.of("enabled", "true", "bypassGroups", "管理员|机器人"), context);
        assertTrue(result.success());
        assertEquals(Boolean.TRUE, globals.get("wikikernel.features.confirm-edit.enabled"));
        assertEquals(List.of("管理员", "机器人"),
                globals.get("wikikernel.features.confirm-edit.bypass-groups"));
    }
}
