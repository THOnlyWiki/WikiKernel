package cc.thonly.wikikernel.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缓存策略：静态资源长缓存（跳页不闪 logo/CSS/JS），动态页面 no-cache 但不再 no-store
 * （Spring Security 默认的 no-store 曾导致所有图片每次跳页重新下载）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class StaticResourceCacheTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void staticResourcesUseLongCacheAndNoStoreIsGone() throws Exception {
        mockMvc.perform(get("/img/wiki-logo.svg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", allOf(
                        containsString("max-age"), not(containsString("no-store")))));

        mockMvc.perform(get("/css/base.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", allOf(
                        containsString("max-age"), not(containsString("no-store")))));

        mockMvc.perform(get("/js/auth.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", allOf(
                        containsString("max-age"), not(containsString("no-store")))));
    }

    @Test
    void staticTextResourcesDeclareUtf8() throws Exception {
        // 直接打开 /js/xxx.js 时按 UTF-8 解码（否则中文 Windows 浏览器按 GBK 显示乱码）
        mockMvc.perform(get("/js/auth.js"))
                .andExpect(header().string("Content-Type", allOf(
                        containsString("javascript"), containsString("charset=UTF-8"))));
        mockMvc.perform(get("/css/base.css"))
                .andExpect(header().string("Content-Type", allOf(
                        containsString("text/css"), containsString("charset=UTF-8"))));
    }

    @Test
    void dynamicPagesAreNoCacheButNotNoStore() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().string("Cache-Control", not(containsString("no-store"))));
    }

    @Test
    void renderedAssetUrlsCarryStartupVersion() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/css/base.css?v=")))
                .andExpect(content().string(containsString("/skins/vector/vector.css?v=")))
                .andExpect(content().string(containsString("/js/code-editor.js?v=")));
    }
}
