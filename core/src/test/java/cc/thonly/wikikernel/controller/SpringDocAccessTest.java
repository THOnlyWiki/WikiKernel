package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.config.SpringDocAccessConfig;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.WikiUrlHelper;
import jakarta.servlet.http.Cookie;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class SpringDocAccessTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    @Autowired private PermissionService permissions;
    @Autowired private AccessControlService accessControl;

    @Test
    void anonymousVisitorsCannotReadDocumentationThroughAnyDefaultEntry() throws Exception {
        for (String path : new String[]{"/swagger-ui.html", "/swagger-ui/index.html", "/webjars/swagger-ui/index.html"}) {
            mvc.perform(get(path))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl(WikiUrlHelper.INSTANCE.loginTo(path)))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        for (String path : new String[]{"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
                "/swagger-ui/swagger-initializer.js", "/webjars/swagger-ui/swagger-ui-bundle.js"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(""));
        }
        mvc.perform(head("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void ordinaryWikiLoginUnlocksSwaggerAndRememberMeRestoresAccess() throws Exception {
        String username = "Swagger读者" + System.nanoTime();
        users.createUser(username, "secret123", "");
        var login = mvc.perform(post("/api/user/login").param("username", username)
                        .param("password", "secret123").param("remember", "1"))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);

        mvc.perform(get("/swagger-ui.html").session(session)).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
        mvc.perform(get("/swagger-ui/index.html").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
        mvc.perform(get("/v3/api-docs/swagger-config").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
        mvc.perform(get("/v3/api-docs").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.openapi").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/user/login'].post").exists());
        mvc.perform(get("/v3/api-docs.yaml").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("openapi:")));

        Cookie rememberMe = login.getResponse().getCookie("wiki_token");
        assertNotNull(rememberMe);
        mvc.perform(get("/v3/api-docs").cookie(rememberMe)).andExpect(status().isOk());
        mvc.perform(get("/api/user/logout").session(session).cookie(rememberMe))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/v3/api-docs").cookie(rememberMe)).andExpect(status().isUnauthorized());
    }

    @Test
    void savedReadRuleAppliesToTheUiAndSchemaForExistingSessions() throws Exception {
        String target = SpringDocAccessConfig.ACCESS_TARGET;
        WikiUser admin = users.createUser("接口文档管理" + System.nanoTime(), "secret123", "");
        WikiUser reader = users.createUser("接口文档读者" + System.nanoTime(), "secret123", "");
        permissions.grant(admin.getId(), PermissionService.GROUP_ADMIN, null);
        MockHttpSession adminSession = sessionOf(admin);
        MockHttpSession readerSession = sessionOf(reader);

        mvc.perform(post("/api/access-rules").session(readerSession).param("target", target)
                        .param("action", "read").param("groups", "*"))
                .andExpect(jsonPath("$.code").value(403));
        try {
            mvc.perform(post("/api/access-rules").session(adminSession).param("target", target)
                            .param("action", "read").param("groups", PermissionService.GROUP_ADMIN,
                                    PermissionService.GROUP_PAGE_ADMIN))
                    .andExpect(jsonPath("$.code").value(200));
            String html = mvc.perform(get("/wiki/{title}", "特殊:权限配置").session(adminSession))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var form = Jsoup.parse(html).selectFirst("#openapi-access-form");
            assertNotNull(form);
            assertEquals(target, form.select("input[name=target]").val());
            assertTrue(form.select("input[name=groups][checked]").eachAttr("value")
                    .contains(PermissionService.GROUP_PAGE_ADMIN));

            for (String path : new String[]{"/swagger-ui.html", "/swagger-ui/index.html",
                    "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
                    "/webjars/swagger-ui/swagger-ui-bundle.js"}) {
                mvc.perform(get(path).session(readerSession)).andExpect(status().isForbidden())
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
            mvc.perform(get("/v3/api-docs").session(adminSession)).andExpect(status().isOk());
            permissions.grant(reader.getId(), PermissionService.GROUP_PAGE_ADMIN, admin.getId());
            mvc.perform(get("/swagger-ui/index.html").session(readerSession)).andExpect(status().isOk());
            mvc.perform(get("/v3/api-docs").session(readerSession)).andExpect(status().isOk());
            mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        } finally {
            accessControl.findRule(AccessAction.READ, target)
                    .filter(rule -> target.equals(rule.getTarget()))
                    .ifPresent(rule -> accessControl.deleteRule(rule.getId()));
        }
    }

    private static MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
