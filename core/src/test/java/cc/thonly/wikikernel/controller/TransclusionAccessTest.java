package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.*;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"wikikernel.database.file=:memory:",
        "wikikernel.cache.page.enabled=true", "wikikernel.cache.page.backend=memory"})
@AutoConfigureMockMvc
class TransclusionAccessTest {
    @Autowired MockMvc mvc;
    @Autowired WikiPageService pages;
    @Autowired AccessControlService access;
    @Autowired PermissionService permissions;
    @Autowired WikiUserMapper users;
    @Autowired PageCacheService cache;

    @Test
    void publicOutputCannotIncludePrivateTemplatesEvenWhenRenderedByAdmin() throws Exception {
        String title = "RestrictedSource" + System.nanoTime();
        String output = "PublicOutput" + System.nanoTime();
        String secret = "PRIVATE_CONTENT_" + System.nanoTime();
        pages.editPage(null, title, secret);
        access.saveRule(title, AccessAction.READ, PermissionService.GROUP_ADMIN);
        pages.editPage(null, output, "{{:" + title + "}}");
        mvc.perform(get("/wiki/{title}", output).session(admin()))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString(secret))));
        mvc.perform(get("/wiki/{title}", output))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString(secret))));
        mvc.perform(get("/api.php").param("action", "parse").param("title", output)
                        .param("text", "{{:" + title + "}}"))
                .andExpect(content().string(not(containsString(secret))));
    }

    @Test
    void equallyRestrictedOutputsCanStillUseTemplates() throws Exception {
        String source = "PrivateSource" + System.nanoTime();
        String output = "PrivateOutput" + System.nanoTime();
        String secret = "ALLOWED_PRIVATE_" + System.nanoTime();
        pages.editPage(null, source, secret);
        pages.editPage(null, output, "{{:" + source + "}}");
        access.saveRule(source, AccessAction.READ, PermissionService.GROUP_ADMIN);
        access.saveRule(output, AccessAction.READ, PermissionService.GROUP_ADMIN);
        mvc.perform(get("/wiki/{title}", output).session(admin()))
                .andExpect(status().isOk()).andExpect(content().string(containsString(secret)));
        mvc.perform(get("/wiki/{title}", output)).andExpect(status().isForbidden());
        String fake = "RestrictedNonexistent" + System.nanoTime();
        access.saveRule(fake, AccessAction.READ, PermissionService.GROUP_ADMIN);
        mvc.perform(get("/api.php").param("action", "parse").param("title", fake)
                        .param("text", "{{:" + source + "}}"))
                .andExpect(jsonPath("$.error.code").value("permissiondenied"));
    }

    @Test
    void readRuleChangesCannotReusePreviouslyPublicRenderedContent() throws Exception {
        String source = "CacheSource" + System.nanoTime();
        String output = "CacheOutput" + System.nanoTime();
        String secret = "CACHED_SECRET_" + System.nanoTime();
        pages.editPage(null, source, secret);
        var page = pages.editPage(null, output, "{{:" + source + "}}");
        mvc.perform(get("/wiki/{title}", output))
                .andExpect(content().string(containsString(secret)));
        assertTrue(cache.get(output, page.getLatestRevisionId()).isPresent());
        var rule = access.saveRule(source, AccessAction.READ, PermissionService.GROUP_ADMIN);
        assertTrue(cache.get(output, page.getLatestRevisionId()).isEmpty());
        mvc.perform(get("/wiki/{title}", output))
                .andExpect(content().string(not(containsString(secret))));
        access.deleteRule(rule.getId());
        mvc.perform(get("/wiki/{title}", output))
                .andExpect(content().string(containsString(secret)));
    }

    @Test
    void luaContentReadsAndPrivateModuleExecutionRespectOutputReaders() throws Exception {
        String source = "LuaPrivateSource" + System.nanoTime();
        String module = "ReadPrivate" + System.nanoTime();
        String output = "LuaPublicOutput" + System.nanoTime();
        String secret = "LUA_SECRET_" + System.nanoTime();
        pages.editPage(null, source, secret);
        access.saveRule(source, AccessAction.READ, PermissionService.GROUP_ADMIN);
        pages.editPage(null, "Module:" + module,
                "return { main = function() return mw.title.new('" + source + "'):getContent() or 'hidden' end }");
        pages.editPage(null, output, "{{#invoke:" + module + "|main}}");
        mvc.perform(get("/wiki/{title}", output))
                .andExpect(content().string(containsString("hidden")))
                .andExpect(content().string(not(containsString(secret))));
        String privateModule = "SecretModule" + System.nanoTime();
        pages.editPage(null, "Module:" + privateModule, "return { main = function() return '" + secret + "' end }");
        access.saveRule("Module:" + privateModule, AccessAction.READ, PermissionService.GROUP_ADMIN);
        mvc.perform(get("/api.php").param("action", "parse").param("title", output)
                        .param("text", "{{#invoke:" + privateModule + "|main}}"))
                .andExpect(content().string(not(containsString(secret))));
    }

    @Test
    void previewCannotUseARestrictedTitleToExtractPrivateSources() throws Exception {
        String source = "PreviewPrivateSource" + System.nanoTime();
        String fake = "PreviewPrivateContext" + System.nanoTime();
        pages.editPage(null, source, "DO_NOT_LEAK");
        access.saveRule(source, AccessAction.READ, PermissionService.GROUP_ADMIN);
        access.saveRule(fake, AccessAction.READ, PermissionService.GROUP_ADMIN);
        WikiUser user = new WikiUser();
        user.setUsername("PreviewUser" + System.nanoTime());
        user.setPassword("unused");
        users.insert(user);
        permissions.grantDefault(user.getId());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        for (String endpoint : new String[]{"/api/pages/preview", "/api/pages/visual/render"}) {
            mvc.perform(post(endpoint).session(session).param("title", fake).param("content", "{{:" + source + "}}"))
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(content().string(not(containsString("DO_NOT_LEAK"))));
        }
    }

    @Test
    void luaDataCachedByPrivateOutputCannotBeReusedByPublicOutput() throws Exception {
        String source = "LuaCachedPrivate" + System.nanoTime();
        String dataModule = "CachedData" + System.nanoTime();
        String mainModule = "ReadCachedData" + System.nanoTime();
        String privateOutput = "PrivateDataOutput" + System.nanoTime();
        String publicOutput = "PublicDataOutput" + System.nanoTime();
        String secret = "CACHED_LUA_SECRET_" + System.nanoTime();
        pages.editPage(null, source, secret);
        access.saveRule(source, AccessAction.READ, PermissionService.GROUP_ADMIN);
        access.saveRule(privateOutput, AccessAction.READ, PermissionService.GROUP_ADMIN);
        pages.editPage(null, "Module:" + dataModule,
                "return { value = mw.title.new('" + source + "'):getContent() or 'hidden' }");
        pages.editPage(null, "Module:" + mainModule,
                "return { main = function() return mw.loadData('Module:" + dataModule + "').value end }");
        String invocation = "{{#invoke:" + mainModule + "|main}}";
        pages.editPage(null, privateOutput, invocation);
        pages.editPage(null, publicOutput, invocation);
        mvc.perform(get("/wiki/{title}", privateOutput).session(admin()))
                .andExpect(content().string(containsString(secret)));
        mvc.perform(get("/wiki/{title}", publicOutput))
                .andExpect(content().string(containsString("hidden")))
                .andExpect(content().string(not(containsString(secret))));
        access.deleteRule(access.findRule(AccessAction.READ, source).orElseThrow().getId());
        mvc.perform(get("/wiki/{title}", publicOutput))
                .andExpect(content().string(containsString(secret)));
        access.saveRule(source, AccessAction.READ, PermissionService.GROUP_ADMIN);
        mvc.perform(get("/wiki/{title}", publicOutput))
                .andExpect(content().string(not(containsString(secret))));
    }

    private MockHttpSession admin() {
        WikiUser user = new WikiUser();
        user.setUsername("TransclusionAdmin" + System.nanoTime());
        user.setPassword("unused");
        users.insert(user);
        permissions.grantDefault(user.getId());
        permissions.grant(user.getId(), PermissionService.GROUP_ADMIN, null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
