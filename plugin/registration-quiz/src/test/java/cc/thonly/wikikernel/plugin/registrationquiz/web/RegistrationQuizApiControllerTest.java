package cc.thonly.wikikernel.plugin.registrationquiz.web;

import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.registrationquiz.*;
import cc.thonly.wikikernel.plugin.registrationquiz.hook.RegistrationQuizGuard;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizFakes.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationQuizApiControllerTest {
    @Test void challengeHasNoAnswersAndUsesTheExistingRegistrationGuard() {
        SiteConfig config = config(true, "本站名称？ | SERVER_SECRET_ANSWER", "", "", "");
        var response = new RegistrationQuizApiController(config).challenge();
        Map<String, Object> body = response.getBody();
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertNotNull(body);
        assertEquals(Set.of("required", "index", "question"), body.keySet());
        assertEquals(true, body.get("required"));
        assertEquals("本站名称？", body.get("question"));
        assertFalse(body.toString().contains("SERVER_SECRET_ANSWER"));
        var guard = new RegistrationQuizGuard(new RegistrationQuizSettings(config), new RegistrationQuizSupport(messages(Map.of())));
        assertEquals(RegistrationGuard.Outcome.Result.ALLOW, guard.beforeRegister(submit("新用户", "", Map.of(
                "registerQuizIndex", body.get("index").toString(), "registerAnswer", "SERVER_SECRET_ANSWER"))).result());
        assertEquals(RegistrationGuard.Outcome.Result.BLOCK, guard.beforeRegister(submit("新用户", "", Map.of(
                "registerQuizIndex", body.get("index").toString(), "registerAnswer", "答错"))).result());
    }

    @Test void disabledOrEmptyQuizHasNoQuestionOrIndex() {
        for (SiteConfig config : new SiteConfig[]{config(false, "问题 | 答案", "", "", ""), config(true, "", "", "", "")}) {
            assertEquals(Map.of("required", false), new RegistrationQuizApiController(config).challenge().getBody());
        }
    }

    @Test void challengeImmediatelyFollowsTheServerToggle() {
        AtomicBoolean enabled = new AtomicBoolean(true);
        SiteConfig config = new SiteConfig() {
            @Override public String get(String key, String fallback) {
                return RegistrationQuizSettings.QUIZ_KEY.equals(key) ? "问题 | 答案" : fallback;
            }
            @Override public boolean getBoolean(String key, boolean fallback) { return enabled.get(); }
        };
        var controller = new RegistrationQuizApiController(config);
        assertEquals(true, controller.challenge().getBody().get("required"));
        enabled.set(false);
        assertEquals(Map.of("required", false), controller.challenge().getBody());
    }
}
