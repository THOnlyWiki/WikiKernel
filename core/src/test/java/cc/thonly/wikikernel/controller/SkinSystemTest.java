package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 皮肤系统：默认 Vector、{@code ?useskin=} 预览、用户偏好保存与生效、资源端点。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class SkinSystemTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private cc.thonly.wikikernel.service.WikiPageService wikiPageService;

    @Test
    void defaultSkinIsVectorWithServedAssets() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:版本"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"p-logo\"")))
                .andExpect(content().string(containsString("/skins/vector/vector.css")))
                .andExpect(content().string(containsString("（使用中）")));

        // 皮肤资源端点：从 classpath 读取，长缓存
        mockMvc.perform(get("/skins/vector/vector.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/css")))
                .andExpect(header().string("Cache-Control", containsString("max-age")))
                .andExpect(content().string(containsString("#wiki-head")));
        mockMvc.perform(get("/skins/vector/no-such-file.css"))
                .andExpect(status().isNotFound());
    }

    @Test
    void frontendLibrariesAreLoadedByDefault() throws Exception {
        // 全站默认引入本地内置的 jQuery、axios 与 MediaWiki JS API 兼容层（mw / mw.loader …）
        mockMvc.perform(get("/wiki/{title}", "特殊:版本"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/vendor/jquery/jquery.min.js")))
                .andExpect(content().string(containsString("/vendor/axios/axios.min.js")))
                .andExpect(content().string(containsString("/js/mediawiki.js")))
                // 服务端注入的 RLCONF（mw.config）
                .andExpect(content().string(containsString("window.RLCONF")))
                .andExpect(content().string(containsString("wgPageName")))
                .andExpect(content().string(containsString("wgUserTokens")))
                // 静态资源本身可访问
                .andExpect(content().string(containsString("wgFormattedNamespaces")));
        mockMvc.perform(get("/vendor/jquery/jquery.min.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("jQuery v3.7.1")));
        mockMvc.perform(get("/vendor/axios/axios.min.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Axios v")));
        mockMvc.perform(get("/js/mediawiki.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("mw.loader")));
    }

    @Test
    void mwConfigReflectsCurrentPage() throws Exception {
        String title = "Mw配置测试页" + System.nanoTime();
        wikiPageService.editPage(null, title, "内容");
        // 内容页：wgNamespaceNumber 0、wgIsArticle true、wgRevisionId 有值
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"wgNamespaceNumber\":0")))
                .andExpect(content().string(containsString("\"wgIsArticle\":true")))
                .andExpect(content().string(containsString("\"wgPageName\":\"" + title + "\"")));
        // 特殊页：命名空间 -1
        mockMvc.perform(get("/wiki/{title}", "特殊:版本"))
                .andExpect(content().string(containsString("\"wgNamespaceNumber\":-1")));
        // 模板页：命名空间 10（先创建，避免不存在页面走创建/404 分支）
        String templateTitle = "模板:Mw配置测试" + System.nanoTime();
        wikiPageService.editPage(null, templateTitle, "模板内容");
        mockMvc.perform(get("/wiki/{title}", templateTitle))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"wgNamespaceNumber\":10")));
    }

    @Test
    void useskinPreviewSwitchesLayoutWithoutSaving() throws Exception {
        // fallback：无 logo / 无 vector.css，但内容正常
        mockMvc.perform(get("/wiki/{title}", "特殊:版本").param("useskin", "fallback"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已安装的软件")))
                .andExpect(content().string(containsString("回退皮肤")))
                .andExpect(content().string(not(containsString("id=\"p-logo\""))))
                .andExpect(content().string(not(containsString("/skins/vector/vector.css"))));

        // 未知皮肤忽略，仍用默认 Vector
        mockMvc.perform(get("/wiki/{title}", "特殊:版本").param("useskin", "no-such-skin"))
                .andExpect(content().string(containsString("id=\"p-logo\"")));
    }

    @Test
    void userSkinPreferenceIsSavedAndApplied() throws Exception {
        WikiUser user = userService.createUser("皮肤用户" + System.nanoTime(), "secret123", "");
        MockHttpSession session = sessionOf(user);

        // 保存 fallback → 当前会话立即生效
        mockMvc.perform(post("/api/user/skin").session(session).param("skin", "fallback"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("皮肤偏好已保存"));
        mockMvc.perform(get("/wiki/{title}", "特殊:版本").session(session))
                .andExpect(content().string(not(containsString("id=\"p-logo\""))));

        // 落库：重新取用户也能拿到偏好
        assertEquals("fallback",
                userService.findByUsername(user.getUsername()).orElseThrow().getSkin());

        // 无效皮肤 400；匿名 401
        mockMvc.perform(post("/api/user/skin").session(session).param("skin", "no-such-skin"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/user/skin").param("skin", "fallback"))
                .andExpect(jsonPath("$.code").value(401));

        // 外观页：匿名提示登录；登录用户有表单
        mockMvc.perform(get("/wiki/{title}", "特殊:外观"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("登录后才能保存皮肤偏好")));
        mockMvc.perform(get("/wiki/{title}", "特殊:外观").session(session))
                .andExpect(content().string(containsString("id=\"skin-preference-form\"")))
                .andExpect(content().string(containsString("站点默认")));
    }

    @Test
    void fallbackLayoutKeepsPersonalToolsSearchAndTabs() throws Exception {
        // 匿名：用户栏（登录 / 注册）+ 搜索框 + 页签；回退皮肤下不能什么都没有
        mockMvc.perform(get("/wiki/{title}", "特殊:版本").param("useskin", "fallback"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"p-personal\"")))
                .andExpect(content().string(containsString(">登录</a>")))
                .andExpect(content().string(containsString(">注册</a>")))
                .andExpect(content().string(containsString("fallback-search")))
                .andExpect(content().string(containsString("fallback-tabs")));

        // 普通页面：回退皮肤同样有 阅读 / 编辑 / 查看历史 页签
        String title = "回退皮肤测试页" + System.nanoTime();
        wikiPageService.editPage(null, title, "内容");
        mockMvc.perform(get("/wiki/{title}", title).param("useskin", "fallback"))
                .andExpect(content().string(containsString("fallback-tabs")))
                .andExpect(content().string(containsString("?action=edit")))
                .andExpect(content().string(containsString("?action=history")));

        // 登录用户：用户栏必须有「外观」——否则回退皮肤下无法切回其它皮肤
        WikiUser user = userService.createUser("回退皮肤用户" + System.nanoTime(), "secret123", "");
        MockHttpSession session = sessionOf(user);
        mockMvc.perform(get("/wiki/{title}", title).session(session).param("useskin", "fallback"))
                .andExpect(content().string(containsString("id=\"p-personal\"")))
                .andExpect(content().string(containsString(">参数设置</a>")))
                .andExpect(content().string(containsString(">外观</a>")))
                .andExpect(content().string(containsString(">退出</a>")));

        // Vector 用户栏共用同一片段，也带「外观」入口
        mockMvc.perform(get("/wiki/{title}", title).session(session))
                .andExpect(content().string(containsString("id=\"p-personal\"")))
                .andExpect(content().string(containsString(">外观</a>")));
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
