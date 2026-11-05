package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.actionapi.ActionApiNamespaces;
import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.*;
import cc.thonly.wikikernel.transfer.TransferNamespaces;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"wikikernel.database.file=:memory:", "wikikernel.site.name=InterfaceTest",
        "wikikernel.cache.page.enabled=true", "wikikernel.cache.page.backend=memory"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SystemInterfacePagesTest {
    @Autowired MockMvc mvc;
    @Autowired WikiPageService pages;
    @Autowired AccessControlService access;
    @Autowired PermissionService permissions;
    @Autowired WikiUserMapper users;
    @Autowired SystemInterfaceService system;

    @AfterEach
    void clean() {
        for (String key : List.of("Sitenotice", "Anonnotice", "Tagline", "Tagline/en", "Editnotice", "Editnotice-10")) {
            for (String namespace : List.of("WikiKernel:", "MediaWiki:")) {
                String title = namespace + key;
                access.findRule(AccessAction.READ, title).ifPresent(rule -> access.deleteRule(rule.getId()));
                pages.deletePage(null, title);
            }
        }
    }

    @Test
    void noticeSelectionIsOutsideCachedArticleAndAppearsAboveTitle() throws Exception {
        pages.editPage(null, "WikiKernel:Sitenotice", "SITE_NOTICE_TOKEN");
        pages.editPage(null, "WikiKernel:Anonnotice", "ANON_NOTICE_TOKEN");
        pages.editPage(null, "WikiKernel:Tagline", "SITE_TAGLINE_TOKEN");
        String title = "系统公告缓存测试";
        pages.editPage(null, title, "正文");
        String anon = mvc.perform(get("/wiki/{title}", title).param("useskin", "vector"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("ANON_NOTICE_TOKEN")))
                .andExpect(content().string(not(containsString("SITE_NOTICE_TOKEN"))))
                .andExpect(content().string(containsString("SITE_TAGLINE_TOKEN")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(anon.indexOf("ANON_NOTICE_TOKEN") < anon.indexOf("id=\"firstHeading\""));
        mvc.perform(get("/wiki/{title}", title).session(admin()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("SITE_NOTICE_TOKEN")))
                .andExpect(content().string(not(containsString("ANON_NOTICE_TOKEN"))));
    }

    @Test
    void dashFallsBackToSiteNoticeAndLocalizedTaglineOverridesGeneric() throws Exception {
        pages.editPage(null, "WikiKernel:Anonnotice", "-");
        pages.editPage(null, "WikiKernel:Sitenotice", "FALLBACK_NOTICE_TOKEN");
        pages.editPage(null, "WikiKernel:Tagline", "通用副标题");
        pages.editPage(null, "WikiKernel:Tagline/en", "English tagline");
        assertTrue(system.render("Tagline", Locale.US, null).contains("English tagline"));
        pages.editPage(null, "公告回退测试", "正文");
        mvc.perform(get("/wiki/公告回退测试")).andExpect(status().isOk()).andExpect(content().string(containsString("FALLBACK_NOTICE_TOKEN")));
        pages.editPage(null, "WikiKernel:Sitenotice", "-");
        assertNull(system.render("Sitenotice", Locale.ROOT, null));
    }

    @Test
    void privateNoticeCannotLeakAndNamespaceEditNoticeOnlyAppearsInEditor() throws Exception {
        pages.editPage(null, "WikiKernel:Sitenotice", "PRIVATE_NOTICE_TOKEN");
        access.saveRule("WikiKernel:Sitenotice", AccessAction.READ, PermissionService.GROUP_ADMIN);
        pages.editPage(null, "公告权限测试", "正文");
        mvc.perform(get("/wiki/公告权限测试")).andExpect(status().isOk()).andExpect(content().string(not(containsString("PRIVATE_NOTICE_TOKEN"))));
        pages.editPage(null, "WikiKernel:Editnotice", "GLOBAL_EDIT_TOKEN");
        pages.editPage(null, "WikiKernel:Editnotice-10", "TEMPLATE_EDIT_TOKEN");
        pages.editPage(null, "模板:系统编辑提示测试", "模板内容");
        mvc.perform(get("/wiki/模板:系统编辑提示测试"))
                .andExpect(content().string(not(containsString("TEMPLATE_EDIT_TOKEN"))));
        mvc.perform(get("/wiki/模板:系统编辑提示测试").param("action", "edit").session(admin()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("GLOBAL_EDIT_TOKEN")))
                .andExpect(content().string(containsString("TEMPLATE_EDIT_TOKEN")));
    }

    @Test
    void importedMessagesAndNamespaceNumbersRemainCompatible() {
        pages.editPage(null, "MediaWiki:Sitenotice", "IMPORTED_NOTICE_TOKEN");
        assertTrue(system.render("Sitenotice", Locale.ROOT, null).contains("IMPORTED_NOTICE_TOKEN"));
        pages.editPage(null, "WikiKernel:Sitenotice", "KERNEL_NOTICE_TOKEN");
        assertTrue(system.render("Sitenotice", Locale.ROOT, null).contains("KERNEL_NOTICE_TOKEN"));
        assertEquals(8, ActionApiNamespaces.idOf("MediaWiki:Sidebar"));
        assertEquals(9, ActionApiNamespaces.idOf("MediaWiki talk:Sidebar"));
        assertEquals("Sidebar", ActionApiNamespaces.bareTitle("MediaWiki讨论:Sidebar"));
        assertEquals(8, TransferNamespaces.mediaWikiId("MediaWiki:Sidebar"));
        assertEquals(9, TransferNamespaces.mediaWikiId("MediaWiki讨论:Sidebar"));
        assertEquals("管理员", access.requiredGroups(AccessAction.EDIT, "MediaWiki:Sitenotice"));
    }

    private MockHttpSession admin() {
        WikiUser user = new WikiUser();
        user.setUsername("InterfaceAdmin" + System.nanoTime());
        user.setPassword("unused");
        users.insert(user);
        permissions.grantDefault(user.getId());
        permissions.grant(user.getId(), PermissionService.GROUP_ADMIN, null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
