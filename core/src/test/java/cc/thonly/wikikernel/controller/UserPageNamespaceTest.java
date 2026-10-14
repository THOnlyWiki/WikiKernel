package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户命名空间页面：{@code 用户:X} 是普通 Wiki 页面（可创建 / 编辑 / 有工具侧栏），
 * {@code 特殊:用户?name=X} 302 到该页面；他人用户页默认不可编辑，管理员无视。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class UserPageNamespaceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Test
    void legacySpecialPageRedirectsToUserNamespaceOrShowsLookup() throws Exception {
        // 不带 name：显示输入框
        mockMvc.perform(get("/wiki/{title}", "特殊:用户"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"name\"")))
                .andExpect(content().string(containsString("进入用户页")));

        // 带 name：302 到 用户:<name>
        mockMvc.perform(get("/wiki/{title}", "特殊:用户").param("name", "某用户"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith(
                        URLEncoder.encode("用户:某用户", StandardCharsets.UTF_8).replace("+", "%20"))));
    }

    @Test
    void ownerCanCreateAndEditOwnUserPageWithTools() throws Exception {
        WikiUser owner = insertUser("用户页主人" + System.nanoTime());
        MockHttpSession session = sessionOf(owner);
        String title = "用户:" + owner.getUsername();

        // 不存在 → 创建页（本人可以）
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("本页面还不存在")))
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));

        // 保存 → 页面创建
        mockMvc.perform(post("/wiki/{title}", title).session(session).with(csrf())
                        .param("content", "这是我的用户页"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("这是我的用户页")))
                // 工具侧栏
                .andExpect(content().string(containsString("用户贡献")))
                .andExpect(content().string(containsString("查看用户组")));
    }

    @Test
    void otherUserPageEditDeniedByDefaultButAdminBypass() throws Exception {
        WikiUser owner = insertUser("页面归属" + System.nanoTime());
        WikiUser other = insertUser("其他用户" + System.nanoTime());
        String title = "用户:" + owner.getUsername();
        wikiPageService.editPage(owner, title, "归属者的用户页");

        // 他人：编辑页降级为查看源代码 + 提示；直接 POST 403
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(other)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("您没有权限编辑其他用户的用户页")))
                .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
        mockMvc.perform(post("/wiki/{title}", title).session(sessionOf(other)).with(csrf())
                        .param("content", "越权修改"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(content().string(containsString("归属者的用户页")));

        // 管理员无视该限制
        WikiUser admin = insertUser("用户页管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
    }

    @Test
    void missingAccountUserPageIs404WithCustomContent() throws Exception {
        String missing = "未注册账号" + System.nanoTime();

        // 账号不存在 → 404（MockMvc 不走容器错误转发，错误页内容直接请求 /error 验证）
        mockMvc.perform(get("/wiki/{title}", "用户:" + missing))
                .andExpect(status().isNotFound());

        // 用户讨论页也按缺失页面处理 → 404
        mockMvc.perform(get("/wiki/{title}", "用户讨论:" + missing))
                .andExpect(status().isNotFound());

        // 账号存在但用户页未创建 → 资料卡 + 尚未创建提示（200）
        WikiUser owner = insertUser("未建页账号" + System.nanoTime());
        mockMvc.perform(get("/wiki/{title}", "用户:" + owner.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("此用户页尚未创建")));

        // 404 错误页：默认内容来自启动时自动创建的 WikiKernel:404，三个短语是可点击蓝链
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("wiki-error-custom")))
                .andExpect(content().string(containsString("此页面目前没有内容")))
                .andExpect(content().string(containsString(">搜索本页标题</a>")))
                .andExpect(content().string(containsString(">搜索相关日志</a>")))
                .andExpect(content().string(containsString(">创建本页面</a>")))
                // 拿不到被访问标题时 h1 回退通用文案
                .andExpect(content().string(containsString(">页面不存在</h1>")));

        // 隐藏该页面 → 回退内置提示（同样带三个链接）
        WikiPage notFound = wikiPageService.getByTitle("WikiKernel:404").orElseThrow();
        notFound.setHided(true);
        wikiPageMapper.updateById(notFound);
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("wiki-error-custom"))))
                .andExpect(content().string(containsString("wiki-error-default")))
                .andExpect(content().string(containsString(">搜索本页标题</a>")));
        notFound.setHided(false);
        wikiPageMapper.updateById(notFound);

        // 占位符替换：$CREATE_URL$ 指向被访问标题的编辑页；h1 / <title> 显示被访问的标题
        wikiPageService.editPage(null, "WikiKernel:404",
                "点这里<a href=\"$CREATE_URL$\">创建本页</a>（$TITLE$）");
        String encoded = URLEncoder.encode("测试标题", StandardCharsets.UTF_8).replace("+", "%20");
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/wiki/" + encoded))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("?action=edit")))
                .andExpect(content().string(containsString(encoded)))
                .andExpect(content().string(containsString("（测试标题）")))
                .andExpect(content().string(containsString(">测试标题</h1>")))
                // 标题栏 =「页面名 - 站点名」；站点名可配置，这里只固定页面名部分
                .andExpect(content().string(containsString("<title>测试标题 - ")));
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
