package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户页资料卡与头像已迁到插件 {@code social-profile}：**core 未加载插件时**
 * 资料卡仍然渲染（core 的 {@code fragments/user-profile.html}），但用默认头像、没有
 * 「上传头像」/「私信」链接；{@code POST /api/user/avatar}、{@code GET /avatars/<文件名>} 与
 * {@code 特殊:上传头像} 都不存在（都由插件提供）。
 * <p>
 * 插件加载后由 {@code UserProfileHook}（头像 + 链接）、插件 {@code @RestController}（上传）与
 * 插件 {@code @Controller}（{@code /avatars/}）接管；完整行为见
 * {@code plugin/social-profile/src/test}（{@code AvatarUploadTest}）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class AvatarTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private UserService userService;

    @Test
    void userPageProfileUsesDefaultAvatarWithoutPlugin() throws Exception {
        WikiUser user = insertUser("头像用户" + System.nanoTime());
        String userPage = "用户:" + user.getUsername();
        wikiPageService.editPage(user, userPage, "个人简介");

        // 资料卡在（core 渲染），头像与额外链接由插件提供：没有插件时是默认头像、没有链接
        mockMvc.perform(get("/wiki/{title}", userPage))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"wiki-profile\"")))
                .andExpect(content().string(containsString("/img/avatar-default.svg")))
                .andExpect(content().string(containsString("个人简介")))
                .andExpect(content().string(not(containsString("特殊:上传头像"))))
                .andExpect(content().string(not(containsString("特殊:私信"))));

        // 本人也没有「上传头像」入口（入口由插件的资料卡钩子贡献）
        mockMvc.perform(get("/wiki/{title}", userPage).session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("特殊:上传头像"))));

        // 已经上传过头像的用户（wiki_user.avatar 有值）：插件未加载时仍显示默认头像
        userService.updateAvatar(user.getId(), "u" + user.getId() + "-1.png");
        mockMvc.perform(get("/wiki/{title}", userPage))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/img/avatar-default.svg")))
                .andExpect(content().string(not(containsString("/avatars/"))));
    }

    @Test
    void avatarUploadApiFileServiceAndSpecialPageAreProvidedByPluginOnly() throws Exception {
        // 上传接口由插件提供：core 未加载插件时该路径不存在（404 / 405）
        mockMvc.perform(multipart("/api/user/avatar")
                        .file(new MockMultipartFile("file", "a.png", "image/png", PNG)))
                .andExpect(status().is4xxClientError());

        // 头像文件服务由插件提供
        mockMvc.perform(get("/avatars/{name}", "u1-1.png")).andExpect(status().isNotFound());
        mockMvc.perform(get("/avatars/{name}", "missing.png")).andExpect(status().isNotFound());

        // 特殊:上传头像 / 特殊:私信 不再是 core 的特殊页 → 404
        mockMvc.perform(get("/wiki/{title}", "特殊:上传头像")).andExpect(status().isNotFound());
        mockMvc.perform(get("/wiki/{title}", "特殊:私信")).andExpect(status().isNotFound());
    }

    @Test
    void missingUserPageStillShowsProfileWithoutPlugin() throws Exception {
        WikiUser owner = insertUser("未建页用户" + System.nanoTime());
        String title = "用户:" + owner.getUsername();

        // 账号存在但用户页未创建：直接按阅读页显示资料卡（+ 尚未创建提示），不再 302 到创建页
        mockMvc.perform(get("/wiki/{title}", title).session(sessionOf(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"wiki-profile\"")))
                .andExpect(content().string(containsString("/img/avatar-default.svg")))
                .andExpect(content().string(containsString("此用户页尚未创建")))
                .andExpect(content().string(containsString("创建此页面")))
                .andExpect(content().string(not(containsString("特殊:上传头像"))));

        // 用户讨论页缺失 / 普通页面缺失 → 404（自定义 404 页）
        mockMvc.perform(get("/wiki/{title}", "用户讨论:" + owner.getUsername()).session(sessionOf(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/wiki/{title}", "不存在页" + System.nanoTime()))
                .andExpect(status().isNotFound());
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
