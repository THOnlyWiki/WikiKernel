package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册开关（{@code features.register}，站点设置 / {@code /init} 可改）：
 * <ul>
 *     <li>默认开启：注册页 / 注册接口可用，登录页与顶栏有注册入口；</li>
 *     <li>关闭：非管理员访问 {@code 特殊:注册} 返回 403、注册接口 403，注册入口隐藏，特殊页面索引不列出；</li>
 *     <li>管理员 / 行政员仍可进入代为创建账号：创建后不切换当前登录。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class RegistrationToggleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private FeatureService featureService;

    @Test
    void disabledRegistrationBlocksNonAdminsAndHidesLinks() throws Exception {
        featureService.setRegister(false);
        try {
            // 页面：403（错误页由 WikiErrorController 渲染，MockMvc 不派发 /error，只校验状态码；文案见真机验证）
            mockMvc.perform(get("/wiki/{title}", "特殊:注册"))
                    .andExpect(status().isForbidden());
            // 普通登录用户同样进不去
            WikiUser normal = insertUser("注册普通用户" + System.nanoTime());
            mockMvc.perform(get("/wiki/{title}", "特殊:注册").session(sessionOf(normal)))
                    .andExpect(status().isForbidden());
            // 接口：HTTP 200 + JSON code 403（AjaxResult 约定）
            mockMvc.perform(post("/api/user/register")
                            .param("username", "关闭注册用户" + System.nanoTime())
                            .param("password", "secret123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
            // 登录页与顶栏不再显示注册入口
            mockMvc.perform(get("/wiki/{title}", "特殊:登录"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("创建账号"))))
                    .andExpect(content().string(not(containsString(">注册<"))));
            // 特殊页面索引不列出「特殊:注册」
            mockMvc.perform(get("/wiki/{title}", "特殊:特殊页面"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString(">注册</a>"))));
        } finally {
            featureService.setRegister(true);
        }
    }

    @Test
    void enabledRegistrationWorksForEveryone() throws Exception {
        featureService.setRegister(true);
        mockMvc.perform(get("/wiki/{title}", "特殊:注册"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-register-form\"")));
        mockMvc.perform(get("/wiki/{title}", "特殊:登录"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("创建账号")));
    }

    @Test
    void adminsCanCreateAccountsWhenDisabledWithoutSwitchingLogin() throws Exception {
        featureService.setRegister(false);
        try {
            WikiUser admin = insertUser("注册开关管理员" + System.nanoTime());
            permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
            MockHttpSession session = sessionOf(admin);

            // 管理员：页面可进（带「已关闭、可代为创建」提示），表单标记 data-admin-create
            mockMvc.perform(get("/wiki/{title}", "特殊:注册").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"wiki-register-form\"")))
                    .andExpect(content().string(containsString("data-admin-create=\"true\"")))
                    .andExpect(content().string(containsString("注册功能当前已关闭")));

            // 代为创建：账号创建成功、返回提示，当前登录仍是管理员
            String newName = "代为创建" + System.nanoTime();
            mockMvc.perform(post("/api/user/register").session(session)
                            .param("username", newName)
                            .param("password", "secret123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value(containsString("未切换当前登录")));
            assertTrue(userService.isExisted(newName), "管理员应能代为创建账号");
            WikiUser sessionUser = (WikiUser) session.getAttribute(UserSession.ATTRIBUTE);
            assertEquals(admin.getUsername(), sessionUser.getUsername(), "代为创建后不应切换当前登录");
        } finally {
            featureService.setRegister(true);
        }
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
