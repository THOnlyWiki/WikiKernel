package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 按皮肤生效的站点样式 / 脚本页（仿 MediaWiki 的 {@code MediaWiki:Vector.css} / {@code .js}）的 HTTP 行为：
 * <ul>
 *     <li>渲染页面时注入「本次请求真正生效的皮肤」的 {@code WikiKernel:<皮肤 id>.css|js}；</li>
 *     <li>{@code ?useskin=} 预览与登录用户的皮肤偏好都会改变注入的皮肤页；</li>
 *     <li>皮肤页自身按代码页渲染；</li>
 *     <li>皮肤页的编辑权与 {@code WikiKernel:Common.css/.js} 同一套：普通用户 403、界面管理员可保存。</li>
 * </ul>
 * 本类把站点默认皮肤设为 {@code fallback}（多一个属性 ⇒ 独立 Spring 上下文 ⇒ 独立内存库），
 * 这样「默认皮肤」与 {@code ?useskin=vector} 两种皮肤都可断言，又不影响其它测试类。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.skins.default-skin=fallback"
})
@AutoConfigureMockMvc
class SkinSiteAssetsTest {

    private static final String SKIN_CSS = "body { outline: 3px solid red; }";
    private static final String SKIN_JS = "window.__skinJsLoaded = true;";
    private static final String VECTOR_CSS = ".vector-only { display: block; }";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Test
    void injectsActiveSkinCssAndJs() throws Exception {
        wikiPageService.editPage(null, "WikiKernel:fallback.css", SKIN_CSS);
        wikiPageService.editPage(null, "WikiKernel:fallback.js", SKIN_JS);
        String page = "皮肤注入页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");

        // 站点默认皮肤是 fallback（见类注解）→ 注入 WikiKernel:Fallback.css / .js
        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<style>" + SKIN_CSS + "</style>")))
                .andExpect(content().string(containsString(
                        "/wiki/WikiKernel%3AFallback.js?action=raw&amp;ctype=text/javascript")));
    }

    @Test
    void useskinPreviewSwitchesInjectedSkinPage() throws Exception {
        wikiPageService.editPage(null, "WikiKernel:fallback.css", SKIN_CSS);
        wikiPageService.editPage(null, "WikiKernel:vector.css", VECTOR_CSS);
        wikiPageService.editPage(null, "WikiKernel:vector.js", "window.__vectorJsLoaded = true;");
        String page = "皮肤预览页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");

        // ?useskin=vector 临时预览：注入 vector 的皮肤页，而不是站点默认 fallback 的
        mockMvc.perform(get("/wiki/{title}", page).param("useskin", "vector"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<style>" + VECTOR_CSS + "</style>")))
                .andExpect(content().string(not(containsString(SKIN_CSS))))
                .andExpect(content().string(containsString(
                        "/wiki/WikiKernel%3AVector.js?action=raw&amp;ctype=text/javascript")));
    }

    @Test
    void loggedInUserSkinPreferenceDecidesInjectedSkinPage() throws Exception {
        wikiPageService.editPage(null, "WikiKernel:fallback.css", SKIN_CSS);
        wikiPageService.editPage(null, "WikiKernel:vector.css", VECTOR_CSS);
        String page = "用户皮肤页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");

        // 两个用户选不同皮肤：同一页面各自注入自己皮肤的站点样式
        WikiUser vectorUser = insertUser("皮肤偏好用户A" + System.nanoTime(), "vector");
        WikiUser fallbackUser = insertUser("皮肤偏好用户B" + System.nanoTime(), "fallback");

        mockMvc.perform(get("/wiki/{title}", page).session(sessionOf(vectorUser)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<style>" + VECTOR_CSS + "</style>")))
                .andExpect(content().string(not(containsString(SKIN_CSS))));

        mockMvc.perform(get("/wiki/{title}", page).session(sessionOf(fallbackUser)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<style>" + SKIN_CSS + "</style>")))
                .andExpect(content().string(not(containsString(VECTOR_CSS))));
    }

    @Test
    void skinPageRendersAsCodePage() throws Exception {
        wikiPageService.editPage(null, "WikiKernel:codeview.css", ".codeview { color: teal; }");

        mockMvc.perform(get("/wiki/{title}", "WikiKernel:codeview.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-language=\"css\"")))
                .andExpect(content().string(containsString(".codeview { color: teal; }")))
                // 代码页面顶部有「清除浏览器缓存」提示（与 WikiKernel:Common.css 一致）
                .andExpect(content().string(containsString("清除浏览器缓存")));
    }

    @Test
    void onlyInterfaceAdminsCanSaveSkinPages() throws Exception {
        wikiPageService.editPage(null, "WikiKernel:skinedit.css", ".before {}");
        wikiPageService.editPage(null, "WikiKernel:skinedit.js", "var before = 1;");

        WikiUser normal = insertUser("皮肤普通用户" + System.nanoTime(), null);
        WikiUser admin = insertUser("皮肤管理员" + System.nanoTime(), null);
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        WikiUser interfaceAdmin = insertUser("皮肤界面管理员" + System.nanoTime(), null);
        permissionService.replaceFor(interfaceAdmin.getId(), List.of(PermissionService.GROUP_INTERFACE_ADMIN));

        // 普通用户 / 纯管理员：编辑页降级为「查看源代码」+ 专用提示，保存 403
        for (WikiUser user : List.of(normal, admin)) {
            mockMvc.perform(get("/wiki/{title}", "WikiKernel:skinedit.css")
                            .param("action", "edit").session(sessionOf(user)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("只有界面管理员可以编辑")))
                    .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
            mockMvc.perform(post("/wiki/{title}", "WikiKernel:skinedit.css")
                            .with(csrf()).session(sessionOf(user)).param("content", ".x {}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/wiki/{title}", "WikiKernel:skinedit.js")
                            .with(csrf()).session(sessionOf(user)).param("content", "var x = 1;"))
                    .andExpect(status().isForbidden());
        }

        // 界面管理员：皮肤 CSS / JS 都能保存
        mockMvc.perform(post("/wiki/{title}", "WikiKernel:skinedit.css")
                        .with(csrf()).session(sessionOf(interfaceAdmin))
                        .param("content", "body { outline: 1px solid red; }"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/wiki/{title}", "WikiKernel:skinedit.js")
                        .with(csrf()).session(sessionOf(interfaceAdmin))
                        .param("content", "window.skinEdit = true;"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/wiki/{title}", "WikiKernel:skinedit.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("body { outline: 1px solid red; }")));
    }

    // ==================== 工具 ====================

    private WikiUser insertUser(String username, String skin) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        user.setSkin(skin);
        wikiUserMapper.insert(user);
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
