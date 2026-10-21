package cc.thonly.wikikernel.startup;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 启动就绪探针：等待页轮询；应用完全就绪后返回 ready=true。 */
@RestController
public class StartupReadyController {

    private final StartupState state;

    public StartupReadyController(StartupState state) {
        this.state = state;
    }

    @GetMapping("/api/ready")
    public Map<String, Object> ready() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", state.ready());
        body.put("startedAt", state.startedAt().toString());
        body.put("uptimeMillis", state.uptimeMillis());
        return body;
    }
}
