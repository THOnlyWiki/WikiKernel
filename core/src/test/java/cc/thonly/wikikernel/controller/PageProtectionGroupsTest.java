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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"wikikernel.database.file=:memory:", "wikikernel.site.name=ProtectionGroupsTest",
        "wikikernel.cache.page.enabled=true", "wikikernel.cache.page.backend=memory"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PageProtectionGroupsTest {
    @Autowired MockMvc mvc;
    @Autowired WikiPageService pages;
    @Autowired AccessControlService access;
    @Autowired PermissionService permissions;
    @Autowired WikiUserMapper users;

    @Test
    void uncreatedTitlesAcceptRulesWithoutCreatingBlankArticles() throws Exception {
        String title = "尚无正文的 插件页面" + System.nanoTime();
        String alias = title.replace(' ', '_');
        MockHttpSession admin = user(PermissionService.GROUP_ADMIN), editor = user(PermissionService.GROUP_PAGE_ADMIN);
        mvc.perform(get("/wiki/特殊:保护页面").param("page", alias).session(admin))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"wiki-protect-form\"")))
                .andExpect(content().string(not(containsString("用户不存在"))));
        mvc.perform(post("/api/pages/protect").session(admin).param("title", alias)
                        .param("readGroups", PermissionService.GROUP_ADMIN).param("editGroups", PermissionService.GROUP_ADMIN))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.title").value(title));
        assertTrue(pages.getByTitle(title).isEmpty(), "保护虚拟页面不能创建空白词条");
        assertFalse(access.canRead(title, null));
        assertTrue(access.canRead(title, (WikiUser) admin.getAttribute(UserSession.ATTRIBUTE)));
        mvc.perform(get("/wiki/特殊:保护页面").param("page", alias))
                .andExpect(content().string(not(containsString("id=\"wiki-protect-form\""))))
                .andExpect(content().string(not(containsString("用户不存在"))));
        mvc.perform(post("/api/pages/protect").session(editor).param("title", alias)
                        .param("readGroups", "*").param("editGroups", "*"))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/pages/protect").session(admin).param("title", title)
                        .param("readGroups", "*").param("editGroups", PermissionService.GROUP_PAGE_ADMIN))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(pages.getByTitle(title).isEmpty());
        pages.editPage(null, title, "后来创建的正文");
        mvc.perform(get("/wiki/{title}", title)).andExpect(status().isOk());
        mvc.perform(get("/wiki/{title}", title).param("action", "edit").session(editor))
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
        mvc.perform(post("/api/pages/protect").session(admin).param("title", title)
                        .param("readGroups", "__inherit__").param("editGroups", "__inherit__"))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(access.findRule(AccessAction.READ, title).filter(r -> title.equals(r.getTarget())).isEmpty());
    }

    @Test
    void invalidProtectionTitlesAreRejectedBeforeSavingRules() throws Exception {
        MockHttpSession admin = user(PermissionService.GROUP_ADMIN);
        for (String title : new String[]{"", "非法|标题", "../相对标题"}) {
            mvc.perform(post("/api/pages/protect").session(admin).param("title", title)
                            .param("readGroups", "*").param("editGroups", PermissionService.GROUP_ADMIN))
                    .andExpect(jsonPath("$.code").value(400));
        }
        mvc.perform(get("/wiki/特殊:保护页面").param("page", "非法|标题").session(admin))
                .andExpect(status().isOk()).andExpect(content().string(containsString("非法字符")))
                .andExpect(content().string(not(containsString("id=\"wiki-protect-form\""))));
    }

    @Test
    void administratorOnlyReadingCannotLeakThroughApiOrTransclusion() throws Exception {
        String title = "仅管理员可看" + System.nanoTime();
        String output = "公开嵌入保护测试" + System.nanoTime();
        pages.editPage(null, title, "SECRET_READ_GROUP_TOKEN");
        MockHttpSession admin = user(PermissionService.GROUP_ADMIN);
        mvc.perform(post("/api/pages/protect").session(admin).param("title", title)
                        .param("readGroups", PermissionService.GROUP_ADMIN).param("editGroups", PermissionService.GROUP_ADMIN))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/wiki/{title}", title)).andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("SECRET_READ_GROUP_TOKEN"))));
        mvc.perform(get("/wiki/{title}", title).session(admin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("SECRET_READ_GROUP_TOKEN")));
        mvc.perform(get("/api.php").param("action", "query").param("prop", "revisions")
                        .param("rvprop", "content").param("titles", title))
                .andExpect(content().string(not(containsString("SECRET_READ_GROUP_TOKEN"))));
        pages.editPage(null, output, "{{:" + title + "}}");
        mvc.perform(get("/wiki/{title}", output)).andExpect(content().string(not(containsString("SECRET_READ_GROUP_TOKEN"))));
    }

    @Test
    void editingCanBeRestrictedWhileReadingRemainsPublic() throws Exception {
        String title = "公开只限管理编辑" + System.nanoTime();
        pages.editPage(null, title, "PUBLIC_READ_TOKEN");
        MockHttpSession admin = user(PermissionService.GROUP_ADMIN), reader = user(null);
        mvc.perform(post("/api/pages/protect").session(admin).param("title", title)
                        .param("readGroups", "*").param("editGroups", PermissionService.GROUP_ADMIN))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/wiki/{title}", title)).andExpect(status().isOk())
                .andExpect(content().string(containsString("PUBLIC_READ_TOKEN")));
        mvc.perform(get("/wiki/{title}", title).param("action", "edit").session(reader))
                .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
        mvc.perform(post("/api/pages/protect").session(reader).param("title", title)
                        .param("readGroups", "*").param("editGroups", "*"))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void nonAdministratorRoleCanEditAndInvalidSelectionsDoNotPartiallySave() throws Exception {
        String title = "指定身份编辑" + System.nanoTime();
        pages.editPage(null, title, "正文");
        MockHttpSession admin = user(PermissionService.GROUP_ADMIN), editor = user(PermissionService.GROUP_PAGE_ADMIN);
        mvc.perform(post("/api/pages/protect").session(admin).param("title", title)
                        .param("readGroups", "*").param("editGroups", PermissionService.GROUP_PAGE_ADMIN))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/wiki/{title}", title).param("action", "edit").session(editor))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
        assertFalse(pages.getByTitle(title).orElseThrow().getProtectedPage());
        mvc.perform(post("/api/pages/protect").session(admin).param("title", title)
                        .param("readGroups", PermissionService.GROUP_ADMIN).param("editGroups", "unknown_identity"))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals("*", access.requiredGroups(AccessAction.READ, title));
        assertEquals(PermissionService.GROUP_PAGE_ADMIN, access.requiredGroups(AccessAction.EDIT, title));
        mvc.perform(get("/wiki/特殊:保护页面").param("page", title).session(admin))
                .andExpect(status().isOk()).andExpect(content().string(containsString("protect-read-groups")))
                .andExpect(content().string(containsString("protect-edit-groups")));
    }

    @Test
    void inheritedRulesAndLegacyBooleanProtectionRemainAvailable() throws Exception {
        String title = "继承保护设置" + System.nanoTime();
        pages.editPage(null, title, "正文");
        MockHttpSession admin = user(PermissionService.GROUP_ADMIN);
        mvc.perform(post("/api/pages/protect").session(admin).param("title", title).param("protected", "true"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/api/pages/protect").session(admin).param("title", title)
                        .param("readGroups", "__inherit__").param("editGroups", "__inherit__"))
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(pages.getByTitle(title).orElseThrow().getProtectedPage());
        assertTrue(access.findRule(AccessAction.READ, title).filter(r -> title.equals(r.getTarget())).isEmpty());
        assertTrue(access.findRule(AccessAction.EDIT, title).filter(r -> title.equals(r.getTarget())).isEmpty());
    }

    private MockHttpSession user(String group) {
        WikiUser user = new WikiUser();
        user.setUsername("ProtectionUser" + System.nanoTime()); user.setPassword("unused"); users.insert(user);
        permissions.grantDefault(user.getId());
        if (group != null) { permissions.grant(user.getId(), group, null); }
        MockHttpSession session = new MockHttpSession(); session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
