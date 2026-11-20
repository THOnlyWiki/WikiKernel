package cc.thonly.wikikernel.plugin.news;

import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class NewsLogTypeTest {
    @Test void oldSaveActionsAndUnknownDetailsRemainReadable() {
        Locale previous = LocaleContextHolder.getLocale();
        try {
            LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
            var type = new NewsLogType(mock(NewsService.class));
            assertEquals("修改新闻草稿 · #1", type.detailText("save / revision 1"));
            assertEquals("新增新闻草稿 · #1", type.detailText("create / revision 1"));
            assertEquals("old custom detail", type.detailText("old custom detail"));
        } finally { LocaleContextHolder.setLocale(previous); }
    }
}
