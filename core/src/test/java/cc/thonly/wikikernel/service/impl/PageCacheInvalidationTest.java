package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.service.PageLinkStore;
import cc.thonly.wikikernel.config.PageCacheProperties;
import cc.thonly.wikikernel.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PageCacheInvalidationTest {
    @Test
    @SuppressWarnings("unchecked")
    void dynamicParserOutputIsRenderedAgainInsteadOfBeingCached() {
        SiteSettingsService settings = mock(SiteSettingsService.class);
        when(settings.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        PageCacheProperties properties = new PageCacheProperties();
        properties.setEnabled(true); properties.setBackend("memory");
        AccessControlService access = mock(AccessControlService.class);
        when(access.allRules()).thenReturn(List.of());
        PageBodyRenderer renderer = mock(PageBodyRenderer.class);
        when(renderer.render(eq("DynamicAvatar"), anyString(), eq(1L))).thenReturn(
                new PageBody(1L, "dynamic", null, List.of(), List.of(), java.util.Map.of("cache-expiry", "0")));
        var service = new PageCacheServiceImpl(settings, properties, renderer, mock(WikiPageService.class),
                mock(PageLinkStore.class), mock(Environment.class), mock(ObjectProvider.class), mock(ObjectProvider.class), access);
        var page = new cc.thonly.wikikernel.common.data.WikiPage();
        page.setTitle("DynamicAvatar"); page.setLatestRevisionId(1L);
        service.body(page, "{{#avatar:甲}}"); service.body(page, "{{#avatar:甲}}");
        verify(renderer, times(2)).render("DynamicAvatar", "{{#avatar:甲}}", 1L);
        org.junit.jupiter.api.Assertions.assertTrue(service.get("DynamicAvatar", 1L).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheFilledAfterMissIsNotDeletedByDelayedPageEvent() {
        SiteSettingsService settings = mock(SiteSettingsService.class);
        when(settings.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        PageCacheProperties properties = new PageCacheProperties();
        properties.setEnabled(true);
        properties.setBackend("memory");
        AccessControlService access = mock(AccessControlService.class);
        when(access.allRules()).thenReturn(List.of());
        PageCacheServiceImpl service = new PageCacheServiceImpl(settings, properties,
                mock(PageBodyRenderer.class), mock(WikiPageService.class), mock(PageLinkStore.class),
                mock(Environment.class), mock(ObjectProvider.class), mock(ObjectProvider.class), access);
        MemoryPageCacheBackend backend = spy(new MemoryPageCacheBackend(() -> 20));
        ReflectionTestUtils.setField(service, "memoryBackend", backend);
        doAnswer(call -> {
            // The invalidator observed a miss, while a concurrent renderer then populated the entry.
            backend.put(call.getArgument(0), PageBody.of(5L, "new body"), Duration.ofSeconds(60));
            return Optional.empty();
        }).when(backend).get(anyString());
        service.invalidate("DelayedEvent", 5L);
        doCallRealMethod().when(backend).get(anyString());
        assertEquals("new body", service.get("DelayedEvent", 5L).orElseThrow().html());
    }
}
