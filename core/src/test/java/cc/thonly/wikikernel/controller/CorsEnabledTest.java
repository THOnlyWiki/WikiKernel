package cc.thonly.wikikernel.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS 启用（{@code wikikernel.cors.*}，等价于写在 LocalSettings.yaml 里）：
 * 允许的来源拿到跨域响应头、预检被直接应答、未允许的来源被 403 拒绝。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.cors.enabled=true",
        "wikikernel.cors.allowed-origins[0]=https://example.com",
        "wikikernel.cors.max-age=600"
})
@AutoConfigureMockMvc
class CorsEnabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowedOriginGetsCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/version").header(HttpHeaders.ORIGIN, "https://example.com"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://example.com"))
                .andExpect(header().string(HttpHeaders.VARY, containsString("Origin")));
    }

    @Test
    void preflightIsAnswered() throws Exception {
        mockMvc.perform(options("/api/version")
                        .header(HttpHeaders.ORIGIN, "https://example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://example.com"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "600"));
    }

    @Test
    void disallowedOriginRejected() throws Exception {
        mockMvc.perform(get("/api/version").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        mockMvc.perform(options("/api/version")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
