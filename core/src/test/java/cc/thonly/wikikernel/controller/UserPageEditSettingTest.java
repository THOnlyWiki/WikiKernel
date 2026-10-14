package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站点设置「允许编辑其他用户的用户页」：
 * LocalSettings 导入为 true → 他人可编辑；设置 API 权限（匿名 401 / 普通用户 403 / 管理员 200）并可随时关闭。
 * 独立上下文（自定义 properties），避免影响其它测试。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.permissions.allow-other-user-page-edit=true"
})
@AutoConfigureMockMvc
class UserPageEditSettingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SiteSettingsService siteSettingsService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void importedSettingAllowsOthersAndApiCanToggleIt() throws Exception {
        // LocalSettings 导入生效
        assertTrue(siteSettingsService.getBoolean(SiteSettingsService.ALLOW_OTHER_USER_PAGE_EDIT, false));

        WikiUser owner = insertUser("设置归属" + System.nanoTime());
        WikiUser other = insertUser("设置他人" + System.nanoTime());
        String title = "用户:" + owner.getUsername();
        wikiPageService.editPage(owner, title, "归属内容");

        // 他人可以打开编辑表单并保存
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(other)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
        mockMvc.perform(post("/wiki/{title}", title).session(sessionOf(other)).with(csrf())
                        .param("content", "他人修改后的内容"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(content().string(containsString("他人修改后的内容")));

        // 设置 API 权限：匿名 401、普通用户 403、管理员 200
        mockMvc.perform(post("/api/site-settings").param("allowOtherUserPageEdit", "1"))
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/site-settings").session(sessionOf(other)).param("allowOtherUserPageEdit", "1"))
                .andExpect(jsonPath("$.code").value(403));

        WikiUser admin = insertUser("设置管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        // 不带参数 = 关闭
        mockMvc.perform(post("/api/site-settings").session(sessionOf(admin)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("站点设置已保存"));
        assertFalse(siteSettingsService.getBoolean(SiteSettingsService.ALLOW_OTHER_USER_PAGE_EDIT, true));

        // 关闭后他人回到「查看源代码」
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(other)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("您没有权限编辑其他用户的用户页")))
                .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
    }

    // ==================== 工具 ====================

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
