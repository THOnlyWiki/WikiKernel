package cc.thonly.wikikernel.plugin.aiagent.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentFakes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /api/ai-agent/*} 的错误语义（与迁移前一致）：
 * 未登录 → 401；功能关闭 / 无权限 / 无编辑创建权限 → 403；其它业务错误 → 400；
 * 对话按用户限流 → 429；对话一轮成功 → 200。
 */
class AiAgentApiControllerTest {

    private static final String PAGE = "AI代理接口页";

    private AiAgentFakes.Fixture fixture() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        fixture.page(PAGE, "接口测试原稿\n");
        return fixture;
    }

    private AiAgentApiController controller(AiAgentFakes.Fixture fixture,
                                            AiAgentFakes.FakeCurrentUser currentUser) {
        return new AiAgentApiController(fixture.service, fixture.support, currentUser, fixture.rateLimit);
    }

    @Test
    void featureSwitchOffReturnsForbidden() {
        AiAgentFakes.Fixture fixture = fixture();
        AiAgentFakes.FakeCurrentUser currentUser = new AiAgentFakes.FakeCurrentUser();
        currentUser.viewer = fixture.agent("接口代理甲");
        fixture.enabled(false);

        AjaxResult<Map<String, Object>> result = controller(fixture, currentUser).chat(null, null, "你好");

        assertEquals(AjaxCode.FORBIDDEN.getCode(), result.getCode());
        assertTrue(String.valueOf(result.getMessage()).contains("未启用"), String.valueOf(result.getMessage()));
    }

    @Test
    void anonymousCallersGetUnauthorized() {
        AiAgentFakes.Fixture fixture = fixture();
        AiAgentFakes.FakeCurrentUser currentUser = new AiAgentFakes.FakeCurrentUser();
        currentUser.viewer = Viewer.ANONYMOUS;
        AiAgentApiController controller = controller(fixture, currentUser);

        assertEquals(AjaxCode.UNAUTHORIZED.getCode(), controller.session(null).getCode());
        assertEquals(AjaxCode.UNAUTHORIZED.getCode(), controller.chat(null, null, "你好").getCode());
        assertEquals(AjaxCode.UNAUTHORIZED.getCode(), controller.apply(null, null, null, null).getCode());
        assertEquals(AjaxCode.UNAUTHORIZED.getCode(), controller.applyAll(null, null).getCode());
        assertEquals(AjaxCode.UNAUTHORIZED.getCode(), controller.discard(null, null).getCode());
    }

    @Test
    void usersWithoutPermissionGetForbidden() {
        AiAgentFakes.Fixture fixture = fixture();
        AiAgentFakes.FakeCurrentUser currentUser = new AiAgentFakes.FakeCurrentUser();
        currentUser.viewer = fixture.user("接口普通用户");

        AjaxResult<Map<String, Object>> result = controller(fixture, currentUser).chat(null, null, "你好");

        assertEquals(AjaxCode.FORBIDDEN.getCode(), result.getCode());
        assertTrue(String.valueOf(result.getMessage()).contains("没有使用 AI 代理的权限"),
                String.valueOf(result.getMessage()));

        // 站点放开普通用户后可对话（Mock 厂商离线跑完整流程）
        fixture.allowNormalUsers(true);
        AjaxResult<Map<String, Object>> chat = controller(fixture, currentUser)
                .chat(null, null, "页面：" + PAGE + " 追加演示");
        assertEquals(AjaxCode.SUCCESS.getCode(), chat.getCode());
        assertNotNull(chat.getData().get("proposal"));
    }

    @Test
    void chatIsRateLimitedPerUser() {
        AiAgentFakes.Fixture fixture = fixture();
        AiAgentFakes.FakeCurrentUser currentUser = new AiAgentFakes.FakeCurrentUser();
        currentUser.viewer = fixture.agent("接口限流用户");
        AiAgentApiController controller = controller(fixture, currentUser);

        assertEquals(AjaxCode.SUCCESS.getCode(), controller.chat(null, null, "第一条").getCode());
        assertEquals(1, fixture.rateLimit.hits, "成功的对话应记一次事件");

        fixture.rateLimit.limited = true;
        AjaxResult<Map<String, Object>> limited = controller.chat(null, null, "第二条");

        assertEquals(AjaxCode.TOO_MANY_REQUESTS.getCode(), limited.getCode());
        assertTrue(String.valueOf(limited.getMessage()).contains("操作过于频繁"),
                String.valueOf(limited.getMessage()));
        assertEquals(1, fixture.rateLimit.hits, "被限流时不再计数");
    }

    @Test
    void stateAndSessionEndpointsReturnDataForLoggedInUsers() {
        AiAgentFakes.Fixture fixture = fixture();
        AiAgentFakes.FakeCurrentUser currentUser = new AiAgentFakes.FakeCurrentUser();
        currentUser.viewer = fixture.agent("接口状态用户");
        AiAgentApiController controller = controller(fixture, currentUser);

        AjaxResult<Map<String, Object>> state = controller.state();
        assertEquals(AjaxCode.SUCCESS.getCode(), state.getCode());
        assertEquals(Boolean.TRUE, state.getData().get("enabled"));
        assertEquals(Boolean.TRUE, state.getData().get("allowed"));

        // 没有会话时返回空会话（不是错误）
        AjaxResult<Map<String, Object>> session = controller.session(null);
        assertEquals(AjaxCode.SUCCESS.getCode(), session.getCode());
        assertEquals("", session.getData().get("session"));
        assertTrue(((List<?>) session.getData().get("messages")).isEmpty());
    }

    @Test
    void conflictAndUnknownProposalKeepTheirCodes() {
        AiAgentFakes.Fixture fixture = fixture();
        AiAgentFakes.FakeCurrentUser currentUser = new AiAgentFakes.FakeCurrentUser();
        currentUser.viewer = fixture.agent("接口冲突用户");
        AiAgentApiController controller = controller(fixture, currentUser);

        AjaxResult<Map<String, Object>> chat = controller.chat(null, null, "页面：" + PAGE + " 追加演示");
        String session = String.valueOf(chat.getData().get("session"));
        @SuppressWarnings("unchecked")
        String proposal = String.valueOf(((Map<String, Object>) chat.getData().get("proposal")).get("id"));

        // 别人先改了页面 → apply 返回 conflict（HTTP 语义仍是 200 + ok=false）
        fixture.page(PAGE, "别人改过\n");
        AjaxResult<Map<String, Object>> conflict = controller.apply(session, proposal, null, null);
        assertEquals(AjaxCode.SUCCESS.getCode(), conflict.getCode());
        assertEquals(Boolean.FALSE, conflict.getData().get("ok"));
        assertEquals("conflict", conflict.getData().get("code"));

        // 提案不存在 → 400
        AjaxResult<Map<String, Object>> missing = controller.apply(session, "不存在的提案", null, null);
        assertEquals(AjaxCode.BAD_REQUEST.getCode(), missing.getCode());

        // 会话不存在 → 400（会话过期语义）
        assertEquals(AjaxCode.BAD_REQUEST.getCode(), controller.applyAll("不存在的会话", null).getCode());
        assertNull(controller.discard(null, null).getData());
    }
}
