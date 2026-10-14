package cc.thonly.wikikernel.sidebar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarParserTest {

    @Test
    void parsesSectionsAndItems() {
        List<SidebarSection> sections = SidebarParser.parse("""
                * 导航
                ** 首页
                ** 特殊:全部页面|全部页面
                * 工具
                ** https://example.com|外部链接
                """);

        assertEquals(2, sections.size());

        SidebarSection navigation = sections.get(0);
        assertEquals("导航", navigation.getTitle());
        assertEquals(2, navigation.getItems().size());

        SidebarItem home = navigation.getItems().get(0);
        assertEquals("首页", home.getLabel());
        assertEquals("/wiki/首页", home.getHref());
        assertFalse(home.isExternal());

        SidebarItem allPages = navigation.getItems().get(1);
        assertEquals("全部页面", allPages.getLabel());
        assertEquals("/wiki/特殊:全部页面", allPages.getHref());

        SidebarItem external = sections.get(1).getItems().get(0);
        assertEquals("外部链接", external.getLabel());
        assertEquals("https://example.com", external.getHref());
        assertTrue(external.isExternal());
    }

    @Test
    void blankContentGivesNoSections() {
        assertTrue(SidebarParser.parse("").isEmpty());
        assertTrue(SidebarParser.parse(null).isEmpty());
    }
}
