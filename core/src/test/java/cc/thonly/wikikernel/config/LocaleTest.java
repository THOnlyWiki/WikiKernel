package cc.thonly.wikikernel.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.jsoup.Jsoup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证本地化：默认中文、{@code ?uselang=en} 切换且记入会话、API 提示跟随语言、
 * 不支持的语言回落到默认。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class LocaleTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void defaultsToChinese() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:登录"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("用户名")))
                .andExpect(content().string(containsString("记住我的登录状态")))
                .andExpect(content().string(not(containsString("??"))));
    }

    @Test
    void uselangSwitchesLanguageAndSticksInSession() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/wiki/{title}", "特殊:登录").param("uselang", "en").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Username")))
                .andExpect(content().string(containsString("Keep me logged in")))
                .andExpect(content().string(not(containsString("??"))));

        // 会话记住语言：后续请求不带 uselang 也是英文
        mockMvc.perform(get("/wiki/{title}", "特殊:注册").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Confirm password")))
                .andExpect(content().string(not(containsString("确认密码"))));
    }

    @Test
    void apiMessagesFollowLanguage() throws Exception {
        mockMvc.perform(post("/api/user/login")
                        .param("username", "nobody")
                        .param("password", "wrong")
                        .param("uselang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Incorrect username or password"));
    }

    @Test
    void unknownLanguageFallsBackToChinese() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:登录").param("uselang", "fr"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("用户名")));
    }

    @ParameterizedTest
    @CsvSource({
            "zh-Hant-HK, zh-HK, zh-hk, 用戶名",
            "zh_TW, zh-TW, zh-tw, 使用者名稱",
            "ja-JP, ja, ja, 利用者名"
    })
    void regionalLanguageUsesLocalizedMessagesAndKeepsQuery(String parameter, String tag, String code, String username)
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        String html = mockMvc.perform(get("/wiki/{title}?action=view&uselang={lang}", "特殊:登录", parameter).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(username)))
                .andReturn().getResponse().getContentAsString();
        var document = Jsoup.parse(html);
        assertEquals(tag, document.selectFirst("html").attr("lang"));
        var selected = document.selectFirst(".footer-languages a[aria-current=true]");
        assertNotNull(selected);
        assertEquals(tag, selected.attr("hreflang"));
        assertEquals("action=view&uselang=" + code, java.net.URI.create(selected.attr("href")).getQuery());
        mockMvc.perform(get("/wiki/{title}", "特殊:登录").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"" + tag + "\"")));
    }
}
