package cc.thonly.wikikernel.plugin.news;

import cc.thonly.wikikernel.common.extension.*;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewsEmbeddingTest {
    @Test
    void ordinaryPagesAreUntouchedUntilTheyContainTheTag() {
        NewsService news = mock(NewsService.class);
        when(news.enabled()).thenReturn(true);
        var pages = new NewsPages(news);
        var display = mock(PageDisplayContext.class);
        when(display.title()).thenReturn("首页");
        when(display.content()).thenReturn("<div class=\"mini-block\">新闻 占位符</div>");
        assertNull(pages.beforeContent(display));
        assertNull(pages.renderBody(display));
        assertNull(pages.renderMissingPage(display));
        var head = mock(HeadContext.class);
        when(head.pageTitle()).thenReturn("首页");
        when(head.properties()).thenReturn(Map.of());
        assertTrue(pages.headItems(head).isEmpty());
        when(head.pageTitle()).thenReturn("其它页面");
        when(head.properties()).thenReturn(Map.of("news-used", "1"));
        assertEquals(2, pages.headItems(head).size());
        when(news.enabled()).thenReturn(false);
        assertTrue(pages.headItems(head).isEmpty());
    }

    @Test
    void explicitTagRegistersAssetsAndRendersOnlyItsOwnContainer() throws Exception {
        NewsService news = mock(NewsService.class);
        when(news.enabled()).thenReturn(true);
        NewsPlugin plugin = new NewsPlugin();
        for (var field : NewsPlugin.class.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.getType() == NewsService.class) field.set(plugin, news);
            if (field.getType() == NewsJobs.class) field.set(plugin, mock(NewsJobs.class));
        }
        PluginContext context = mock(PluginContext.class);
        plugin.onStart(context);
        var registered = ArgumentCaptor.forClass(ParserTag.class);
        verify(context).register(eq(ParserTag.class), registered.capture());
        ParserTag tag = registered.getValue();
        assertEquals("news-list", tag.name());
        var call = mock(ParserTagCall.class);
        var render = mock(WikiRenderContext.class);
        when(call.context()).thenReturn(render);
        when(call.attributes()).thenReturn(Map.of());
        String html = tag.render(call);
        verify(render).setProperty("news-used", "1");
        assertTrue(html.contains("class=\"news-list news-embed\""));
        assertFalse(html.contains("mini-block"));
        assertFalse(html.contains("<h2"));
        assertTrue(html.contains("data-news-limit=\"6\""));
        when(call.attributes()).thenReturn(Map.of("limit", "10"));
        assertTrue(tag.render(call).contains("data-news-limit=\"10\""));
        when(news.enabled()).thenReturn(false);
        assertEquals("", tag.render(call));
    }

    @Test void tagBoundsAndInvalidAttributesKeepThePageUsable() {
        var news = mock(NewsService.class); when(news.enabled()).thenReturn(true);
        var call = mock(ParserTagCall.class); when(call.context()).thenReturn(mock(WikiRenderContext.class));
        var tag = new NewsListTag(news);
        for (String invalid : java.util.List.of("", "0", "-1", "abc", "99999999999999999999", "1\" onclick=\"alert(1)")) {
            when(call.attributes()).thenReturn(Map.of("limit", invalid));
            String html = tag.render(call);
            assertTrue(html.contains("data-news-limit=\"6\""), invalid);
            assertFalse(html.contains("onclick"));
        }
        when(call.attributes()).thenReturn(Map.of("limit", " 1 "));
        assertTrue(tag.render(call).contains("data-news-limit=\"1\""));
        when(call.attributes()).thenReturn(Map.of("limit", "999"));
        assertTrue(tag.render(call).contains("data-news-limit=\"50\""));
    }
}
