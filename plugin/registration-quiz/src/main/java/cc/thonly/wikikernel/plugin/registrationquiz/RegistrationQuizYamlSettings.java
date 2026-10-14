package cc.thonly.wikikernel.plugin.registrationquiz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LocalSettings.yaml 的注册问答配置（与迁移前 core 的键**完全一致**）：
 * <ul>
 *     <li>{@code wikikernel.features.registration-quiz}：功能开关；</li>
 *     <li>{@code wikikernel.registration.questions} / {@code answers}：题目 / 答案数组（按下标对应）；</li>
 *     <li>{@code wikikernel.registration.question}：旧格式单问题（兼容旧配置）。</li>
 * </ul>
 * 插件在 {@code onStart} 里把它导入站点设置（写了的值每次启动覆盖一次）。
 */
@Component
@ConfigurationProperties(prefix = "wikikernel")
public class RegistrationQuizYamlSettings {

    private Features features = new Features();
    private Registration registration = new Registration();

    public Features getFeatures() {
        return features;
    }

    public void setFeatures(Features features) {
        this.features = features;
    }

    public Registration getRegistration() {
        return registration;
    }

    public void setRegistration(Registration registration) {
        this.registration = registration;
    }

    /** {@code wikikernel.features.*}（只声明注册问答这一项）。 */
    public static class Features {

        private Boolean registrationQuiz;

        public Boolean getRegistrationQuiz() {
            return registrationQuiz;
        }

        public void setRegistrationQuiz(Boolean registrationQuiz) {
            this.registrationQuiz = registrationQuiz;
        }
    }

    /** {@code wikikernel.registration.*}。 */
    public static class Registration {

        private List<String> questions = List.of();
        private List<String> answers = List.of();
        private String question;

        public List<String> getQuestions() {
            return questions;
        }

        public void setQuestions(List<String> questions) {
            this.questions = questions == null ? List.of() : questions;
        }

        public List<String> getAnswers() {
            return answers;
        }

        public void setAnswers(List<String> answers) {
            this.answers = answers == null ? List.of() : answers;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }
    }
}
