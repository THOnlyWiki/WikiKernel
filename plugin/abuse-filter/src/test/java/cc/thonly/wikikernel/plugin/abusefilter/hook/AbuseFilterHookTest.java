package cc.thonly.wikikernel.plugin.abusefilter.hook;

import cc.thonly.wikikernel.common.extension.ActionContext;
import cc.thonly.wikikernel.common.extension.ActionGuard;
import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.RegistrationContext;
import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterFakes;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三类评估钩子（编辑 / 动作 / 注册）的 BLOCK / WARN / ALLOW 行为、
 * 标签登记（{@code tag} 操作）、警告的二次确认，以及功能开关关闭时不评估。
 */
class AbuseFilterHookTest {

    private static final String EDITOR = "编辑者甲";

    private AbuseFilterFakes.Fixture fixture() {
        AbuseFilterFakes.Fixture fixture = new AbuseFilterFakes.Fixture();
        fixture.addUser(EDITOR, AbuseFilterSupport.GROUP_AUTOCONFIRMED);
        fixture.addPage("测试页面", "旧内容");
        return fixture;
    }

    private long disallowFilter(AbuseFilterFakes.Fixture fixture, String pattern) {
        return fixture.service().save(0, new AbuseFilterService.FilterForm("阻止说明", pattern,
                "disallow", "", true, false, false, 0, 0, "user", ""), 1L);
    }

    // ==================== 编辑钩子 ====================

    @Test
    void editGuardBlocksDisallowedEditsAndAllowsCleanOnes() {
        AbuseFilterFakes.Fixture fixture = fixture();
        disallowFilter(fixture, "new_wikitext contains \"spam\"");
        AbuseFilterEditGuard guard = new AbuseFilterEditGuard(fixture.service(), fixture.support(),
                fixture.pageStore());

        EditGuard.Outcome blocked = guard.beforeSave(editContext("测试页面", "spam http://example.com",
                EditContext.SOURCE_WEB, SessionAttributes.NONE));
        assertEquals(EditGuard.Outcome.Result.BLOCK, blocked.result());
        assertEquals("此操作已被滥用过滤器阻止：阻止说明", blocked.message());

        EditGuard.Outcome allowed = guard.beforeSave(editContext("测试页面", "正常内容",
                EditContext.SOURCE_WEB, SessionAttributes.NONE));
        assertEquals(EditGuard.Outcome.Result.ALLOW, allowed.result());
    }

    @Test
    void editGuardWarnsAndRequiresSecondConfirmationOnTheWeb() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.service().save(0, new AbuseFilterService.FilterForm("提醒说明", "page_title == \"测试页面\"",
                "warn", "", true, false, false, 0, 0, "user", ""), 1L);
        AbuseFilterEditGuard guard = new AbuseFilterEditGuard(fixture.service(), fixture.support(),
                fixture.pageStore());
        SessionAttributes session = session();

        EditGuard.Outcome first = guard.beforeSave(editContext("测试页面", "新内容",
                EditContext.SOURCE_WEB, session));
        assertEquals(EditGuard.Outcome.Result.BLOCK, first.result(), "网页第一次提交要求再次确认");
        assertTrue(first.message().contains("提醒说明"));
        assertFalse(fixture.service().logs(true, 10).isEmpty(), "命中会写日志");

        EditGuard.Outcome second = guard.beforeSave(editContext("测试页面", "新内容",
                EditContext.SOURCE_WEB, session));
        assertEquals(EditGuard.Outcome.Result.WARN, second.result(), "再次提交即放行并提示");
        assertTrue(second.message().contains("提醒说明"));
    }

    @Test
    void editGuardOnlyWarnsForApiAndAiSources() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.service().save(0, new AbuseFilterService.FilterForm("提醒说明", "true",
                "warn", "", true, false, false, 0, 0, "user", ""), 1L);
        AbuseFilterEditGuard guard = new AbuseFilterEditGuard(fixture.service(), fixture.support(),
                fixture.pageStore());

        for (String source : List.of(EditContext.SOURCE_API, EditContext.SOURCE_AI)) {
            EditGuard.Outcome outcome = guard.beforeSave(editContext("测试页面", "新内容", source, session()));
            assertEquals(EditGuard.Outcome.Result.WARN, outcome.result(),
                    "API / AI 不要求二次确认（与迁移前一致）");
        }
    }

    @Test
    void editGuardRegistersChangeTagsFromTagAction() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.service().save(0, new AbuseFilterService.FilterForm("标签过滤器", "true",
                "tag", "自定义标签", true, false, false, 0, 0, "user", ""), 1L);
        AbuseFilterEditGuard guard = new AbuseFilterEditGuard(fixture.service(), fixture.support(),
                fixture.pageStore());

        MutableEditContext context = new MutableEditContext("测试页面", "新内容", EditContext.SOURCE_WEB,
                session(), Viewer.of(1L, EDITOR, Set.of()));
        EditGuard.Outcome outcome = guard.beforeSave(context);
        assertEquals(EditGuard.Outcome.Result.ALLOW, outcome.result());
        assertEquals(List.of("自定义标签"), context.tags());
    }

    @Test
    void editGuardUsesPageIdAndOldTextFromThePageStore() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.service().save(0, new AbuseFilterService.FilterForm("旧内容过滤器",
                "page_id == 1 & old_wikitext == \"旧内容\" & action == \"edit\"",
                "disallow", "", true, false, false, 0, 0, "user", ""), 1L);
        AbuseFilterEditGuard guard = new AbuseFilterEditGuard(fixture.service(), fixture.support(),
                fixture.pageStore());
        assertEquals(EditGuard.Outcome.Result.BLOCK,
                guard.beforeSave(editContext("测试页面", "新内容", EditContext.SOURCE_WEB,
                        SessionAttributes.NONE)).result());
    }

    // ==================== 动作钩子 ====================

    @Test
    void actionGuardBlocksMoveWarnAllowsUploadAndIgnoresUnknownActions() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.service().save(0, new AbuseFilterService.FilterForm("禁止移动",
                "action == \"move\" & moved_to == \"禁地\"", "disallow", "",
                true, false, false, 0, 0, "user", ""), 1L);
        fixture.service().save(0, new AbuseFilterService.FilterForm("上传提醒",
                "action == \"upload\" & file_size > 1000", "warn", "",
                true, false, false, 0, 0, "user", ""), 1L);
        AbuseFilterActionGuard guard = new AbuseFilterActionGuard(fixture.service(), fixture.support());

        ActionGuard.Outcome blocked = guard.beforeAction(action("move", "源页面", "禁地",
                Map.of("from", "源页面", "to", "禁地")));
        assertEquals(ActionGuard.Outcome.Result.BLOCK, blocked.result());
        assertEquals("此操作已被滥用过滤器阻止：禁止移动", blocked.message());

        assertEquals(ActionGuard.Outcome.Result.ALLOW, guard.beforeAction(action("move", "源页面", "自由地",
                Map.of("from", "源页面", "to", "自由地"))).result());

        ActionGuard.Outcome warned = guard.beforeAction(action("upload", "文件:大图.png", "",
                Map.of("name", "大图.png", "size", "2048", "contentType", "image/png")));
        assertEquals(ActionGuard.Outcome.Result.WARN, warned.result());
        assertTrue(warned.message().contains("上传提醒"));

        assertEquals(ActionGuard.Outcome.Result.ALLOW,
                guard.beforeAction(action("purge", "页面", "", Map.of())).result(),
                "未接线的动作直接放行");

        // 上传 / 删除 / 封禁命中的日志动作名与数据
        assertEquals("upload", fixture.service().logs(true, 10).get(0).getAction());
        assertEquals("大图.png", fixture.service().logs(true, 10).get(0).getPageTitle());
    }

    @Test
    void actionGuardCoversDeleteProtectAndBlockVariables() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.service().save(0, new AbuseFilterService.FilterForm("删除禁止",
                "action == \"delete\" & page_title == \"要删的图.png\"", "disallow", "",
                true, false, false, 0, 0, "user", ""), 1L);
        fixture.service().save(0, new AbuseFilterService.FilterForm("封禁禁止",
                "action == \"block\" & page_title == \"坏用户\"", "disallow", "",
                true, false, false, 0, 0, "user", ""), 1L);
        AbuseFilterActionGuard guard = new AbuseFilterActionGuard(fixture.service(), fixture.support());

        assertEquals(ActionGuard.Outcome.Result.BLOCK, guard.beforeAction(action("delete",
                "文件:要删的图.png", "", Map.of("name", "要删的图.png"))).result());
        assertEquals(ActionGuard.Outcome.Result.BLOCK, guard.beforeAction(action("block",
                "坏用户", "破坏", Map.of("target", "坏用户", "reason", "破坏"))).result());
        assertEquals(ActionGuard.Outcome.Result.ALLOW, guard.beforeAction(action("protect",
                "页面", "", Map.of("title", "页面", "protected", "true"))).result());
    }

    // ==================== 注册钩子 ====================

    @Test
    void registrationGuardBlocksDisallowedNamesAndAllowsOthers() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.service().save(0, new AbuseFilterService.FilterForm("禁止广告账号",
                "accountname contains \"spam\"", "disallow", "",
                true, false, false, 0, 0, "user", ""), 1L);
        AbuseFilterRegistrationGuard guard = new AbuseFilterRegistrationGuard(fixture.service(),
                fixture.support());

        RegistrationGuard.Outcome blocked = guard.beforeRegister(registration("spamuser123"));
        assertEquals(RegistrationGuard.Outcome.Result.BLOCK, blocked.result());
        assertEquals("此操作已被滥用过滤器阻止：禁止广告账号", blocked.message());
        assertEquals("createaccount", fixture.service().logs(true, 10).get(0).getAction());

        assertEquals(RegistrationGuard.Outcome.Result.ALLOW, guard.beforeRegister(registration("好人甲")).result());
    }

    @Test
    void registrationWarningsDoNotBlockTheSignUp() {
        AbuseFilterFakes.Fixture fixture = fixture();
        fixture.service().save(0, new AbuseFilterService.FilterForm("注册提醒", "action == \"createaccount\"",
                "warn", "", true, false, false, 0, 0, "user", ""), 1L);
        AbuseFilterRegistrationGuard guard = new AbuseFilterRegistrationGuard(fixture.service(),
                fixture.support());
        assertEquals(RegistrationGuard.Outcome.Result.ALLOW, guard.beforeRegister(registration("新人甲")).result());
        assertEquals("warn", fixture.service().logs(true, 10).get(0).getActionsTaken());
    }

    // ==================== 开关关闭 ====================

    @Test
    void disabledFeatureNeverBlocksAnyHook() {
        AbuseFilterFakes.Fixture fixture = fixture();
        disallowFilter(fixture, "true");
        fixture.enabled(false);

        AbuseFilterEditGuard editGuard = new AbuseFilterEditGuard(fixture.service(), fixture.support(),
                fixture.pageStore());
        assertEquals(EditGuard.Outcome.Result.ALLOW, editGuard.beforeSave(editContext("测试页面", "新内容",
                EditContext.SOURCE_WEB, SessionAttributes.NONE)).result());

        AbuseFilterActionGuard actionGuard = new AbuseFilterActionGuard(fixture.service(), fixture.support());
        assertEquals(ActionGuard.Outcome.Result.ALLOW,
                actionGuard.beforeAction(action("move", "源页面", "目标页面", Map.of())).result());

        AbuseFilterRegistrationGuard registrationGuard =
                new AbuseFilterRegistrationGuard(fixture.service(), fixture.support());
        assertEquals(RegistrationGuard.Outcome.Result.ALLOW,
                registrationGuard.beforeRegister(registration("新人甲")).result());

        assertTrue(fixture.service().logs(true, 10).isEmpty(), "关闭时不写滥用日志");
    }

    // ==================== 替身 ====================

    private static EditContext editContext(String title, String content, String source,
                                           SessionAttributes session) {
        return new MutableEditContext(title, content, source, session,
                Viewer.of(1L, EDITOR, Set.of()));
    }

    private static ActionContext action(String action, String target, String summary,
                                        Map<String, String> data) {
        return new ActionContext() {
            @Override
            public Viewer viewer() {
                return Viewer.of(1L, EDITOR, Set.of());
            }

            @Override
            public String action() {
                return action;
            }

            @Override
            public String target() {
                return target;
            }

            @Override
            public String summary() {
                return summary;
            }

            @Override
            public Map<String, String> data() {
                return data;
            }

            @Override
            public Map<String, String> parameters() {
                return Map.of();
            }

            @Override
            public SessionAttributes session() {
                return SessionAttributes.NONE;
            }

            @Override
            public String clientIp() {
                return "203.0.113.7";
            }

            @Override
            public String userAgent() {
                return "JUnit";
            }

            @Override
            public String source() {
                return SOURCE_WEB;
            }
        };
    }

    private static RegistrationContext registration(String username) {
        return new RegistrationContext() {
            @Override
            public String username() {
                return username;
            }

            @Override
            public String email() {
                return "";
            }

            @Override
            public Map<String, String> parameters() {
                return Map.of();
            }

            @Override
            public SessionAttributes session() {
                return SessionAttributes.NONE;
            }

            @Override
            public String clientIp() {
                return "203.0.113.7";
            }

            @Override
            public String source() {
                return SOURCE_WEB;
            }
        };
    }

    private static SessionAttributes session() {
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

    /** 可读标签的 {@link EditContext} 替身（{@code addTag} 会被记录下来）。 */
    private static final class MutableEditContext implements EditContext {

        private final String title;
        private final String content;
        private final String source;
        private final SessionAttributes session;
        private final Viewer viewer;
        private final Map<String, String> tags = new LinkedHashMap<>();

        private MutableEditContext(String title, String content, String source,
                                   SessionAttributes session, Viewer viewer) {
            this.title = title;
            this.content = content;
            this.source = source;
            this.session = session;
            this.viewer = viewer;
        }

        @Override
        public Viewer viewer() {
            return viewer;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public boolean newPage() {
            return false;
        }

        @Override
        public String content() {
            return content;
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
            return Map.of();
        }

        @Override
        public SessionAttributes session() {
            return session;
        }

        @Override
        public String clientIp() {
            return "203.0.113.7";
        }

        @Override
        public String userAgent() {
            return "JUnit";
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public List<String> tags() {
            return List.copyOf(tags.keySet());
        }

        @Override
        public void addTag(String tag) {
            tags.put(tag, tag);
        }
    }
}
