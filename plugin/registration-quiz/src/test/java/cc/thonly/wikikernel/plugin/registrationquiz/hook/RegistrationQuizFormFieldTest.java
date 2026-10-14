package cc.thonly.wikikernel.plugin.registrationquiz.hook;

import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizFakes;
import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizSettings;
import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizSupport;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizFakes.config;
import static cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizFakes.form;
import static cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizFakes.messages;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册页表单字段：题目 + **隐藏的题目下标** + 答案输入框（字段名与迁移前 core 模板一致）、
 * 题目与下标一一对应、开关关闭 / 题库为空时不渲染。
 */
class RegistrationQuizFormFieldTest {

    private static final Pattern INDEX = Pattern.compile("name=\"registerQuizIndex\" value=\"(\\d+)\"");

    private static final String QUESTIONS = "1 + 1 = ?\n2 + 2 = ?";
    private static final String ANSWERS = "2\n4";

    private static RegistrationQuizFormField field(boolean enabled, String questions, String answers) {
        RegistrationQuizSettings settings = new RegistrationQuizSettings(config(enabled, questions, answers, ""));
        return new RegistrationQuizFormField(settings, new RegistrationQuizSupport(messages(Map.of(
                "auth.registerAnswerHint", "请回答上面的注册问题（每次注册随机抽取一题），答对才能完成注册。"))));
    }

    @Test
    void rendersQuestionIndexAndAnswerInput() {
        RegistrationQuizFormField field = field(true, QUESTIONS, ANSWERS);
        String[] questions = QUESTIONS.split("\n");
        for (int attempt = 0; attempt < 20; attempt++) {
            String html = field.html(form());
            assertNotNull(html);
            Matcher matcher = INDEX.matcher(html);
            assertTrue(matcher.find(), "应带隐藏的题目下标：" + html);
            int index = Integer.parseInt(matcher.group(1));
            assertTrue(index >= 0 && index < questions.length, "下标应在题库范围内：" + index);
            assertTrue(html.contains(questions[index]), "显示的问题应与下标对应：" + html);
            assertTrue(html.contains("name=\"registerAnswer\""), html);
            assertTrue(html.contains("id=\"wpRegisterAnswer\""), html);
            assertTrue(html.contains("autocomplete=\"off\""), html);
            assertTrue(html.contains("请回答上面的注册问题"), html);
        }
    }

    @Test
    void htmlEscapesQuestionText() {
        String html = field(true, "1 < 2 吗？", "是").html(form());
        assertNotNull(html);
        assertTrue(html.contains("1 &lt; 2 吗？"), html);
        assertTrue(html.contains("name=\"registerQuizIndex\" value=\"0\""), html);
    }

    @Test
    void hiddenWhenDisabledOrBankEmpty() {
        assertNull(field(false, QUESTIONS, ANSWERS).html(form()), "开关关闭时不渲染");
        assertNull(field(true, "", "").html(form()), "题库为空时不渲染");
        assertNull(field(true, "只有问题", "").html(form()), "问题没有对应答案时不渲染（按较短配对为空）");
    }
}
