package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.ConfigPages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 禁用标题名单（WikiKernel:Titleblacklist，仿 MediaWiki TitleBlacklist）、标题白名单、编辑邮箱白名单 / 黑名单
 * （WikiKernel:Email-whitelist / WikiKernel:Email-blacklist），以及 WikiKernel 命名空间的默认权限：
 * <ul>
 *     <li>黑 / 白名单页默认只有管理员能查看（其他人 404，不暴露规则）；</li>
 *     <li>普通规则禁止创建 / 移动 / 注册，{@code <noedit>} 也禁止编辑，{@code <autoconfirmed>} 豁免；</li>
 *     <li>{@code WikiKernel:Titlewhitelist} 命中时不受黑名单限制；</li>
 *     <li>邮箱白名单启用时只有匹配邮箱能保存、邮箱黑名单命中时不能保存（管理员 / 行政员豁免）；</li>
 *     <li>WikiKernel 命名空间默认只有管理员能编辑，可用 {@code WikiKernel:*} 访问规则覆盖。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class TitleBlacklistTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private AccessControlService accessControlService;

    /** 每个用例结束后清空三份名单（测试共享同一个内存库，避免影响其它测试类）。 */
    @AfterEach
    void clearLists() {
        setBlacklist("# 测试清理");
        setWhitelist("# 测试清理");
        setEmailWhitelist("# 测试清理");
    }

    // ==================== 页面可见性 & WikiKernel 命名空间 ====================

    @Test
    void titleBlacklistPagesAreHiddenFromNonAdmins() throws Exception {
        setBlacklist("# 标题黑名单测试标记");
        mockMvc.perform(get("/wiki/{title}", ConfigPages.TITLE_BLACKLIST))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/wiki/{title}", ConfigPages.TITLE_BLACKLIST).session(sessionOf(normalUser())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/wiki/{title}", ConfigPages.TITLE_BLACKLIST).session(sessionOf(adminUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("标题黑名单测试标记")));
        // 白名单页同样默认仅管理员可见
        mockMvc.perform(get("/wiki/{title}", ConfigPages.TITLE_WHITELIST))
                .andExpect(status().isNotFound());
        // 邮箱白名单页公开（用户需要知道允许哪些邮箱）
        mockMvc.perform(get("/wiki/{title}", ConfigPages.EMAIL_WHITELIST))
                .andExpect(status().isOk());
    }

    @Test
    void wikiKernelNamespaceEditableByAdminsOnlyButConfigurable() throws Exception {
        String title = "WikiKernel:命名空间权限测试" + System.nanoTime();
        wikiPageService.editPage(null, title, "内容", "测试");
        WikiUser user = normalUser();

        // 查看不受限；普通用户点编辑 → 查看源代码（没有编辑表单）
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk());
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
        // 管理员 → 编辑器
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(adminUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
        // 保存：普通用户 403，管理员成功
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(user)).param("content", "改"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(adminUser()))
                        .param("content", "管理员改"))
                .andExpect(status().is3xxRedirection());

        // 「可以配置」：WikiKernel:* 编辑 = 用户 → 普通用户也能编辑
        accessControlService.saveRule("WikiKernel:*", AccessAction.EDIT, PermissionService.GROUP_USER);
        try {
            mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(user)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
        } finally {
            accessControlService.findRule(AccessAction.EDIT, "WikiKernel:*")
                    .ifPresent(rule -> accessControlService.deleteRule(rule.getId()));
        }
    }

    // ==================== 禁用标题名单 ====================

    @Test
    void blacklistBlocksCreationEditAndMove() throws Exception {
        String blocked = "黑名单标题" + System.nanoTime();
        setBlacklist(".*" + blocked + ".*");
        WikiUser user = normalUser();
        MockHttpSession session = sessionOf(user);

        // 创建：保存接口 403 + 编辑页显示原因（查看源代码样式）
        mockMvc.perform(post("/wiki/{title}", blocked).with(csrf()).session(session).param("content", "内容"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/wiki/{title}", blocked).param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("禁用标题名单")));
        // 不匹配的标题可以创建
        String allowed = "正常标题" + System.nanoTime();
        mockMvc.perform(post("/wiki/{title}", allowed).with(csrf()).session(session).param("content", "内容"))
                .andExpect(status().is3xxRedirection());

        // 没有 <noedit>：可以编辑已存在的匹配标题（系统先建好）
        wikiPageService.editPage(null, blocked, "旧内容", "测试");
        mockMvc.perform(post("/wiki/{title}", blocked).with(csrf()).session(session).param("content", "新内容"))
                .andExpect(status().is3xxRedirection());

        // 移动：目标标题被禁止
        mockMvc.perform(post("/api/pages/move").session(session)
                        .param("from", allowed).param("to", blocked + "目标"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("禁用标题名单")));
    }

    @Test
    void noeditRulesAlsoBlockEditingAndAdminOverrides() throws Exception {
        String title = "禁止编辑标题" + System.nanoTime();
        wikiPageService.editPage(null, title, "旧内容", "测试");
        setBlacklist(title + " <noedit>");

        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(normalUser()))
                        .param("content", "新内容"))
                .andExpect(status().isForbidden());
        // 管理员 / 行政员豁免（仿 tboverride）
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(adminUser()))
                        .param("content", "管理员改"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void autoconfirmedExemptionAndTitleWhitelistWork() throws Exception {
        // <autoconfirmed>：普通用户被禁止，自动确定用户豁免
        String title = "自动确认豁免标题" + System.nanoTime();
        setBlacklist(title + " <autoconfirmed>");
        WikiUser user = normalUser();
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(user)).param("content", "x"))
                .andExpect(status().isForbidden());
        permissionService.grant(user.getId(), PermissionService.GROUP_AUTOCONFIRMED, null);
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(user)).param("content", "x"))
                .andExpect(status().is3xxRedirection());

        // 标题白名单覆盖黑名单
        String white = "标题白名单测试" + System.nanoTime();
        setBlacklist(".*" + white + ".*");
        setWhitelist(white);
        mockMvc.perform(post("/wiki/{title}", white).with(csrf()).session(sessionOf(normalUser()))
                        .param("content", "x"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void blacklistFiltersRegistrationUsernames() throws Exception {
        // newaccountonly：只影响注册，不影响页面标题
        String bad = "违规用户名" + System.nanoTime();
        setBlacklist(".*" + bad + ".* <newaccountonly>");
        mockMvc.perform(post("/api/user/register").param("username", bad).param("password", "secret123"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("禁用标题名单")));
        mockMvc.perform(post("/wiki/{title}", bad).with(csrf()).session(sessionOf(normalUser()))
                        .param("content", "x"))
                .andExpect(status().is3xxRedirection());

        // 普通规则同样会阻止注册（与 MediaWiki 启用用户名来源时一致）
        String bad2 = "违规用户名2" + System.nanoTime() + "x9";
        setBlacklist(bad2);
        mockMvc.perform(post("/api/user/register").param("username", bad2).param("password", "secret123"))
                .andExpect(jsonPath("$.code").value(400));
    }

    // ==================== 编辑邮箱白名单 ====================

    @Test
    void emailWhitelistGatesSaving() throws Exception {
        String page = "邮箱白名单页面" + System.nanoTime();
        setEmailWhitelist("gmail\\.com");
        WikiUser noEmail = insertUser("无邮箱用户" + System.nanoTime(), "");
        WikiUser other = insertUser("非白名单用户" + System.nanoTime(), "user@example.com");
        WikiUser allowed = insertUser("白名单用户" + System.nanoTime(), "user@gmail.com");

        // 无邮箱 / 非白名单：编辑页只给查看源代码 + 原因，保存 403
        mockMvc.perform(get("/wiki/{title}", page).param("action", "edit").session(sessionOf(noEmail)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑邮箱白名单")));
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(noEmail)).param("content", "x"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(other)).param("content", "x"))
                .andExpect(status().isForbidden());
        // 白名单邮箱可以保存
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(allowed)).param("content", "x"))
                .andExpect(status().is3xxRedirection());
        // 管理员豁免（没有邮箱也能保存）
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(adminUser()))
                        .param("content", "管理员"))
                .andExpect(status().is3xxRedirection());

        // 页面只有注释 = 未启用：任何人都能保存
        setEmailWhitelist("# 未启用");
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(noEmail)).param("content", "x"))
                .andExpect(status().is3xxRedirection());
    }

    // ==================== 编辑邮箱黑名单 ====================

    @Test
    void emailBlacklistGatesSaving() throws Exception {
        String page = "邮箱黑名单页面" + System.nanoTime();
        setEmailBlacklist("spam\\.net");
        WikiUser blocked = insertUser("黑名单用户" + System.nanoTime(), "bad@spam.net");
        WikiUser fine = insertUser("黑名单外用户" + System.nanoTime(), "user@example.com");
        WikiUser noEmail = insertUser("黑名单无邮箱" + System.nanoTime(), "");

        // 命中黑名单：编辑页只给查看源代码 + 原因，保存 403
        mockMvc.perform(get("/wiki/{title}", page).param("action", "edit").session(sessionOf(blocked)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑邮箱黑名单")));
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(blocked)).param("content", "x"))
                .andExpect(status().isForbidden());
        // 未命中 / 没邮箱：可以保存
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(fine)).param("content", "x"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(noEmail)).param("content", "x"))
                .andExpect(status().is3xxRedirection());
        // 管理员豁免（命中黑名单也能保存）
        WikiUser admin = insertUser("名单黑管理员" + System.nanoTime(), "admin@spam.net");
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(admin)).param("content", "管理员"))
                .andExpect(status().is3xxRedirection());

        // 白名单 + 黑名单同时启用：先要求命中白名单，再要求不命中黑名单
        setEmailWhitelist("gmail\\.com");
        setEmailBlacklist("^bad.*@gmail\\.com$");
        WikiUser both = insertUser("双名单用户" + System.nanoTime(), "bad1@gmail.com");
        mockMvc.perform(get("/wiki/{title}", page).param("action", "edit").session(sessionOf(both)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑邮箱黑名单")));
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(both)).param("content", "x"))
                .andExpect(status().isForbidden());

        // 恢复未启用，避免影响同上下文其它用例
        setEmailWhitelist("# 未启用");
        setEmailBlacklist("# 未启用");
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(blocked)).param("content", "x"))
                .andExpect(status().is3xxRedirection());
    }

    // ==================== 工具 ====================

    private WikiUser insertUser(String username) {
        return insertUser(username, "");
    }

    private WikiUser insertUser(String username, String email) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail(email == null ? "" : email);
        wikiUserMapper.insert(user);
        return user;
    }

    private WikiUser normalUser() {
        return insertUser("名单测试用户" + System.nanoTime());
    }

    private WikiUser adminUser() {
        WikiUser user = insertUser("名单测试管理员" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_ADMIN));
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }

    private void setBlacklist(String content) {
        wikiPageService.editPage(null, ConfigPages.TITLE_BLACKLIST, content, "测试");
    }

    private void setWhitelist(String content) {
        wikiPageService.editPage(null, ConfigPages.TITLE_WHITELIST, content, "测试");
    }

    private void setEmailWhitelist(String content) {
        wikiPageService.editPage(null, ConfigPages.EMAIL_WHITELIST, content, "测试");
    }

    private void setEmailBlacklist(String content) {
        wikiPageService.editPage(null, ConfigPages.EMAIL_BLACKLIST, content, "测试");
    }
}
