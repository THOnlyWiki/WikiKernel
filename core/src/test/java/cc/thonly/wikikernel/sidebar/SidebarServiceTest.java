package cc.thonly.wikikernel.sidebar;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SidebarServiceTest {

    private final WikiPageService wikiPageService = mock(WikiPageService.class);
    private final SidebarService service = new SidebarService(wikiPageService, new SidebarProperties(), messageSource());

    @BeforeEach
    void useChinese() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void fallsBackToDefaultWhenPageMissing() {
        when(wikiPageService.getByTitle(any())).thenReturn(Optional.empty());

        List<SidebarSection> sections = service.sidebar();

        assertFalse(sections.isEmpty());
        assertEquals("导航", sections.get(0).getTitle());
        // 默认侧栏包含完整「工具」箱（仿 MediaWiki）
        assertEquals(2, sections.size());
        assertEquals("工具", sections.get(1).getTitle());
        assertTrue(sections.get(1).getItems().stream().anyMatch(item -> "可打印版".equals(item.getLabel())));
        assertTrue(sections.get(1).getItems().stream().anyMatch(item -> "固定链接".equals(item.getLabel())));
    }

    @Test
    void usesSidebarPageContent() {
        WikiPage page = new WikiPage();
        page.setId(1L);
        when(wikiPageService.getByTitle(any())).thenReturn(Optional.of(page));
        when(wikiPageService.getLatestContent(1L)).thenReturn(Optional.of("* 测试组\n** 首页"));

        List<SidebarSection> sections = service.sidebar();

        assertEquals(1, sections.size());
        assertEquals("测试组", sections.get(0).getTitle());
        assertEquals("/wiki/首页", sections.get(0).getItems().get(0).getHref());
    }

    @Test
    void substitutesTitlePlaceholders() {
        WikiPage page = new WikiPage();
        page.setId(1L);
        when(wikiPageService.getByTitle(any())).thenReturn(Optional.of(page));
        when(wikiPageService.getLatestContent(1L)).thenReturn(Optional.of(
                "* 工具\n"
                        + "** 特殊:链入页面?page=$title$|链入页面\n"
                        + "** $title$?action=info|页面信息\n"
                        + "** {{FULLPAGENAME}}?action=info|完整标题\n"
                        + "** {{PAGENAME}}?action=info|页面名\n"
                        + "说明：$title$ 是当前页面标题的占位\n"));

        List<SidebarSection> sections = service.sidebar("模板:信息框");

        assertEquals(1, sections.size());
        List<SidebarItem> items = sections.get(0).getItems();
        assertEquals(4, items.size(), "说明行不是条目");
        assertEquals("/wiki/特殊:链入页面?page=模板:信息框", items.get(0).getHref());
        assertEquals("/wiki/模板:信息框?action=info", items.get(1).getHref(), "$title$ = 完整标题");
        assertEquals("/wiki/模板:信息框?action=info", items.get(2).getHref(), "{{FULLPAGENAME}} = 完整标题");
        assertEquals("/wiki/信息框?action=info", items.get(3).getHref(), "{{PAGENAME}} = 去掉命名空间");
    }

    @Test
    void dropsTitleDependentItemsWithoutContext() {
        WikiPage page = new WikiPage();
        page.setId(1L);
        when(wikiPageService.getByTitle(any())).thenReturn(Optional.of(page));
        when(wikiPageService.getLatestContent(1L)).thenReturn(Optional.of(
                "* 工具\n** 特殊:上传文件|上传文件\n** $title$?action=info|页面信息\n"));

        List<SidebarSection> sections = service.sidebar();

        // 没有页面上下文：依赖 $title$ 的条目被去掉，其余保留
        assertEquals(1, sections.size());
        assertEquals(1, sections.get(0).getItems().size());
        assertEquals("上传文件", sections.get(0).getItems().get(0).getLabel());
    }

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }
}
