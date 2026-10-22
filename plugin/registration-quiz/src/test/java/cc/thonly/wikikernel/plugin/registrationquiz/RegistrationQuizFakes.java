package cc.thonly.wikikernel.plugin.registrationquiz;

import cc.thonly.wikikernel.common.extension.RegistrationContext;
import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.service.SiteConfig;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

/** 测试替身：站点设置、注册上下文与文案源（不启动 Spring / core）。 */
public final class RegistrationQuizFakes {

    private RegistrationQuizFakes() {
    }

    /** 站点设置替身：只认注册问答的几个键（旧格式）。 */
    public static SiteConfig config(boolean enabled, String questions, String answers, String legacyQuestion) {
        return config(enabled, "", questions, answers, legacyQuestion);
    }

    /** 站点设置替身：带新格式 quiz 的版本。 */
    public static SiteConfig config(boolean enabled, String quiz, String questions, String answers,
                                    String legacyQuestion) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(RegistrationQuizSettings.QUIZ_KEY, quiz == null ? "" : quiz);
        values.put(RegistrationQuizSettings.QUESTIONS_KEY, questions == null ? "" : questions);
        values.put(RegistrationQuizSettings.ANSWERS_KEY, answers == null ? "" : answers);
        values.put(RegistrationQuizSettings.LEGACY_QUESTION_KEY, legacyQuestion == null ? "" : legacyQuestion);
        return new SiteConfig() {
            @Override
            public String get(String key, String defaultValue) {
                return values.getOrDefault(key, defaultValue);
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                return RegistrationQuizSettings.ENABLED_KEY.equals(key) ? enabled : defaultValue;
            }
        };
    }

    /** 文案源替身：命中给定键返回对应文案，否则返回 fallback。 */
    public static MessageSource messages(Map<String, String> texts) {
        return new MessageSource() {
            @Override
            public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
                return texts.getOrDefault(code, defaultMessage);
            }

            @Override
            public String getMessage(String code, Object[] args, Locale locale) {
                return texts.getOrDefault(code, code);
            }

            @Override
            public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
                return resolvable.getDefaultMessage();
            }
        };
    }

    /** 注册提交上下文（source=web，带表单参数）。 */
    public static RegistrationContext submit(String username, String email, Map<String, String> parameters) {
        return new FakeRegistrationContext(username, email, parameters, RegistrationContext.SOURCE_WEB);
    }

    /** 注册页渲染上下文（还没有用户名 / 邮箱）。 */
    public static RegistrationContext form() {
        return new FakeRegistrationContext("", "", Map.of(), RegistrationContext.SOURCE_WEB);
    }

    /** {@link RegistrationContext} 测试实现。 */
    public static final class FakeRegistrationContext implements RegistrationContext {

        private final String username;
        private final String email;
        private final Map<String, String> parameters;
        private final String source;

        public FakeRegistrationContext(String username, String email, Map<String, String> parameters, String source) {
            this.username = username;
            this.email = email;
            this.parameters = parameters == null ? Map.of() : new LinkedHashMap<>(parameters);
            this.source = source;
        }

        @Override
        public String username() {
            return username;
        }

        @Override
        public String email() {
            return email;
        }

        @Override
        public Map<String, String> parameters() {
            return parameters;
        }

        @Override
        public SessionAttributes session() {
            return SessionAttributes.NONE;
        }

        @Override
        public String clientIp() {
            return "127.0.0.1";
        }

        @Override
        public String source() {
            return source;
        }
    }
}
