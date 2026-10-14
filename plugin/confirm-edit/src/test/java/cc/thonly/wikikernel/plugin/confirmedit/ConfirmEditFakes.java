package cc.thonly.wikikernel.plugin.confirmedit;

import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditFormContext;
import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.SiteConfig;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

/** 测试替身：站点设置、会话、文案源与编辑上下文（不启动 Spring / core）。 */
public final class ConfirmEditFakes {

    private ConfirmEditFakes() {
    }

    /** 站点设置替身：只认开关与免验证组两个键。 */
    public static SiteConfig config(boolean enabled, String bypassGroups) {
        return new SiteConfig() {
            @Override
            public String get(String key, String defaultValue) {
                if (ConfirmEditSettings.BYPASS_GROUPS_KEY.equals(key)) {
                    return bypassGroups == null ? defaultValue : bypassGroups;
                }
                return defaultValue;
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                return ConfirmEditSettings.ENABLED_KEY.equals(key) ? enabled : defaultValue;
            }
        };
    }

    /** 会话替身（HashMap）：模拟 HttpSession 的属性读写。 */
    public static SessionAttributes session() {
        return new SessionAttributes() {
            private final Map<String, Object> values = new HashMap<>();

            @Override
            public Object getAttribute(String name) {
                return values.get(name);
            }

            @Override
            public void setAttribute(String name, Object value) {
                if (value == null) {
                    values.remove(name);
                } else {
                    values.put(name, value);
                }
            }

            @Override
            public void removeAttribute(String name) {
                values.remove(name);
            }
        };
    }

    /** 文案源替身：返回 fallback（等价于插件消息缺失时用兜底文案）。 */
    public static MessageSource messages() {
        return messages(Map.of());
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

    /** 固定随机源：依次返回给定下标（越界后重复最后一个），用于复现题目。 */
    public static Random sequence(int... values) {
        return new Random() {
            private int index;

            @Override
            public int nextInt(int bound) {
                int value = values[Math.min(index, values.length - 1)];
                index++;
                return Math.floorMod(value, bound);
            }
        };
    }

    /** 网页编辑提交上下文（source=web）。 */
    public static FakeEditContext web(Viewer viewer, SessionAttributes session, Map<String, String> parameters) {
        return new FakeEditContext(viewer, session, parameters, EditContext.SOURCE_WEB);
    }

    /** API 编辑提交上下文（source=api）。 */
    public static FakeEditContext api(Viewer viewer, SessionAttributes session, Map<String, String> parameters) {
        return new FakeEditContext(viewer, session, parameters, EditContext.SOURCE_API);
    }

    /** 编辑页渲染上下文（表单字段用）。 */
    public static FakeEditContext form(Viewer viewer, SessionAttributes session) {
        return new FakeEditContext(viewer, session, Map.of(), EditContext.SOURCE_WEB);
    }

    /** 同时实现 {@link EditContext} 与 {@link EditFormContext} 的测试上下文。 */
    public static final class FakeEditContext implements EditContext, EditFormContext {

        private final Viewer viewer;
        private final SessionAttributes session;
        private final Map<String, String> parameters;
        private final String source;

        public FakeEditContext(Viewer viewer, SessionAttributes session, Map<String, String> parameters, String source) {
            this.viewer = viewer == null ? Viewer.ANONYMOUS : viewer;
            this.session = session == null ? SessionAttributes.NONE : session;
            this.parameters = parameters == null ? Map.of() : new LinkedHashMap<>(parameters);
            this.source = source;
        }

        @Override
        public Viewer viewer() {
            return viewer;
        }

        @Override
        public String title() {
            return "测试页";
        }

        @Override
        public boolean newPage() {
            return false;
        }

        @Override
        public String content() {
            return "正文";
        }

        @Override
        public String summary() {
            return "";
        }

        @Override
        public boolean minor() {
            return false;
        }

        @Override
        public String action() {
            return ACTION_EDIT;
        }

        @Override
        public Long baseRevisionId() {
            return null;
        }

        @Override
        public Map<String, String> parameters() {
            return parameters;
        }

        @Override
        public SessionAttributes session() {
            return session;
        }

        @Override
        public String clientIp() {
            return "127.0.0.1";
        }

        @Override
        public String userAgent() {
            return "test";
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public boolean preview() {
            return false;
        }
    }
}
