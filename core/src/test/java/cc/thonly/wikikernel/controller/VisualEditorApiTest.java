package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 可视化编辑器 API：渲染（剥目录）/ 回写（源码块保留）/ 权限与拒绝。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class VisualEditorApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void renderStripsGeneratedContentAndSerializeRoundTrips() throws Exception {
        wikiPageService.editPage(null, "可视化API测试页", "旧内容");
        MockHttpSession session = sessionOf(insertUser("ve-user-" + System.nanoTime()));
        String source = "== 一 ==\n\n=== 一一 ===\n\n== 二 ==\n\n正文 '''粗''' 与 <ref>来源</ref>。\n\n[[分类:测试]]\n";

        String body = mockMvc.perform(post("/api/pages/visual/render").session(session)
                        .param("title", "可视化API测试页")
                        .param("content", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        String html = JsonPath.read(body, "$.data.html");
        List<String> tokens = JsonPath.read(body, "$.data.tokens");
        assertFalse(html.contains("id=\"toc\""), html);
        assertFalse(html.contains("catlinks"), html);
        assertTrue(html.contains("ve-token"), html);
        assertTrue(tokens.size() >= 2, tokens.toString());

        String serialized = mockMvc.perform(post("/api/pages/visual/serialize").session(session)
                        .param("title", "可视化API测试页")
                        .param("html", html)
                        .param("tokens", objectMapper.writeValueAsString(tokens)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        assertEquals(source, JsonPath.read(serialized, "$.data.text"));
    }

    @Test
    void anonymousCannotUseVisualEditor() throws Exception {
        mockMvc.perform(post("/api/pages/visual/render")
                        .param("title", "可视化API测试页")
                        .param("content", "内容"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void unsupportedStructureIsRejected() throws Exception {
        wikiPageService.editPage(null, "可视化API测试页", "旧内容");
        MockHttpSession session = sessionOf(insertUser("ve-user2-" + System.nanoTime()));

        mockMvc.perform(post("/api/pages/visual/serialize").session(session)
                        .param("title", "可视化API测试页")
                        .param("html", "<p><span class=\"x\">?</span></p>")
                        .param("tokens", "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("可视化编辑器暂不支持")));
    }

    @Test
    void templateDialogEndpointFindsParametersPreviewsAndRebuilds() throws Exception {
        wikiPageService.editPage(null, "模板:VE卡片", "{{{名称|}}} × {{{数量|0}}}");
        wikiPageService.editPage(null, "可视化模板页", "旧内容");
        MockHttpSession session = sessionOf(insertUser("ve-tpl-" + System.nanoTime()));

        // 解析模式：发现模板全部参数 + 渲染预览
        String body = mockMvc.perform(post("/api/pages/visual/template").session(session)
                        .param("title", "可视化模板页")
                        .param("source", "{{VE卡片|名称=甲}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        assertEquals("VE卡片", JsonPath.read(body, "$.data.name"));
        assertEquals(Boolean.TRUE, JsonPath.read(body, "$.data.exists"));
        assertEquals(List.of("名称", "数量"), JsonPath.read(body, "$.data.params[*].name"));
        assertTrue(((String) JsonPath.read(body, "$.data.previewHtml")).contains("甲"));

        // 重建模式：值里的 | 转义成 {{!}}，预览生效
        String built = mockMvc.perform(post("/api/pages/visual/template").session(session)
                        .param("title", "可视化模板页")
                        .param("name", "VE卡片")
                        .param("params", "[{\"name\":\"名称\",\"value\":\"甲|乙\",\"positional\":false,"
                                + "\"present\":true}]"))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        assertEquals("{{VE卡片|名称=甲{{!}}乙}}", JsonPath.read(built, "$.data.source"));
        assertTrue(((String) JsonPath.read(built, "$.data.previewHtml")).contains("甲|乙"));

        // 无效调用 → 400（界面提示改用源代码编辑）
        mockMvc.perform(post("/api/pages/visual/template").session(session)
                        .param("title", "可视化模板页").param("source", "不是模板"))
                .andExpect(jsonPath("$.code").value(400));
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
