package cc.thonly.wikikernel.plugin.registrationquiz.web;

import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizSettings;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 原生客户端抽题接口；仅返回问题和下标，答案仍由既有注册钩子校验。 */
@RestController
public class RegistrationQuizApiController {
    private final RegistrationQuizSettings settings;

    public RegistrationQuizApiController(SiteConfig config) {
        this.settings = new RegistrationQuizSettings(config);
    }

    @GetMapping("/api/registration-quiz/challenge")
    public ResponseEntity<Map<String, Object>> challenge() {
        var challenge = settings.pick();
        Map<String, Object> data = challenge == null ? Map.of("required", false)
                : Map.of("required", true, "index", challenge.index(), "question", challenge.question());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);
    }
}
