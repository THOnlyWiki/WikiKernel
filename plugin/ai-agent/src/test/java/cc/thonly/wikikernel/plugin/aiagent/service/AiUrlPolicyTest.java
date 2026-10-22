package cc.thonly.wikikernel.plugin.aiagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiUrlPolicyTest {

    @Test
    void onlyPublicHttpEndpointsAreAllowed() {
        assertTrue(AiUrlPolicy.isPublicHttpUrl("http://8.8.8.8/v1"));
        assertTrue(AiUrlPolicy.isPublicHttpUrl("https://1.1.1.1/v1"));

        assertFalse(AiUrlPolicy.isPublicHttpUrl("http://127.0.0.1:11434/v1"));
        assertFalse(AiUrlPolicy.isPublicHttpUrl("http://localhost:8080/v1"));
        assertFalse(AiUrlPolicy.isPublicHttpUrl("http://10.0.0.1/v1"));
        assertFalse(AiUrlPolicy.isPublicHttpUrl("http://169.254.169.254/latest/meta-data"));
        assertFalse(AiUrlPolicy.isPublicHttpUrl("ftp://example.com/file"));
        assertFalse(AiUrlPolicy.isPublicHttpUrl("not a url"));
    }
}
