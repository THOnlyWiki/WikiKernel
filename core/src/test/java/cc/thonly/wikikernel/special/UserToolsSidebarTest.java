package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.sidebar.SidebarItem;
import cc.thonly.wikikernel.sidebar.SidebarSection;
import cc.thonly.wikikernel.sidebar.SidebarService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具箱合并逻辑（{@link UserToolsSidebar}）：把标准工具条目按「链接目标」识别后替换，
 * 与侧栏页面内容的语言无关（例如中文侧栏页面 + 英文界面不会出现两个工具分组或重复条目）。
 */
class UserToolsSidebarTest {

    private SidebarService sidebarService;
    private UserToolsSidebar userToolsSidebar;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        sidebarService = mock(SidebarService.class);
        when(sidebarService.sidebar(anyString())).thenReturn(List.of(
                new SidebarSection("导航", List.of(
                        new SidebarItem("首页", "/wiki/首页", false),
                        new SidebarItem("特殊页面", "/wiki/特殊:特殊页面", false))),
                new SidebarSection("工具", List.of(
                        new SidebarItem("链入页面", "/wiki/特殊:链入页面", false),
                        new SidebarItem("相关更改", "/wiki/特殊:相关更改", false),
                        new SidebarItem("上传文件", "/wiki/特殊:上传文件", false),
                        new SidebarItem("特殊页面", "/wiki/特殊:特殊页面", false),
                        new SidebarItem("页面信息", "/wiki/首页?action=info", false)))));
        userToolsSidebar = new UserToolsSidebar(sidebarService, messageSource());
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void replacesLegacyBareLinksInSameToolsSection() {
        List<SidebarSection> sections = userToolsSidebar.withPageTools("示例页面", 42L);
        assertEquals(2, sections.size(), "不应新建分组");
        SidebarSection nav = sections.get(0);
        SidebarSection tools = sections.get(1);
        assertEquals("导航", nav.getTitle());
        // 导航里的「特殊页面」原样保留
        assertTrue(nav.getItems().stream().anyMatch(item -> "特殊页面".equals(item.getLabel())));
        assertEquals("工具", tools.getTitle());
        // 七项标准工具，各恰好一次
        for (String label : new String[]{"链入页面", "相关更改", "上传文件", "特殊页面", "可打印版", "固定链接", "页面信息"}) {
            assertEquals(1, tools.getItems().stream().filter(item -> label.equals(item.getLabel())).count(),
                    label + " 应恰好一次");
        }
        // 旧的裸链接被替换为带参数的当前页面版本
        SidebarItem whatLinksHere = tools.getItems().stream()
                .filter(item -> "链入页面".equals(item.getLabel())).findFirst().orElseThrow();
        assertTrue(whatLinksHere.getHref().contains("?page="), "链入页面应指向当前页面");
        SidebarItem printable = tools.getItems().stream()
                .filter(item -> "可打印版".equals(item.getLabel())).findFirst().orElseThrow();
        assertTrue(printable.getHref().endsWith("?printable=yes"), "可打印版应带 ?printable=yes");
        SidebarItem permanent = tools.getItems().stream()
                .filter(item -> "固定链接".equals(item.getLabel())).findFirst().orElseThrow();
        assertTrue(permanent.getHref().endsWith("?oldid=42"), "固定链接应指向当前版本");
        SidebarItem pageInfo = tools.getItems().stream()
                .filter(item -> "页面信息".equals(item.getLabel())).findFirst().orElseThrow();
        assertTrue(pageInfo.getHref().endsWith("?action=info"), "页面信息应带 ?action=info（?action=info 也是受管标记）");
        // 旧版裸链接（无 ?page=）不应残留
        assertFalse(tools.getItems().stream().anyMatch(item ->
                "链入页面".equals(item.getLabel()) && !item.getHref().contains("?page=")), "裸链入链接应被替换");
    }

    @Test
    void specialPageContextKeepsOnlyStaticTools() {
        List<SidebarSection> sections = userToolsSidebar.withPageTools(null);
        SidebarSection tools = sections.get(1);
        assertEquals(2, tools.getItems().size());
        assertTrue(tools.getItems().stream().anyMatch(item -> "上传文件".equals(item.getLabel())));
        assertTrue(tools.getItems().stream().anyMatch(item -> "特殊页面".equals(item.getLabel())));
        assertFalse(tools.getItems().stream().anyMatch(item -> "链入页面".equals(item.getLabel())));
        assertFalse(tools.getItems().stream().anyMatch(item -> "可打印版".equals(item.getLabel())));
    }

    @Test
    void specialPageContextBuildsManagedItems() {
        // 特殊页：托管条目按特殊页上下文生成（可打印版 / 固定链接 / 页面信息都指回该特殊页）
        List<SidebarSection> sections = userToolsSidebar.withSpecialPageTools(
                "特殊:最近更改", "/wiki/特殊:最近更改");
        assertEquals(2, sections.size());
        SidebarSection tools = sections.get(1);
        assertEquals("工具", tools.getTitle());
        for (String label : new String[]{"链入页面", "相关更改", "上传文件", "特殊页面",
                "可打印版", "固定链接", "页面信息"}) {
            assertEquals(1, tools.getItems().stream().filter(item -> label.equals(item.getLabel())).count(),
                    label + " 应恰好一次");
        }
        SidebarItem printable = tools.getItems().stream()
                .filter(item -> "可打印版".equals(item.getLabel())).findFirst().orElseThrow();
        assertEquals("/wiki/特殊:最近更改?printable=yes", printable.getHref());
        SidebarItem permanent = tools.getItems().stream()
                .filter(item -> "固定链接".equals(item.getLabel())).findFirst().orElseThrow();
        assertEquals("/wiki/特殊:最近更改", permanent.getHref());
        SidebarItem info = tools.getItems().stream()
                .filter(item -> "页面信息".equals(item.getLabel())).findFirst().orElseThrow();
        assertEquals("/wiki/特殊:最近更改?action=info", info.getHref());
        SidebarItem links = tools.getItems().stream()
                .filter(item -> "链入页面".equals(item.getLabel())).findFirst().orElseThrow();
        assertTrue(links.getHref().contains("?page="), "链入页面应以特殊页为查询目标");
    }

    @Test
    void specialPageKeepsCustomItems() {
        // 自定义（非托管）条目原样保留
        when(sidebarService.sidebar(anyString())).thenReturn(List.of(
                new SidebarSection("导航", List.of(new SidebarItem("首页", "/wiki/首页", false))),
                new SidebarSection("工具", List.of(
                        new SidebarItem("链入页面", "/wiki/特殊:链入页面", false),
                        new SidebarItem("常用链接", "/wiki/常用", false)))));
        List<SidebarSection> sections = userToolsSidebar.withSpecialPageTools(
                "特殊:最近更改", "/wiki/特殊:最近更改");
        assertEquals(2, sections.size());
        SidebarSection tools = sections.get(1);
        assertTrue(tools.getItems().stream().anyMatch(item -> "常用链接".equals(item.getLabel())),
                "自定义条目应保留");
        assertEquals(1, tools.getItems().stream().filter(item -> "可打印版".equals(item.getLabel())).count(),
                "标准工具应补齐");
    }

    @Test
    void userToolsMergeIntoLegacySectionWithUserSpecificItems() {
        List<SidebarSection> sections = userToolsSidebar.withUserTools("星尘编辑", false, false, false, false);
        assertEquals(2, sections.size(), "不应新建分组");
        SidebarSection tools = sections.get(1);
        assertEquals("工具", tools.getTitle());
        for (String label : new String[]{"链入页面", "相关更改", "用户贡献", "日志", "上传文件", "特殊页面",
                "可打印版", "固定链接", "查看用户组", "页面信息"}) {
            assertEquals(1, tools.getItems().stream().filter(item -> label.equals(item.getLabel())).count(),
                    label + " 应恰好一次");
        }
        SidebarItem logItem = tools.getItems().stream()
                .filter(item -> "日志".equals(item.getLabel())).findFirst().orElseThrow();
        assertTrue(logItem.getHref().contains("?name="), "日志应带用户名");
    }

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }
}
