package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteSettingsService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 功能开关：评论（页面里的 comment 标签）与用户交互（头像上传 + 私信）可在
 * {@code 特殊:权限配置 → 站点设置} 或 LocalSettings.yaml 里启停。
 * <ul>
 *     <li>评论关闭：不渲染评论区、评论接口 403（评论区与接口已迁到插件 comments，见其测试）；</li>
 *     <li>用户交互关闭：用户页资料卡整体隐藏（未创建的用户页直接 404）；
 *         两个特殊页与两个接口已迁到插件 social-profile——core 未加载插件时它们本来就不存在
 *         （插件经 {@code SiteConfig} 读同一个键，关闭时提示不可用 / 接口 403）；</li>
 *     <li>微件关闭：{@code {{#widget:…}}} 不生效（显示「未启用」提示），页面其余内容照常。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class FeatureToggleTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private FeatureService featureService;
    @Autowired
    private SiteSettingsService siteSettingsService;


    @Test
    void commentsCanBeDisabled() throws Exception {
        siteSettingsService.set("features.comments", Boolean.toString(true));
        WikiUser user = insertUser("评论开关用户" + System.nanoTime());
        String page = "评论开关页" + System.nanoTime();
        wikiPageService.editPage(user, page, "正文\n<comment />");

        // 评论已迁到插件 comments：core 未加载插件时，无论开关如何都不渲染评论区
        // （开关本身仍生效：插件经 SiteConfig 读同一个键，见 plugin/comments）
        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("正文")))
                .andExpect(content().string(containsString("&lt;comment /&gt;")))
                .andExpect(content().string(not(containsString("class=\"wiki-comments\""))));

        siteSettingsService.set("features.comments", Boolean.toString(false));
        try {
            mockMvc.perform(get("/wiki/{title}", page))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("正文")))
                    .andExpect(content().string(not(containsString("class=\"wiki-comments\""))));
            // 评论 API 由插件提供：core 未加载插件时该路径不存在
            mockMvc.perform(post("/api/comments").session(sessionOf(user))
                            .param("page", page).param("content", "x"))
                    .andExpect(status().is4xxClientError());
        } finally {
            siteSettingsService.set("features.comments", Boolean.toString(true));
        }
    }

    @Test
    void userInteractionSwitchIsOwnedByThePlugin() throws Exception {
        WikiUser owner = insertUser("交互开关用户" + System.nanoTime());
        String userPage = "用户:" + owner.getUsername();
        wikiPageService.editPage(owner, userPage, "简介");

        // core 不依赖插件开关：没有插件时资料卡照常渲染（头像与「上传头像 / 私信」链接由插件
        // social-profile 提供）；功能开关 features.social-profile 由插件自己读（UserProfileHook.enabled），
        // 插件未加载时 core 也不会因为它是 false 而隐藏资料卡
        siteSettingsService.set("features.social-profile", Boolean.toString(false));
        try {
            mockMvc.perform(get("/wiki/{title}", userPage))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("class=\"wiki-profile\"")))
                    .andExpect(content().string(containsString("/img/avatar-default.svg")));

            // 未创建的用户页：core 不读插件开关，照常渲染资料卡
            WikiUser noPage = insertUser("无页交互用户" + System.nanoTime());
            mockMvc.perform(get("/wiki/{title}", "用户:" + noPage.getUsername()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("class=\"wiki-profile\"")));

            // 两个特殊页与两个接口都由插件 social-profile 提供：core 未加载插件时不存在
            mockMvc.perform(get("/wiki/{title}", "特殊:私信"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/wiki/{title}", "特殊:上传头像"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/messages/send").session(sessionOf(owner))
                            .param("to", noPage.getUsername()).param("content", "x"))
                    .andExpect(status().is4xxClientError());
            mockMvc.perform(multipart("/api/user/avatar").session(sessionOf(owner))
                            .file(new MockMultipartFile("file", "a.png", "image/png", PNG)))
                    .andExpect(status().is4xxClientError());

            // 评论已迁到插件 comments：core 未加载插件时，即使功能开关开着也没有评论区
            String page = "交互评论页" + System.nanoTime();
            wikiPageService.editPage(owner, page, "正文\n<comment />");
            mockMvc.perform(get("/wiki/{title}", page))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("&lt;comment /&gt;")))
                    .andExpect(content().string(not(containsString("class=\"wiki-comments\""))));
        } finally {
            siteSettingsService.set("features.social-profile", Boolean.toString(true));
        }
    }

    @Test
    void widgetsCanBeDisabled() throws Exception {
        siteSettingsService.set("features.widgets", Boolean.toString(true));
        String suffix = String.valueOf(System.nanoTime());
        String widget = "开关框" + suffix;
        wikiPageService.editPage(null, "微件:" + widget, "<div class=\"widget-box\">框</div>");
        String page = "微件开关页" + suffix;
        wikiPageService.editPage(null, page, "前文\n\n{{#widget:" + widget + "}}");

        // 微件调用已迁到插件 widget：core 未加载插件时按原文显示（开关开启或关闭都一样）
        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("{{#widget:" + widget)))
                .andExpect(content().string(not(containsString("class=\"widget-box\""))));

        siteSettingsService.set("features.widgets", Boolean.toString(false));
        try {
            // 关闭：同样不生效，页面其余内容照常
            mockMvc.perform(get("/wiki/{title}", page))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("前文")))
                    .andExpect(content().string(not(containsString("class=\"widget-box\""))));
        } finally {
            siteSettingsService.set("features.widgets", Boolean.toString(true));
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
