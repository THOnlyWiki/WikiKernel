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

@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class ApiSandboxTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiSandboxRendersAndSupportsBothPrefixes() throws Exception {
        for (String prefix : new String[]{"特殊:ApiSandbox", "Special:ApiSandbox"}) {
            mockMvc.perform(get("/wiki/{title}", prefix))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"api-sandbox-form\"")))
                    .andExpect(content().string(containsString("/js/api-sandbox.js")))
                    .andExpect(content().string(containsString("id=\"api-sandbox-action\"")))
                    .andExpect(content().string(containsString("query")));
        }
    }
}
