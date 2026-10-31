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
        assertEquals("/wiki/%E9%A6%96%E9%A1%B5", home.getHref());
        assertFalse(home.isExternal());

        SidebarItem allPages = navigation.getItems().get(1);
        assertEquals("全部页面", allPages.getLabel());
        assertEquals("/wiki/%E7%89%B9%E6%AE%8A%3A%E5%85%A8%E9%83%A8%E9%A1%B5%E9%9D%A2",
                allPages.getHref());

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
