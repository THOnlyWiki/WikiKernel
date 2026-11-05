package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class ActionApiSafetyTest {
    @Autowired MockMvc mvc;
    @Autowired WikiPageService pages;
    @Autowired WikiUserMapper users;
    @Autowired PermissionService permissions;
    @Autowired AccessControlService access;
    @Autowired ObjectMapper mapper;
    @Autowired FileService files;

    @Test
    void jsonpCannotReadLoggedInIdentityTokensOrPrivateContent() throws Exception {
        MockHttpSession session = session();
        WikiUser user = (WikiUser) session.getAttribute(UserSession.ATTRIBUTE);
        permissions.grant(user.getId(), PermissionService.GROUP_ADMIN, null);
        String title = "JsonpPrivate" + System.nanoTime();
        String secret = "secret-body-" + System.nanoTime();
        pages.editPage(user, title, secret);
        access.saveRule(title, AccessAction.READ, PermissionService.GROUP_ADMIN);
        String csrf = csrf(session);
        mvc.perform(get("/api.php").session(session).param("action", "query")
                        .param("prop", "revisions").param("rvprop", "content").param("titles", title)
                        .param("formatversion", "2"))
                .andExpect(content().string(containsString(secret)));
        String body = mvc.perform(get("/api.php").session(session).param("action", "query")
                        .param("meta", "userinfo|tokens").param("type", "csrf|login")
                        .param("prop", "revisions").param("rvprop", "content").param("titles", title)
                        .param("callback", "$client.done").param("formatversion", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/javascript"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(not(containsString(user.getUsername()))))
                .andExpect(content().string(not(containsString(csrf))))
                .andExpect(content().string(not(containsString(secret))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var json = mapper.readTree(body.substring(body.indexOf('(') + 1, body.length() - 2));
        assertTrue(json.path("query").path("userinfo").path("anon").asBoolean());
        assertEquals("+\\", json.path("query").path("tokens").path("csrftoken").asString());
        mvc.perform(get("/api.php").session(session).param("action", "tokens").param("callback", "done"))
                .andExpect(content().string(not(containsString(csrf))));
        mvc.perform(get("/api.php").session(session).param("action", "query").param("meta", "userinfo"))
                .andExpect(jsonPath("$.query.userinfo.name").value(user.getUsername()));
        var anonymous = mvc.perform(get("/api.php").param("action", "query")
                        .param("meta", "tokens").param("callback", "done")).andReturn();
        String publicBody = anonymous.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(publicBody.contains("+\\"), publicBody);
        assertFalse(publicBody.contains(csrf), publicBody);
    }

    @Test
    void getCannotPerformAnyBuiltInWriteAndPostJsonpCannotWrite() throws Exception {
        MockHttpSession session = session();
        String csrf = csrf(session);
        for (String action : List.of("edit", "move", "delete", "protect", "block", "unblock",
                "upload", "rollback", "login", "logout")) {
            mvc.perform(get("/api.php").session(session).param("action", action).param("token", csrf))
                    .andExpect(jsonPath("$.error.code").value("mustbeposted"));
        }
        String title = "JsonpWrite" + System.nanoTime();
        mvc.perform(post("/api.php").session(session).param("action", "edit")
                        .param("title", title).param("text", "bad").param("token", csrf).param("callback", "done"))
                .andExpect(content().string(containsString("badvalue")));
        assertTrue(pages.getByTitle(title).isEmpty());
        assertNotNull(session.getAttribute(UserSession.ATTRIBUTE));
    }

    @Test
    void logoutRequiresTokenAndInvalidatesSessionOnlyOnSuccess() throws Exception {
        MockHttpSession session = session();
        String csrf = csrf(session);
        mvc.perform(post("/api.php").session(session).param("action", "logout"))
                .andExpect(jsonPath("$.error.code").value("badtoken"));
        assertFalse(session.isInvalid());
        mvc.perform(post("/api.php").session(session).param("action", "logout").param("token", csrf))
                .andExpect(jsonPath("$.error").doesNotExist());
        assertTrue(session.isInvalid());
    }

    @Test
    void editPreservesWhitespaceAndAcceptsPresenceBasedBooleans() throws Exception {
        MockHttpSession session = session();
        String csrf = csrf(session);
        String title = "ApiWhitespace" + System.nanoTime();
        String text = " preformatted first line\n\ntrailing blank line\n\n";
        mvc.perform(post("/api.php").session(session).param("action", "edit").param("title", title)
                        .param("text", text).param("minor", "").param("token", csrf))
                .andExpect(jsonPath("$.edit.result").value("Success"));
        var page = pages.getByTitle(title).orElseThrow();
        assertEquals(text, pages.getLatestContent(page.getId()).orElseThrow());
        assertTrue(pages.getRevision(page.getLatestRevisionId()).orElseThrow().getMinor());
        mvc.perform(post("/api.php").session(session).param("action", "edit").param("title", title)
                        .param("text", "overwrite").param("createonly", "").param("token", csrf))
                .andExpect(jsonPath("$.error.code").value("articleexists"));
        mvc.perform(post("/api.php").session(session).param("action", "edit").param("title", title + "Missing")
                        .param("text", "new").param("nocreate", "0").param("token", csrf))
                .andExpect(jsonPath("$.error.code").value("nocreate-missing"));
        assertEquals(text, pages.getLatestContent(page.getId()).orElseThrow());
    }

    @Test
    void newSectionCanCreateTalkPageAndNoredirectCanBeEmpty() throws Exception {
        MockHttpSession session = session();
        String csrf = csrf(session);
        String title = "Talk:ApiNewSection" + System.nanoTime();
        mvc.perform(post("/api.php").session(session).param("action", "edit").param("title", title)
                        .param("section", "new").param("sectiontitle", "Topic").param("text", " first post\n")
                        .param("token", csrf))
                .andExpect(jsonPath("$.edit.result").value("Success"));
        assertEquals("== Topic ==\n first post\n", pages.getLatestContent(
                pages.getByTitle(title).orElseThrow().getId()).orElseThrow());
        String from = "ApiMoveNoRedirect" + System.nanoTime();
        pages.editPage(null, from, "original");
        mvc.perform(post("/api.php").session(session).param("action", "move").param("from", from)
                        .param("to", from + "Moved").param("noredirect", "").param("token", csrf))
                .andExpect(jsonPath("$.move.redirectcreated").value(false));
        assertTrue(pages.getByTitle(from).isEmpty());
    }

    @Test
    void userAssertionsFailBeforeEditingUnderWrongIdentity() throws Exception {
        mvc.perform(get("/api.php").param("action", "query").param("assert", "user"))
                .andExpect(jsonPath("$.error.code").value("assertuserfailed"));
        MockHttpSession session = session();
        String csrf = csrf(session);
        mvc.perform(get("/api.php").session(session).param("action", "query").param("assert", "anon"))
                .andExpect(jsonPath("$.error.code").value("assertanonfailed"));
        mvc.perform(get("/api.php").session(session).param("action", "query").param("assert", "bot"))
                .andExpect(jsonPath("$.error.code").value("assertbotfailed"));
        String title = "WrongAccountEdit" + System.nanoTime();
        mvc.perform(post("/api.php").session(session).param("action", "edit").param("title", title)
                        .param("text", "bad").param("assertuser", "DifferentUser").param("token", csrf))
                .andExpect(jsonPath("$.error.code").value("assertnameduserfailed"));
        assertTrue(pages.getByTitle(title).isEmpty());
        WikiUser user = (WikiUser) session.getAttribute(UserSession.ATTRIBUTE);
        permissions.grant(user.getId(), PermissionService.GROUP_BOT, null);
        mvc.perform(get("/api.php").session(session).param("action", "query").param("assert", "bot")
                        .param("assertuser", user.getUsername()))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void invalidOrDeletedBaseRevisionCannotSilentlyOverwrite() throws Exception {
        MockHttpSession session = session();
        String csrf = csrf(session);
        String title = "DeletedBase" + System.nanoTime();
        pages.editPage(null, title, "old");
        String base = pages.getByTitle(title).orElseThrow().getLatestRevisionId().toString();
        mvc.perform(post("/api.php").session(session).param("action", "edit").param("title", title)
                        .param("text", "bad").param("baserevid", "invalid").param("token", csrf))
                .andExpect(jsonPath("$.error.code").value("badinteger"));
        pages.deletePage(null, title);
        mvc.perform(post("/api.php").session(session).param("action", "edit").param("title", title)
                        .param("text", "resurrect").param("baserevid", base).param("token", csrf))
                .andExpect(jsonPath("$.error.code").value("pagedeleted"));
        assertTrue(pages.getByTitle(title).isEmpty());
    }

    @Test
    void privateFileMetadataCannotLeakThroughAllimagesOrJsonp() throws Exception {
        MockHttpSession session = session();
        WikiUser admin = (WikiUser) session.getAttribute(UserSession.ATTRIBUTE);
        permissions.grant(admin.getId(), PermissionService.GROUP_ADMIN, null);
        String name = "PrivateFile" + System.nanoTime() + ".png";
        var file = files.store(admin, name, "", new byte[]{1, 2, 3});
        access.saveRule("File:" + name, AccessAction.READ, PermissionService.GROUP_ADMIN);
        mvc.perform(get("/api.php").param("action", "query").param("list", "allimages").param("ailimit", "500"))
                .andExpect(content().string(not(containsString(name))));
        mvc.perform(get("/api.php").session(session).param("action", "query")
                        .param("list", "allimages").param("ailimit", "500"))
                .andExpect(content().string(containsString(name)));
        mvc.perform(get("/api.php").session(session).param("action", "query")
                        .param("list", "allimages").param("ailimit", "500").param("callback", "done"))
                .andExpect(content().string(not(containsString(name))));
        mvc.perform(get("/files/{name}", file.getStoredName()).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().string("Cache-Control", not(containsString("immutable"))));
        mvc.perform(get("/files/{name}", file.getStoredName())).andExpect(status().isForbidden());
    }

    @Test
    void accessRulesUseTheSameCanonicalTitlesAsPageStorage() throws Exception {
        String suffix = "alias_test" + System.nanoTime();
        String canonical = WikiTitles.normalize("Template:" + suffix);
        access.saveRule(canonical, AccessAction.READ, PermissionService.GROUP_ADMIN);
        assertFalse(access.canRead("template:" + suffix, null));
        assertFalse(access.canRead(":" + canonical.replace(' ', '_'), null));
        String from = "AliasMoveSource" + System.nanoTime();
        String to = "Template:target_alias_" + System.nanoTime();
        pages.editPage(null, from, "original");
        access.saveRule(WikiTitles.normalize(to), AccessAction.CREATE, PermissionService.GROUP_ADMIN);
        mvc.perform(post("/api/pages/move").session(session()).param("from", from).param("to", to))
                .andExpect(jsonPath("$.code").value(403));
        assertTrue(pages.getByTitle(from).isPresent());
        assertTrue(pages.getByTitle(to).isEmpty());
    }

    private MockHttpSession session() {
        WikiUser user = new WikiUser();
        user.setUsername("ApiSafetyUser" + System.nanoTime());
        user.setPassword("unused");
        user.setEmail("");
        users.insert(user);
        permissions.grantDefault(user.getId());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }

    private String csrf(MockHttpSession session) throws Exception {
        String body = mvc.perform(get("/api.php").session(session).param("action", "query").param("meta", "tokens"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body).path("query").path("tokens").path("csrftoken").asString();
    }
}
