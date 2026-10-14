package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户间私信已迁到插件 {@code social-profile}：**core 未加载插件时**
 * {@code 特殊:私信} 是未注册的特殊页（404）、{@code /api/messages/*} 不存在、
 * 顶栏个人工具区没有「私信」入口、用户页资料卡没有「私信」链接、用户页侧栏没有「发私信」。
 * <p>
 * 插件加载后由插件特殊页（{@code SpecialPage} + 插件模板）、插件 {@code @RestController}
 * 与 {@code PersonalToolbarHook} / {@code UserProfileHook} 接管；完整行为见
 * {@code plugin/social-profile/src/test}（{@code MessageCenterTest}、{@code SocialProfileCoreHooksTest}）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class MessageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageService wikiPageService;

    @Test
    void messagesSpecialPageAndApiAreProvidedByPluginOnly() throws Exception {
        WikiUser user = insertUser("私信用户" + System.nanoTime());

        // 特殊:私信 不再是 core 的特殊页 → 404
        mockMvc.perform(get("/wiki/{title}", "特殊:私信")).andExpect(status().isNotFound());
        mockMvc.perform(get("/wiki/{title}", "特殊:私信").param("box", "sent").session(sessionOf(user)))
                .andExpect(status().isNotFound());

        // 私信 API 由插件提供：core 未加载插件时这些路径不存在
        mockMvc.perform(post("/api/messages/send").session(sessionOf(user))
                        .param("to", "别人").param("content", "x"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/messages/delete").session(sessionOf(user)).param("id", "1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void personalToolsHaveNoMessageEntryWithoutPlugin() throws Exception {
        WikiUser viewer = insertUser("私信查看用户" + System.nanoTime());
        String page = "私信入口页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");

        // 登录用户的个人工具区照常（用户名 / 参数设置 / 贡献 / 退出），但没有插件贡献的私信条目
        mockMvc.perform(get("/wiki/{title}", page).session(sessionOf(viewer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(viewer.getUsername())))
                .andExpect(content().string(containsString("退出")))
                .andExpect(content().string(not(containsString("特殊:私信"))))
                .andExpect(content().string(not(containsString("personal-messages"))));

        // 匿名页面同样没有
        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("特殊:私信"))));
    }

    @Test
    void userPageProfileAndSidebarHaveNoMessageEntriesWithoutPlugin() throws Exception {
        WikiUser owner = insertUser("私信链接用户" + System.nanoTime());
        WikiUser other = insertUser("私信路人" + System.nanoTime());
        String userPage = "用户:" + owner.getUsername();
        wikiPageService.editPage(owner, userPage, "简介");

        // 看别人的用户页：资料卡只有「贡献」，没有插件贡献的「私信」；侧栏也没有「发私信」
        mockMvc.perform(get("/wiki/{title}", userPage).session(sessionOf(other)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("贡献")))
                .andExpect(content().string(not(containsString("特殊:私信"))))
                .andExpect(content().string(not(containsString("write=1"))));

        // 自己的用户页同样没有私信与写私信链接
        mockMvc.perform(get("/wiki/{title}", userPage).session(sessionOf(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("write=1"))));
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
