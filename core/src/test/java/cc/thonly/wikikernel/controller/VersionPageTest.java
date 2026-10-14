package cc.thonly.wikikernel.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 特殊:版本：软件版本、入口点、皮肤、客户端库、插件与扩展点（仿 MediaWiki Special:Version）。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class VersionPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void versionPageListsSoftwareSkinsAndExtensions() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:版本"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已安装的软件")))
                .andExpect(content().string(containsString("WikiKernel")))
                .andExpect(content().string(containsString("0.0.1-SNAPSHOT")))
                .andExpect(content().string(containsString("Spring Boot")))
                .andExpect(content().string(containsString("Java")))
                .andExpect(content().string(containsString("数据库")))
                .andExpect(content().string(containsString("入口点 URL")))
                .andExpect(content().string(containsString("/api/version")))
                .andExpect(content().string(containsString("已安装的皮肤")))
                .andExpect(content().string(containsString("Vector")))
                .andExpect(content().string(containsString("已安装的客户端库")))
                .andExpect(content().string(containsString("CodeMirror")))
                .andExpect(content().string(containsString("已安装的扩展")))
                .andExpect(content().string(containsString("扩展点")))
                .andExpect(content().string(containsString("PageRenderHook")));
    }

    @Test
    void versionPageIsLocalizedAndAliasWorks() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:版本").param("uselang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Installed software")))
                .andExpect(content().string(containsString("Entry point URLs")))
                .andExpect(content().string(containsString("Installed skins")))
                .andExpect(content().string(containsString("Extension points")));

        mockMvc.perform(get("/wiki/{title}", "Special:Version"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已安装的软件")));
    }
}
