package cc.thonly.wikikernel.plugin.gadgets.service;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.gadgets.hook.GadgetsHeadProvider;
import cc.thonly.wikikernel.plugin.gadgets.hook.GadgetsPreferenceSection;
import cc.thonly.wikikernel.plugin.gadgets.model.GadgetDefinition;
import cc.thonly.wikikernel.plugin.gadgets.web.GadgetsSpecialPage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gadgets 插件：定义页解析、默认 / 显式偏好 / {@code rights} 过滤、资源解析、
 * 前端注入（脚本 / 样式 / 依赖）与参数设置分区。
 */
class GadgetServiceTest {

    private static final String DEFINITION = """
            == Gadgets ==

            文档示例（在 pre 里，不算定义）：
            <pre>
            * QuickEdit | 快速编辑工具 | Gadget-QuickEdit.js | Gadget-QuickEdit.css | default
            * DarkMode | 深色模式 | Gadget-DarkMode.js | default
            </pre>

            * QuickEdit | 快速编辑工具 | Gadget-QuickEdit.js | Gadget-QuickEdit.css | default
            * DarkMode | 深色模式 | Gadget-DarkMode.js
            * AdminTool | 仅在组内可见 | Gadget-AdminTool.js | rights=管理员 | default
            * Internal | 隐藏：不出现在个人设置里 | Gadget-Internal.js | hidden
            * Deps | 依赖模块 | Gadget-Deps.js | dependencies=oojs-ui-core,mediawiki.api
            """;

    @Test
    void parsesDefinitionPageSkippingPreBlocks() {
        List<GadgetDefinition> definitions = GadgetService.parse(DEFINITION, true);
        assertEquals(List.of("QuickEdit", "DarkMode", "AdminTool", "Internal", "Deps"),
                definitions.stream().map(GadgetDefinition::name).toList());

        GadgetDefinition quick = definitions.get(0);
        assertEquals("快速编辑工具", quick.description());
        assertEquals(List.of("Gadget-QuickEdit.js"), quick.scripts());
        assertEquals(List.of("Gadget-QuickEdit.css"), quick.styles());
        assertTrue(quick.defaultEnabled());

        GadgetDefinition admin = definitions.get(2);
        assertEquals(List.of("管理员"), admin.rights());
        assertTrue(admin.defaultEnabled());
        assertTrue(definitions.get(3).hidden());
        assertEquals(List.of("oojs-ui-core", "mediawiki.api"), definitions.get(4).dependencies());
    }

    @Test
    void typeScriptAssetsOnlyCountWhenCompilerEnabled() {
        String content = "* TsDemo | TS 演示 | TsDemo.ts | default\n";
        assertTrue(GadgetService.parse(content, true).get(0).scripts().contains("TsDemo.ts"));
        assertTrue(GadgetService.parse(content, false).get(0).scripts().isEmpty());
    }

    @Test
    void enabledGadgetsFollowDefaultsRightsAndExplicitPreferences() {
        Fixture fixture = new Fixture();
        GadgetService gadgets = fixture.service(Set.of(), "");

        // 匿名：默认启用的（QuickEdit / AdminTool 需要 rights 不满足 → 只剩 QuickEdit）
        assertEquals(Set.of("QuickEdit"), gadgets.enabledFor(Set.of(), ""));

        // 管理员组：AdminTool 也在
        assertEquals(Set.of("QuickEdit", "AdminTool"), gadgets.enabledFor(Set.of("管理员"), ""));

        // 显式勾选：覆盖默认值
        assertEquals(Set.of("DarkMode"), gadgets.enabledFor(Set.of(), "DarkMode"));
        assertEquals(Set.of("QuickEdit", "DarkMode"), gadgets.enabledFor(Set.of(), "QuickEdit,DarkMode"));

        // "!"：明确全部关闭（含默认启用的）
        assertEquals(Set.of(), gadgets.enabledFor(Set.of("管理员"), "!"));
    }

    @Test
    void hiddenGadgetsAreNotSelectableNorLoadable() {
        Fixture fixture = new Fixture();
        GadgetService gadgets = fixture.service(Set.of(), "Internal");
        assertTrue(gadgets.visibleDefinitions().stream().noneMatch(GadgetDefinition::hidden));
        // 与迁移前的 core 语义一致：hidden 的 Gadget 既不列出、也不由个人偏好启用
        assertEquals(Set.of(), gadgets.enabledFor(Set.of(), "Internal"));
    }

    @Test
    void resolvesAssetTitlesAndSkipsMissingPages() {
        Fixture fixture = new Fixture();
        fixture.pages.put("小部件:Gadget-QuickEdit.js", "console.log(1);");
        fixture.pages.put("Gadget:Gadget-QuickEdit.css", ".x{}");
        GadgetService gadgets = fixture.service(Set.of(), "");

        assertEquals(List.of("小部件:Gadget-QuickEdit.js"), gadgets.scriptTitles());
        assertEquals(List.of("Gadget:Gadget-QuickEdit.css"), gadgets.styleTitles());
    }

    @Test
    void headProviderInjectsScriptsStylesAndDependencies() {
        Fixture fixture = new Fixture();
        fixture.pages.put("小部件:Gadget-QuickEdit.js", "console.log(1);");
        fixture.pages.put("Gadget:Gadget-QuickEdit.css", ".x{}");
        fixture.pages.put("小部件:Gadget-Deps.js", "console.log(2);");
        GadgetService gadgets = fixture.service(Set.of("用户"), "QuickEdit,Deps", true, true);

        List<String> items = new GadgetsHeadProvider(gadgets).headItems(head());
        String joined = String.join("\n", items);
        assertTrue(joined.contains("/wiki/Gadget:Gadget-QuickEdit.css?action=raw&ctype=text/css"), joined);
        assertTrue(joined.contains("/wiki/%E5%B0%8F%E9%83%A8%E4%BB%B6:Gadget-QuickEdit.js"
                + "?action=raw&ctype=text/javascript"), joined);
        assertTrue(joined.contains("gadgetDependencies"), joined);
        assertTrue(joined.contains("oojs-ui-core"), joined);
    }

    @Test
    void disabledFeatureInjectsNothing() {
        Fixture fixture = new Fixture();
        fixture.pages.put("小部件:Gadget-QuickEdit.js", "x");
        GadgetService gadgets = fixture.service(Set.of(), "", false);
        assertEquals(Set.of(), gadgets.enabledFor(Set.of(), ""));
        assertEquals(List.of(), new GadgetsHeadProvider(gadgets).headItems(head()));
    }

    @Test
    void preferenceSectionListsVisibleGadgets() {
        Fixture fixture = new Fixture();
        GadgetService gadgets = fixture.service(Set.of("用户"), "DarkMode", true, true);
        String html = new GadgetsPreferenceSection(gadgets).html(preferenceContext());

        assertTrue(html.contains("id=\"gadgets-preferences-form\""), html);
        assertTrue(html.contains("value=\"QuickEdit\""), html);
        assertTrue(html.contains("value=\"DarkMode\" checked"), html);
        assertFalse(html.contains("value=\"Internal\""), "隐藏的 Gadget 不出现在设置里");
        assertTrue(html.contains("/plugins/gadgets/gadgets.js"), html);
    }

    @Test
    void specialPageListsDefinitions() {
        Fixture fixture = new Fixture();
        GadgetService gadgets = fixture.service(Set.of(), "");
        Map<String, Object> model = new LinkedHashMap<>();
        new GadgetsSpecialPage(gadgets, fixture.currentUser(Set.of("小部件管理员")))
                .populate(new SpecialPageRequest("Gadgets", Map.of()), model);

        assertEquals(Boolean.TRUE, model.get("enabled"));
        assertEquals(Boolean.TRUE, model.get("canManage"));
        assertEquals("/wiki/WikiKernel:Gadgets-definition", model.get("definitionUrl"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.get("gadgets");
        assertEquals(4, rows.size(), "隐藏的 Gadget 不出现在目录页");
    }

    // ==================== 测试替身 ====================

    /** 内存页面桥 + 偏好 + 当前用户。 */
    private static final class Fixture {

        private final Map<String, String> pages = new LinkedHashMap<>();
        private String gadgetsPreference = "";

        Fixture() {
            pages.put(GadgetService.DEFINITION_PAGE, DEFINITION);
        }

        GadgetService service(Set<String> groups, String preference) {
            return service(groups, preference, true, false);
        }

        GadgetService service(Set<String> groups, String preference, boolean enabled) {
            return service(groups, preference, enabled, false);
        }

        GadgetService service(Set<String> groups, String preference, boolean enabled, boolean loggedIn) {
            this.gadgetsPreference = preference;
            SiteConfig config = new SiteConfig() {
                @Override
                public String get(String key, String defaultValue) {
                    return defaultValue;
                }

                @Override
                public boolean getBoolean(String key, boolean defaultValue) {
                    if (GadgetService.ENABLED_KEY.equals(key)) {
                        return enabled;
                    }
                    return defaultValue;
                }
            };
            UserSettings userSettings = new UserSettings() {
                @Override
                public String get(long userId, String key, String defaultValue) {
                    return "gadgets".equals(key) ? gadgetsPreference : defaultValue;
                }

                @Override
                public void set(long userId, String key, String value) {
                    if ("gadgets".equals(key)) {
                        gadgetsPreference = value;
                    }
                }

                @Override
                public Map<String, String> list(long userId, String prefix) {
                    return Map.of();
                }
            };
            return new GadgetService(store(), config, userSettings, currentUser(groups, loggedIn));
        }

        CurrentUser currentUser(Set<String> groups) {
            return currentUser(groups, !groups.isEmpty());
        }

        CurrentUser currentUser(Set<String> groups, boolean loggedIn) {
            return () -> new Viewer(loggedIn ? 7 : 0, loggedIn ? "测试用户" : "",
                    !loggedIn, groups, false);
        }

        private WikiPageStore store() {
            Map<String, Long> ids = new HashMap<>();
            return new WikiPageStore() {
                @Override
                public Optional<PageSnapshot> byId(long pageId) {
                    return Optional.empty();
                }

                @Override
                public Optional<PageSnapshot> byTitle(String title) {
                    return contentTitle(title).map(found -> snapshot(found, ids));
                }

                @Override
                public Optional<PageSnapshot> visibleByTitle(String title) {
                    return byTitle(title);
                }

                @Override
                public Optional<String> content(long pageId) {
                    for (String title : pages.keySet()) {
                        if (ids.getOrDefault(title, -1L) == pageId) {
                            return Optional.of(pages.get(title));
                        }
                    }
                    return Optional.empty();
                }

                @Override
                public Optional<Long> latestRevisionId(long pageId) {
                    return Optional.empty();
                }

                @Override
                public List<PageSnapshot> contentPages() {
                    return List.of();
                }
            };
        }

        private Optional<String> contentTitle(String title) {
            if (pages.containsKey(title)) {
                return Optional.of(title);
            }
            return pages.keySet().stream().filter(key -> key.equalsIgnoreCase(title)).findFirst();
        }

        private PageSnapshot snapshot(String title, Map<String, Long> ids) {
            long id = ids.computeIfAbsent(title, key -> (long) (ids.size() + 1));
            return new PageSnapshot(id, title, false, false, pages.get(title).length(), null);
        }
    }

    private static HeadContext head() {
        return new HeadContext() {
            @Override
            public long pageId() {
                return 1;
            }

            @Override
            public String pageTitle() {
                return "首页";
            }

            @Override
            public String path() {
                return "/wiki/首页";
            }

            @Override
            public Map<String, String> query() {
                return Map.of();
            }

            @Override
            public Map<String, String> properties() {
                return Map.of();
            }

            @Override
            public String siteName() {
                return "WikiKernel";
            }

            @Override
            public String baseUrl() {
                return "http://localhost:18080";
            }

            @Override
            public String fileUrl(String fileName) {
                return "";
            }

            @Override
            public int fileWidth(String fileName) {
                return 0;
            }

            @Override
            public int fileHeight(String fileName) {
                return 0;
            }
        };
    }

    private static PreferenceContext preferenceContext() {
        return new PreferenceContext() {
            @Override
            public Viewer viewer() {
                return new Viewer(7, "测试用户", false, Set.of(), false);
            }

            @Override
            public String siteName() {
                return "WikiKernel";
            }

            @Override
            public String baseUrl() {
                return "http://localhost:18080";
            }

            @Override
            public String message(String key, String fallback) {
                return fallback;
            }
        };
    }
}
