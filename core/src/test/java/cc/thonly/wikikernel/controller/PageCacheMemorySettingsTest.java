package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.*;
import cc.thonly.wikikernel.user.UserSession;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"wikikernel.database.file=:memory:", "wikikernel.cache.page.memory-size-mb=128"})
@AutoConfigureMockMvc
class PageCacheMemorySettingsTest {
    @Autowired MockMvc mvc;
    @Autowired PageCacheService cache;
    @Autowired SiteSettingsService settings;
    @Autowired PermissionService permissions;
    @Autowired WikiUserMapper users;

    @AfterEach
    void restoreCacheDefaults() {
        settings.set(PageCacheService.MEMORY_SIZE_MB_KEY, "");
        settings.set(PageCacheService.ENABLED_KEY, "false");
        settings.set(PageCacheService.BACKEND_KEY, "auto");
        cache.invalidateAll();
    }

    @Test
    void settingsPageOffersThreePresetsWithDefault128AndPersistsAllChoices() throws Exception {
        MockHttpSession admin = session(true);
        assertEquals(128, cache.memorySizeMb());
        var initial = Jsoup.parse(mvc.perform(get("/wiki/Special:AccessControl").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        var options = initial.select("select[name=cachePageMemorySizeMb] option");
        assertEquals(List.of("64", "128", "256"), options.eachAttr("value"));
        assertEquals("128", initial.selectFirst("select[name=cachePageMemorySizeMb] option[selected]").val());
        for (int size : List.of(64, 128, 256)) {
            mvc.perform(post("/api/site-settings").session(admin).param("cachePageMemorySizeMb", "" + size)
                            .param("cachePageEnabled", "1").param("cachePageBackend", "memory"))
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals(size, cache.memorySizeMb());
            assertEquals("memory", cache.backendName());
            cache.put("ManualMemory", "<p>cached</p>", 1L);
            assertTrue(cache.get("ManualMemory", 1L).isPresent());
            var page = Jsoup.parse(mvc.perform(get("/wiki/Special:AccessControl").session(admin))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertEquals("" + size, page.selectFirst("select[name=cachePageMemorySizeMb] option[selected]").val());
        }
    }

    @Test
    void invalidPresetIsRejectedBeforeAnySettingsAreChanged() throws Exception {
        MockHttpSession admin = session(true);
        settings.set(PageCacheService.ENABLED_KEY, "true");
        settings.set(PageCacheService.MEMORY_SIZE_MB_KEY, "128");
        String name = settings.get(SiteNameService.KEY, "");
        for (String value : List.of("", "garbage", "0", "-1", "32", "512", "128.0")) {
            mvc.perform(post("/api/site-settings").session(admin).param("cachePageMemorySizeMb", value)
                            .param("siteName", "ShouldNotChange").param("cachePageEnabled", "false"))
                    .andExpect(jsonPath("$.code").value(400));
            assertEquals(128, cache.memorySizeMb());
            assertTrue(cache.enabled());
            assertEquals(name, settings.get(SiteNameService.KEY, ""));
        }
        settings.set(PageCacheService.MEMORY_SIZE_MB_KEY, "512");
        assertEquals(128, cache.memorySizeMb());
    }

    @Test
    void cacheCapacityCanOnlyBeChangedByAdministrators() throws Exception {
        mvc.perform(post("/api/site-settings").param("cachePageMemorySizeMb", "256"))
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/api/site-settings").session(session(false)).param("cachePageMemorySizeMb", "256"))
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(128, cache.memorySizeMb());
    }

    private MockHttpSession session(boolean admin) {
        WikiUser user = new WikiUser();
        user.setUsername("MemorySettings" + System.nanoTime());
        user.setPassword("unused");
        users.insert(user);
        permissions.grantDefault(user.getId());
        if (admin) permissions.grant(user.getId(), PermissionService.GROUP_ADMIN, null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
