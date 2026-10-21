package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.FeatureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class CodexExampleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureService features;

    @Test
    void codexExampleRendersCompleteIconGalleryAndCanBeDisabled() throws Exception {
        boolean original = features.codexExampleEnabled();
        try {
            features.setCodexExample(true);
            mockMvc.perform(get("/wiki/{title}", "特殊:CodexExample"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("codex-icon-gallery")))
                    .andExpect(content().string(containsString("cdx-button")))
                    .andExpect(content().string(containsString(">Add<")));

            features.setCodexExample(false);
            mockMvc.perform(get("/wiki/{title}", "特殊:CodexExample"))
                    .andExpect(status().isNotFound());
        } finally {
            features.setCodexExample(original);
        }
    }
}
