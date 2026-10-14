package cc.thonly.wikikernel.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 皮肤禁用：Vector（甚至 fallback）配置禁用时仍能正常打开站点——
 * Vector 被禁用 → 自动回退 fallback，其资源 404；fallback 的禁用配置被忽略。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.skins.default-skin=vector",
        "wikikernel.skins.disabled[0]=vector",
        "wikikernel.skins.disabled[1]=fallback"
})
@AutoConfigureMockMvc
class SkinDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void disabledVectorFallsBackToFallbackAndItsAssetsAreGone() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:版本"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已安装的软件")))
                .andExpect(content().string(containsString("回退皮肤")))
                .andExpect(content().string(containsString("（使用中）")))
                .andExpect(content().string(not(containsString("id=\"p-logo\""))))
                // Vector 不在可用皮肤列表里（其说明文案不出现）
                .andExpect(content().string(not(containsString("仿 MediaWiki Vector（经典版 2010）的默认皮肤"))));

        // 被禁用皮肤的资源不再提供
        mockMvc.perform(get("/skins/vector/vector.css"))
                .andExpect(status().isNotFound());
    }
}
