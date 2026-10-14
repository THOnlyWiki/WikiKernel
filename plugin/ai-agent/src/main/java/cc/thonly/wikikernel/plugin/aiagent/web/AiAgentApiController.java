package cc.thonly.wikikernel.plugin.aiagent.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentSupport;
import cc.thonly.wikikernel.plugin.aiagent.model.AiAgentException;
import cc.thonly.wikikernel.plugin.aiagent.model.AiException;
import cc.thonly.wikikernel.plugin.aiagent.service.AiAgentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 代理 API（插件自己的 {@code @RestController}，由 core 动态挂载到 MVC）：
 * <ul>
 *     <li>{@code GET  /api/ai-agent/state} —— 权限、厂商列表（不含密钥）与配置；</li>
 *     <li>{@code GET  /api/ai-agent/session} —— 还原界面：最近一次会话的对话记录 / 步骤 / 待确认提案（刷新不丢）；</li>
 *     <li>{@code POST /api/ai-agent/chat} —— 对话一轮（模型可多次调用只读工具，最后可能给出待确认提案）；</li>
 *     <li>{@code POST /api/ai-agent/apply} —— 用户确认后提交（复查权限与基准版本后走
 *         {@code common.service.EditSubmitter}：先跑平台保存前钩子，再以用户名义**直接保存**，不进审核队列）；</li>
 *     <li>{@code POST /api/ai-agent/apply-all} —— 一键确认提交**多个条目**（如正文 + 它引用的模板），逐个复查、失败的返回原因；</li>
 *     <li>{@code POST /api/ai-agent/discard} —— 放弃提案。</li>
 * </ul>
 * 对话按用户限流（{@code wikikernel.rate-limit.ai-agent}，默认 30 次/小时；限流实现走
 * {@code common.service.RateLimit} 桥，与 core 的登录 / 安装向导共用同一套滑动窗口）。
 */
@RestController
@RequestMapping("/api/ai-agent")
public class AiAgentApiController {

    /** 限流分类（core 的 {@code RateLimiterService.AI_AGENT}）。 */
    public static final String RATE_LIMIT_CATEGORY = "ai-agent";

    private final AiAgentService agentService;
    private final AiAgentSupport support;
    private final CurrentUser currentUser;
    private final RateLimit rateLimit;

    public AiAgentApiController(AiAgentService agentService, AiAgentSupport support,
                                CurrentUser currentUser, RateLimit rateLimit) {
        this.agentService = agentService;
        this.support = support;
        this.currentUser = currentUser;
        this.rateLimit = rateLimit;
    }

    /** 界面初始化：功能开关、当前用户是否有权、可用的 AI 厂商与默认厂商。 */
    @GetMapping("/state")
    public AjaxResult<Map<String, Object>> state() {
        return AjaxResult.success(agentService.state(viewer()));
    }

    /** 还原界面（刷新页面不丢）：最近一次会话的对话记录、步骤轨迹与待确认提案。 */
    @GetMapping("/session")
    public AjaxResult<Map<String, Object>> session(@RequestParam(value = "session", required = false) String session) {
        Viewer viewer = viewer();
        AjaxResult<Map<String, Object>> denied = denyAnonymous(viewer);
        if (denied != null) {
            return denied;
        }
        return AjaxResult.success(agentService.sessionView(viewer, session));
    }

    /** 对话一轮。 */
    @PostMapping("/chat")
    public AjaxResult<Map<String, Object>> chat(@RequestParam(value = "session", required = false) String session,
                                                @RequestParam(value = "provider", required = false) String provider,
                                                @RequestParam(value = "message", required = false) String message) {
        Viewer viewer = viewer();
        AjaxResult<Map<String, Object>> denied = denyAnonymous(viewer);
        if (denied != null) {
            return denied;
        }
        String key = "chat-" + viewer.id();
        RateLimit.Decision decision = rateLimit.check(RATE_LIMIT_CATEGORY, key);
        if (decision.limited()) {
            return AjaxResult.error(AjaxCode.TOO_MANY_REQUESTS,
                    message("aiAgent.rateLimited", decision.retryAfterSeconds()));
        }
        rateLimit.hit(RATE_LIMIT_CATEGORY, key);
        try {
            return AjaxResult.success(agentService.chat(viewer, session, provider, message));
        } catch (AiAgentException exception) {
            return agentError(exception);
        } catch (AiException exception) {
            return AjaxResult.error(AjaxCode.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    /** 用户确认后提交（可带编辑摘要与用户在「Wikitext」里改过的正文）。 */
    @PostMapping("/apply")
    public AjaxResult<Map<String, Object>> apply(@RequestParam(value = "session", required = false) String session,
                                                 @RequestParam(value = "proposal", required = false) String proposal,
                                                 @RequestParam(value = "comment", required = false) String comment,
                                                 @RequestParam(value = "content", required = false) String content) {
        Viewer viewer = viewer();
        AjaxResult<Map<String, Object>> denied = denyAnonymous(viewer);
        if (denied != null) {
            return denied;
        }
        try {
            return AjaxResult.success(agentService.apply(viewer, session, proposal, comment, content));
        } catch (AiAgentException exception) {
            return agentError(exception);
        }
    }

    /** 一键确认提交：会话里**全部**待确认提案（多条目，如正文 + 模板）逐个提交，失败的返回原因。 */
    @PostMapping("/apply-all")
    public AjaxResult<Map<String, Object>> applyAll(@RequestParam(value = "session", required = false) String session,
                                                    @RequestParam(value = "comment", required = false) String comment) {
        Viewer viewer = viewer();
        AjaxResult<Map<String, Object>> denied = denyAnonymous(viewer);
        if (denied != null) {
            return denied;
        }
        try {
            return AjaxResult.success(agentService.applyAll(viewer, session, comment));
        } catch (AiAgentException exception) {
            return agentError(exception);
        }
    }

    /** 放弃提案。 */
    @PostMapping("/discard")
    public AjaxResult<Map<String, Object>> discard(@RequestParam(value = "session", required = false) String session,
                                                   @RequestParam(value = "proposal", required = false) String proposal) {
        Viewer viewer = viewer();
        AjaxResult<Map<String, Object>> denied = denyAnonymous(viewer);
        if (denied != null) {
            return denied;
        }
        try {
            return AjaxResult.success(agentService.discard(viewer, session, proposal));
        } catch (AiAgentException exception) {
            return agentError(exception);
        }
    }

    private Viewer viewer() {
        return currentUser.viewer();
    }

    private AjaxResult<Map<String, Object>> denyAnonymous(Viewer viewer) {
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        return null;
    }

    /** 业务异常 → 合适的 HTTP 语义码（403 / 400）。 */
    private AjaxResult<Map<String, Object>> agentError(AiAgentException exception) {
        AjaxCode code = switch (exception.messageKey()) {
            case "permissions.loginRequired" -> AjaxCode.UNAUTHORIZED;
            case "aiAgent.disabled", "aiAgent.notAllowed", "aiAgent.editForbidden", "aiAgent.createForbidden" ->
                    AjaxCode.FORBIDDEN;
            default -> AjaxCode.BAD_REQUEST;
        };
        return AjaxResult.error(code, message(exception.messageKey()));
    }

    private String message(String key, Object... args) {
        return support.message(key, key, args);
    }
}
