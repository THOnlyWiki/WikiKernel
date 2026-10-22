package cc.thonly.wikikernel.plugin.registrationquiz;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.RegistrationFormField;
import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.common.plugin.support.SimplePluginContext;
import cc.thonly.wikikernel.plugin.registrationquiz.hook.RegistrationQuizFormField;
import cc.thonly.wikikernel.plugin.registrationquiz.hook.RegistrationQuizGuard;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegistrationQuizPlugin}：插件入口注册的扩展点（注册页字段 + 注册前钩子）、
 * 消息包键名与设置键常量（必须与 core 的站点设置一致）。
 */
class RegistrationQuizPluginTest {

    @Test
    void descriptorAndKeysMatchCoreSettings() {
        RegistrationQuizPlugin plugin = new RegistrationQuizPlugin();
        assertEquals("registration-quiz", plugin.descriptor().id());
        assertEquals("Registration Quiz", plugin.descriptor().name());
        assertEquals("features.registration-quiz", RegistrationQuizPlugin.ENABLED_KEY);
        assertEquals("registration.quiz", RegistrationQuizPlugin.QUIZ_KEY);
        assertEquals("registration.questions", RegistrationQuizPlugin.QUESTIONS_KEY);
        assertEquals("registration.answers", RegistrationQuizPlugin.ANSWERS_KEY);
        assertEquals("registration.question", RegistrationQuizPlugin.LEGACY_QUESTION_KEY);
    }

    @Test
    void onStartRegistersFormFieldGuardAndMessages() {
        SimplePluginContext context = SimplePluginContext.create(
                "registration-quiz", Path.of("build", "test-plugin-data"), Map.of());
        // 单元测试里没有 Spring：siteConfig / messageSource 为 null 时插件仍能装配（默认不启用）
        new RegistrationQuizPlugin().onStart(context);

        assertEquals(1, context.lookup(RegistrationFormField.class).size());
        assertInstanceOf(RegistrationQuizFormField.class, context.lookup(RegistrationFormField.class).get(0));
        assertEquals(1, context.lookup(RegistrationGuard.class).size());
        assertInstanceOf(RegistrationQuizGuard.class, context.lookup(RegistrationGuard.class).get(0));

        assertEquals(1, context.lookup(MessageBundle.class).size());
        Map<String, Map<String, String>> messages = context.lookup(MessageBundle.class).get(0).messages();
        // 键名与迁移前 core 的文案键一致
        assertTrue(messages.get("zh").containsKey("auth.registerAnswerHint"), messages.toString());
        assertTrue(messages.get("zh").containsKey("auth.registerAnswerWrong"), messages.toString());
        assertTrue(messages.get("en").containsKey("auth.registerAnswerWrong"), messages.toString());
    }
}
