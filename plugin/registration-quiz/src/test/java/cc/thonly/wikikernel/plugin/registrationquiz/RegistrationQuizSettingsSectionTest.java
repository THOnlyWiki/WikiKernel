package cc.thonly.wikikernel.plugin.registrationquiz;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册问答的「站点设置分区 + 安装向导步骤」：字段键与 {@link RegistrationQuizSettings} 一致，
 * 向导 apply 写迁移前 core 用的同一批 YAML 路径（{@code wikikernel.registration.*} + 功能开关）。
 */
class RegistrationQuizSettingsSectionTest {

    @Test
    void sectionDeclaresExactlyTheSettingsKeys() {
        RegistrationQuizSettingsSection section = new RegistrationQuizSettingsSection(new StaticMessageSource());
        List<String> keys = section.fields().stream().map(SettingField::key).toList();
        assertEquals(List.of(RegistrationQuizSettings.ENABLED_KEY, RegistrationQuizSettings.QUIZ_KEY), keys);
        assertEquals("registration-quiz", section.id());
    }

    @Test
    void sectionPrefillsTheOldQuestionsAndAnswersAsCombinedLines() {
        RegistrationQuizSettingsSection section = new RegistrationQuizSettingsSection(
                new StaticMessageSource(),
                RegistrationQuizFakes.config(false, "问题一\n问题二", "答案一\n答案二", ""));
        SettingField quiz = section.fields().stream()
                .filter(field -> RegistrationQuizSettings.QUIZ_KEY.equals(field.key()))
                .findFirst()
                .orElseThrow();
        assertEquals("问题一 | 答案一\n问题二 | 答案二", quiz.defaultValue());
    }

    @Test
    void setupStepWritesTheCombinedQuizAndRejectsInvalidLines() {
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
        RegistrationQuizSetupStep step = new RegistrationQuizSetupStep(new StaticMessageSource());
        List<String> keys = step.fields().stream().map(SetupField::key).toList();
        assertEquals(List.of("enabled", "quiz"), keys);

        SetupResult ok = step.apply(Map.of(
                "enabled", "true",
                "quiz", "1+1=? | 2 | 二\n2+2=? | 4"), context);
        assertTrue(ok.success());
        assertEquals(Boolean.TRUE, globals.get("wikikernel.features.registration-quiz"));
        assertEquals("1+1=? | 2 | 二\n2+2=? | 4", globals.get("wikikernel.registration.quiz"));
        assertEquals(List.of(), globals.get("wikikernel.registration.questions"));
        assertEquals(List.of(), globals.get("wikikernel.registration.answers"));
        assertEquals("", globals.get("wikikernel.registration.question"));

        SetupResult invalid = step.apply(Map.of("enabled", "true", "quiz", "只有一题"), context);
        assertFalse(invalid.success(), "没有 | 分隔符的行应给出错误提示");
    }
}
