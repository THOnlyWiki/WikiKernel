package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.i18n.PluginMessageSource;
import cc.thonly.wikikernel.service.*;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"wikikernel.database.file=:memory:", "wikikernel.cache.page.enabled=true", "wikikernel.cache.page.backend=memory"})
@AutoConfigureMockMvc
class AllMessagesTest {
    @Autowired MockMvc mvc;
    @Autowired WikiPageService pages;
    @Autowired PluginMessageSource messages;
    @Autowired WikiUserMapper users;
    @Autowired PermissionService permissions;
    @Autowired AccessControlService access;
    @Autowired PageCacheService cache;
    @Autowired cc.thonly.wikikernel.i18n.SystemMessages system;

    @AfterEach void clean() {
        for (String key : List.of("Common.cancel", "Common.close", "Common.search", "Common.cancel/en", "Sitenotice", "Sitenotice/en")) {
            for (String namespace : List.of("WikiKernel:", "MediaWiki:")) {
                String title = namespace + key;
                access.findRule(cc.thonly.wikikernel.common.access.AccessAction.READ, title).ifPresent(r -> access.deleteRule(r.getId()));
                pages.deletePage(null, title);
            }
        }
    }

    private MockHttpSession session(boolean admin) {
        WikiUser user = new WikiUser(); user.setUsername("AllMessages" + System.nanoTime()); user.setPassword("test"); user.setEmail(""); users.insert(user);
        permissions.grantDefault(user.getId());
        if (admin) permissions.grant(user.getId(), PermissionService.GROUP_ADMIN, null);
        var session = new MockHttpSession(); session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy()); return session;
    }

    @Test void aliasesFiltersAndRealInstalledDefaults() throws Exception {
        for (String title : List.of("特殊:所有消息", "Special:AllMessages", "特殊:系统消息")) {
            mvc.perform(get("/wiki/{title}", title).param("prefix", "common.").param("limit", "20"))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("mw-allmessagestable")))
                    .andExpect(content().string(containsString("common.cancel")))
                    .andExpect(content().string(not(containsString("abusefilter-edit-action-showcaptcha"))));
        }
        mvc.perform(get("/wiki/特殊:所有消息").param("prefix", "no-such-message-"))
                .andExpect(content().string(containsString("没有符合条件的消息")));
        mvc.perform(get("/wiki/特殊:所有消息").param("lang", "en").param("prefix", "common.cancel"))
                .andExpect(content().string(containsString("Cancel")));
    }

    @Test void normalEditorPermissionsAndOverridesAreEffective() throws Exception {
        mvc.perform(post("/wiki/WikiKernel:Common.cancel").with(csrf()).session(session(false)).param("content", "越权修改"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/wiki/WikiKernel:Common.cancel").with(csrf()).session(session(true)).param("content", "CUSTOM_CANCEL_TOKEN"))
                .andExpect(status().is3xxRedirection());
        assertEquals("CUSTOM_CANCEL_TOKEN", messages.getMessage("common.cancel", null, Locale.SIMPLIFIED_CHINESE));
        mvc.perform(get("/wiki/特殊:所有消息").param("prefix", "common.cancel").param("filter", "modified"))
                .andExpect(content().string(containsString("CUSTOM_CANCEL_TOKEN")))
                .andExpect(content().string(containsString("am_actual")));
        mvc.perform(get("/wiki/特殊:所有消息").param("prefix", "common.cancel").param("filter", "unmodified"))
                .andExpect(content().string(not(containsString("id=\"mw-allmessagestable\""))));
    }

    @Test void importsLanguageOverridesAndDeletionPreserveDefaults() {
        String original = messages.defaults(Locale.SIMPLIFIED_CHINESE).get("common.cancel");
        pages.editPage(null, "MediaWiki:Common.cancel", "IMPORTED_CANCEL");
        assertEquals("IMPORTED_CANCEL", messages.getMessage("common.cancel", null, Locale.SIMPLIFIED_CHINESE));
        pages.editPage(null, "WikiKernel:Common.cancel", "KERNEL_CANCEL");
        assertEquals("KERNEL_CANCEL", messages.getMessage("common.cancel", null, Locale.SIMPLIFIED_CHINESE));
        assertEquals("Cancel", messages.getMessage("common.cancel", null, Locale.ENGLISH));
        pages.editPage(null, "WikiKernel:Common.cancel/en", "English custom cancel");
        assertEquals("English custom cancel", messages.getMessage("common.cancel", null, Locale.ENGLISH));
        assertEquals(original, messages.defaults(Locale.SIMPLIFIED_CHINESE).get("common.cancel"));
        pages.deletePage(null, "WikiKernel:Common.cancel");
        assertEquals("IMPORTED_CANCEL", messages.getMessage("common.cancel", null, Locale.SIMPLIFIED_CHINESE));
        pages.deletePage(null, "MediaWiki:Common.cancel");
        assertEquals(original, messages.getMessage("common.cancel", null, Locale.SIMPLIFIED_CHINESE));
    }

    @Test void protectedMessagesDoNotLeakThroughCatalogueOrRuntime() throws Exception {
        pages.editPage(null, "WikiKernel:Common.close", "PRIVATE_INTERFACE_TOKEN");
        access.saveRule("WikiKernel:Common.close", cc.thonly.wikikernel.common.access.AccessAction.READ, PermissionService.GROUP_ADMIN);
        mvc.perform(get("/wiki/特殊:所有消息").param("prefix", "common.close"))
                .andExpect(content().string(not(containsString("PRIVATE_INTERFACE_TOKEN"))));
        mvc.perform(get("/wiki/特殊:所有消息").param("prefix", "common.close").session(session(true)))
                .andExpect(content().string(containsString("PRIVATE_INTERFACE_TOKEN")));
        assertNotEquals("PRIVATE_INTERFACE_TOKEN", messages.getMessage("common.close", null, Locale.SIMPLIFIED_CHINESE));
    }

    @Test void rawTextIsEscapedAndDefaultEditorIsPreloaded() throws Exception {
        pages.editPage(null, "WikiKernel:Common.cancel", "<script>window.bad=true</script>");
        String html = mvc.perform(get("/wiki/特殊:所有消息").param("prefix", "common.cancel"))
                .andExpect(content().string(containsString("&lt;script&gt;window.bad=true&lt;/script&gt;")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(org.jsoup.Jsoup.parse(html).select("#mw-allmessagestable script").isEmpty());
        mvc.perform(get("/wiki/WikiKernel:Common.close").param("action", "edit").session(session(true)))
                .andExpect(status().isOk()).andExpect(model().attribute("editContent", "关闭"));
    }

    @Test void intMessagesRemainFreshWithPageCachingEnabled() {
        pages.editPage(null, "WikiKernel:Common.cancel", "CACHE_FIRST_MESSAGE");
        var page = pages.editPage(null, "界面消息缓存测试", "{{int:common.cancel}}");
        assertTrue(cache.body(page, "{{int:common.cancel}}").html().contains("CACHE_FIRST_MESSAGE"));
        assertTrue(cache.get(page.getTitle(), page.getLatestRevisionId()).isEmpty());
        pages.editPage(null, "WikiKernel:Common.cancel", "CACHE_SECOND_MESSAGE");
        assertTrue(cache.body(page, "{{int:common.cancel}}").html().contains("CACHE_SECOND_MESSAGE"));
    }

    @Test void pagerRetainsFiltersAndUsesRealMessageBoundaries() throws Exception {
        String html = mvc.perform(get("/wiki/特殊:所有消息").param("prefix", "common.").param("limit", "20"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var first = org.jsoup.Jsoup.parse(html);
        var rows = first.select("#mw-allmessagestable td.am_title>a");
        assertEquals(20, rows.size());
        String next = first.select(".mw-allmessages-nav a").first().attr("href");
        assertTrue(next.contains("limit=20")); assertTrue(next.contains("prefix=common."));
        String second = mvc.perform(get(java.net.URI.create(next))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNotEquals(rows.first().text(), org.jsoup.Jsoup.parse(second).select("#mw-allmessagestable td.am_title>a").first().text());
    }

    @Test void compatibleAllmessagesApiListsActualDefaultsAndCustomisations() throws Exception {
        pages.editPage(null, "WikiKernel:Common.cancel", "API_CANCEL");
        mvc.perform(get("/api.php").param("action", "query").param("meta", "allmessages")
                        .param("ammessages", "common.cancel|nonexistent-message-key").param("amincludedefault", "1").param("formatversion", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.query.allmessages[0]['*']").value("API_CANCEL"))
                .andExpect(jsonPath("$.query.allmessages[0].default").value("取消"))
                .andExpect(jsonPath("$.query.allmessages[0].customised").value(true))
                .andExpect(jsonPath("$.query.allmessages[1].missing").value(true));
        mvc.perform(get("/api.php").param("action", "query").param("meta", "allmessages")
                        .param("amprefix", "common.cancel").param("amcustomised", "modified"))
                .andExpect(jsonPath("$.query.allmessages.length()").value(1));
    }

    @Test void legacyConfigPagesKeepTheirExistingLanguageAndNamespacePriority() {
        pages.editPage(null, "WikiKernel:Sitenotice", "EXISTING_NOTICE_CONFIGURATION");
        pages.editPage(null, "MediaWiki:Sitenotice/en", "IMPORTED_EN_NOTICE");
        var notice = system.entries(Locale.ENGLISH).stream().filter(e -> e.name().equals("sitenotice")).findFirst().orElseThrow();
        assertEquals("EXISTING_NOTICE_CONFIGURATION", notice.currentText());
        assertEquals("WikiKernel:Sitenotice", notice.title());
        var sidebar = system.entries(Locale.ENGLISH).stream().filter(e -> e.name().equals("sidebar")).findFirst().orElseThrow();
        assertEquals("WikiKernel:Sidebar", sidebar.title());
    }
}
