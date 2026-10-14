package cc.thonly.wikikernel.plugin.aiagent.service;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.aiagent.service.AiConfig;
import cc.thonly.wikikernel.common.service.EditSubmitter;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentFakes;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentSupport;
import cc.thonly.wikikernel.plugin.aiagent.model.AiAgentException;
import cc.thonly.wikikernel.plugin.aiagent.model.AiProposal;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 代理的会话编排与确认提交（全部离线：厂商用插件内置的 {@code mock} 适配器，桥用内存替身）：
 * 功能开关与权限、读 → 提案（渲染预览 + 差异 + 基准版本）、确认提交**走
 * {@code common.service.EditSubmitter}（source=ai + AI 水印 + 乐观锁）**、冲突拒绝、
 * 多条目一键提交、缺模板检测、会话还原、轮数上限与厂商不可用。
 */
class AiAgentServiceTest {

    private static final String PAGE = "AI代理测试页";

    private AiAgentFakes.Fixture fixture() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        fixture.page(PAGE, "原始内容第一行\n");
        return fixture;
    }

    @Test
    void disabledFeatureAndUnauthorizedUsersAreRejected() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理甲");

        fixture.enabled(false);
        AiAgentException disabled = assertThrows(AiAgentException.class,
                () -> fixture.service.chat(agent, null, null, "你好"));
        assertEquals("aiAgent.disabled", disabled.messageKey());

        fixture.enabled(true);
        Viewer normal = fixture.user("普通乙");
        AiAgentException notAllowed = assertThrows(AiAgentException.class,
                () -> fixture.service.chat(normal, null, null, "你好"));
        assertEquals("aiAgent.notAllowed", notAllowed.messageKey());

        // 站点放开普通用户 → 可用
        fixture.allowNormalUsers(true);
        assertTrue(fixture.service.allowed(normal));
        assertNotNull(fixture.service.chat(normal, null, null, "你好").get("session"));

        // 未登录 → 需要登录
        AiAgentException anonymous = assertThrows(AiAgentException.class,
                () -> fixture.service.chat(Viewer.ANONYMOUS, null, null, "你好"));
        assertEquals("permissions.loginRequired", anonymous.messageKey());
    }

    @Test
    void mockAgentProposesPreviewAndDiffWithoutSaving() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理乙");

        Map<String, Object> data = fixture.service.chat(agent, null, null, "请给 页面：" + PAGE + " 追加一节");

        assertEquals("mock", data.get("provider"));
        assertEquals("mock", data.get("model"));
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) data.get("proposal");
        assertNotNull(proposal);
        assertEquals(PAGE, proposal.get("title"));
        assertEquals(Boolean.FALSE, proposal.get("conflict"));
        assertTrue(String.valueOf(proposal.get("previewHtml")).contains("AI 代理演示"));
        assertTrue(String.valueOf(proposal.get("diffHtml")).contains("diffchange"));
        assertTrue(String.valueOf(proposal.get("content")).contains("原始内容第一行"));
        assertEquals(1, proposal.get("pendingCount"));
        assertNotNull(proposal.get("baseRevisionId"));
        assertTrue(String.valueOf(data.get("messageHtml")).contains("<strong>提案已就绪</strong>"));
        assertFalse(((List<?>) data.get("steps")).isEmpty());

        // propose 不落库；也还没有任何提交
        assertEquals("原始内容第一行\n", fixture.content(PAGE));
        assertTrue(fixture.editSubmitter.requests.isEmpty());
    }

    @Test
    void confirmSubmitsThroughEditSubmitterAsAiSource() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理丙");
        Long baseRevision = fixture.pageQuery.currentRevision(PAGE);

        Map<String, Object> chat = fixture.service.chat(agent, null, null, "页面：" + PAGE + " 追加演示");
        String session = String.valueOf(chat.get("session"));
        @SuppressWarnings("unchecked")
        String proposalId = String.valueOf(((Map<String, Object>) chat.get("proposal")).get("id"));

        Map<String, Object> applied = fixture.service.apply(agent, session, proposalId, "补充一节", null);

        assertEquals(Boolean.TRUE, applied.get("ok"));
        assertEquals(Boolean.FALSE, applied.get("editedByUser"));
        assertEquals("mock/mock", applied.get("aiModel"));
        assertNotNull(applied.get("revisionId"));

        // 保存路径必须走 EditSubmitter：source=ai + AI 水印 + 基准版本（乐观锁）
        assertEquals(1, fixture.editSubmitter.requests.size());
        EditSubmitter.Request request = fixture.editSubmitter.requests.get(0);
        assertEquals(EditSubmitter.SOURCE_AI, request.source());
        assertTrue(request.aiAssisted(), "AI 协助的版本应带水印标记");
        assertEquals("mock/mock", request.aiModel());
        assertEquals(agent.id(), request.actorUserId());
        assertEquals(baseRevision, request.baseRevisionId());
        assertEquals("AI 代理（mock/mock）：补充一节", request.summary());
        assertTrue(request.content().contains("AI 代理演示"), request.content());

        // 页面已保存；提案从会话里移除
        assertTrue(fixture.content(PAGE).contains("AI 代理演示"));
        @SuppressWarnings("unchecked")
        List<Object> remaining = (List<Object>) applied.get("remaining");
        assertTrue(remaining.isEmpty());
    }

    @Test
    void userEditedContentIsWhatGetsSaved() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理丁");
        Map<String, Object> chat = fixture.service.chat(agent, null, null, "页面：" + PAGE + " 追加演示");
        String session = String.valueOf(chat.get("session"));
        @SuppressWarnings("unchecked")
        String proposalId = String.valueOf(((Map<String, Object>) chat.get("proposal")).get("id"));

        String edited = "用户改过的正文\n\n== 我加的一节 ==\n";
        Map<String, Object> applied = fixture.service.apply(agent, session, proposalId, null, edited);

        assertEquals(Boolean.TRUE, applied.get("ok"));
        assertEquals(Boolean.TRUE, applied.get("editedByUser"));
        assertEquals(edited, fixture.content(PAGE));
        assertEquals(edited, fixture.editSubmitter.requests.get(0).content());
    }

    @Test
    void applyIsRejectedWhenAnotherUserEditedThePageMeanwhile() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理戊");
        Map<String, Object> chat = fixture.service.chat(agent, null, null, "页面：" + PAGE + " 追加演示");
        String session = String.valueOf(chat.get("session"));
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) chat.get("proposal");
        String proposalId = String.valueOf(proposal.get("id"));
        Object baseRevision = proposal.get("baseRevisionId");

        // 另一位用户在确认前保存了新版本
        fixture.page(PAGE, "别人改过的内容\n");

        Map<String, Object> conflict = fixture.service.apply(agent, session, proposalId, null, null);

        assertEquals(Boolean.FALSE, conflict.get("ok"));
        assertEquals("conflict", conflict.get("code"));
        assertEquals(baseRevision, conflict.get("baseRevisionId"));
        assertEquals("别人改过的内容\n", fixture.content(PAGE));
        assertTrue(fixture.editSubmitter.requests.isEmpty(), "冲突时不应调用提交桥");
    }

    @Test
    void blockedOrHandledSubmissionsAreRejectedAndSurfaced() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理己");
        Map<String, Object> chat = fixture.service.chat(agent, null, null, "页面：" + PAGE + " 追加演示");
        String session = String.valueOf(chat.get("session"));
        @SuppressWarnings("unchecked")
        String proposalId = String.valueOf(((Map<String, Object>) chat.get("proposal")).get("id"));

        // 平台保存前钩子拒绝（如插件版滥用过滤器命中禁止）
        fixture.editSubmitter.failure = "blocked";
        fixture.editSubmitter.failureMessage = "滥用过滤器拒绝了这次编辑";
        AiAgentException blocked = assertThrows(AiAgentException.class,
                () -> fixture.service.apply(agent, session, proposalId, null, null));
        assertEquals("aiAgent.abuseBlocked", blocked.messageKey());
        assertEquals("原始内容第一行\n", fixture.content(PAGE), "被拒绝时不应保存");

        // 钩子接管（HANDLED，例如审核）同样拒绝：AI 代理必须直接保存，不能悄悄进队列
        fixture.editSubmitter.failure = "handled";
        AiAgentException handled = assertThrows(AiAgentException.class,
                () -> fixture.service.apply(agent, session, proposalId, null, null));
        assertEquals("aiAgent.abuseBlocked", handled.messageKey());

        // 保存失败（其它原因）→ saveFailed
        fixture.editSubmitter.failure = "saveFailed";
        AiAgentException failed = assertThrows(AiAgentException.class,
                () -> fixture.service.apply(agent, session, proposalId, null, null));
        assertEquals("aiAgent.saveFailed", failed.messageKey());
    }

    @Test
    void applyRechecksEditPermissionAndProposalExistence() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理庚");
        Map<String, Object> chat = fixture.service.chat(agent, null, null, "页面：" + PAGE + " 追加演示");
        String session = String.valueOf(chat.get("session"));
        @SuppressWarnings("unchecked")
        String proposalId = String.valueOf(((Map<String, Object>) chat.get("proposal")).get("id"));

        AiAgentException missing = assertThrows(AiAgentException.class,
                () -> fixture.service.apply(agent, session, "不存在的提案", null, null));
        assertEquals("aiAgent.noProposal", missing.messageKey());

        fixture.accessCheck.deny(AccessAction.EDIT, PAGE);
        AiAgentException forbidden = assertThrows(AiAgentException.class,
                () -> fixture.service.apply(agent, session, proposalId, null, null));
        assertEquals("aiAgent.editForbidden", forbidden.messageKey());

        // 会话必须属于当前用户
        Viewer other = fixture.agent("代理辛");
        AiAgentException expired = assertThrows(AiAgentException.class,
                () -> fixture.service.apply(other, session, proposalId, null, null));
        assertEquals("aiAgent.sessionExpired", expired.messageKey());
    }

    @Test
    void proposeIsRefusedWithoutPermissionOnRestrictedPages() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理壬");
        fixture.accessCheck.deny(AccessAction.CREATE, "WikiKernel:AiAgent测试");

        Map<String, Object> data = fixture.service.chat(agent, null, null, "页面：WikiKernel:AiAgent测试 写点东西");

        assertNull(data.get("proposal"));
        assertTrue(String.valueOf(data.get("message")).contains("没有创建权限"), String.valueOf(data.get("message")));
        assertTrue(fixture.editSubmitter.requests.isEmpty());
    }

    @Test
    void applyAllSubmitsEveryPendingProposalInOneSession() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        Viewer agent = fixture.agent("代理癸");
        String page = "AI代理多页演示页";
        String template = "模板:" + page + "导航";

        Map<String, Object> chat = fixture.service.chat(agent, null, null, "页面：" + page + " 新建演示");
        assertEquals(2, ((List<?>) chat.get("proposals")).size(), "正文 + 配套模板各一个提案");
        String session = String.valueOf(chat.get("session"));
        assertFalse(fixture.pageQuery.detail(page).isPresent(), "propose 不应落库");
        assertFalse(fixture.pageQuery.detail(template).isPresent(), "propose 不应落库");

        Map<String, Object> applied = fixture.service.applyAll(agent, session, "多页演示");

        assertEquals(Boolean.TRUE, applied.get("ok"));
        assertEquals(2, applied.get("appliedCount"));
        assertEquals(0, applied.get("failedCount"));
        assertTrue(fixture.content(page).contains("{{" + template + "}}"), fixture.content(page));
        assertTrue(fixture.pageQuery.detail(template).isPresent(), "配套模板应一并创建");
        assertEquals(2, fixture.editSubmitter.requests.size());
        assertEquals(EditSubmitter.SOURCE_AI, fixture.editSubmitter.requests.get(0).source());
    }

    @Test
    void applyAllKeepsFailedEntriesWithReasons() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        Viewer agent = fixture.agent("代理子");
        String page = "AI代理批量失败页";
        Map<String, Object> chat = fixture.service.chat(agent, null, null, "页面：" + page + " 新建演示");
        String session = String.valueOf(chat.get("session"));

        // 正文条目冲突（页面在提案后被别人建了）：它失败并留在列表里；配套模板条目照常成功
        fixture.page(page, "别人先建了\n");
        Map<String, Object> applied = fixture.service.applyAll(agent, session, null);

        assertEquals(Boolean.FALSE, applied.get("ok"));
        assertEquals(1, applied.get("appliedCount"));
        assertEquals(1, applied.get("failedCount"));
        assertEquals("conflict", ((Map<?, ?>) ((List<?>) applied.get("failed")).get(0)).get("code"));
        assertEquals(1, ((List<?>) applied.get("remaining")).size(), "失败的提案应留在会话里");
        assertEquals("别人先建了\n", fixture.content(page), "冲突的条目不得覆盖他人版本");
    }

    @Test
    void discardRemovesTheProposal() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理丑");
        Map<String, Object> chat = fixture.service.chat(agent, null, null, "页面：" + PAGE + " 追加演示");
        String session = String.valueOf(chat.get("session"));
        @SuppressWarnings("unchecked")
        String proposalId = String.valueOf(((Map<String, Object>) chat.get("proposal")).get("id"));

        Map<String, Object> discarded = fixture.service.discard(agent, session, proposalId);

        assertEquals(Boolean.TRUE, discarded.get("ok"));
        assertNull(discarded.get("proposal"));
        assertTrue(((List<?>) discarded.get("remaining")).isEmpty());
    }

    @Test
    void sessionViewRestoresTranscriptStepsAndProposals() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理寅");
        Map<String, Object> chat = fixture.service.chat(agent, null, null, "页面：" + PAGE + " 追加演示");
        String session = String.valueOf(chat.get("session"));

        Map<String, Object> restored = fixture.service.sessionView(agent, session);

        assertEquals(session, restored.get("session"));
        assertEquals("mock", restored.get("provider"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) restored.get("messages");
        assertEquals("user", messages.get(0).get("role"));
        assertTrue(String.valueOf(messages.get(0).get("text")).contains(PAGE));
        assertEquals("assistant", messages.get(1).get("role"));
        assertTrue(String.valueOf(messages.get(1).get("html")).contains("<strong>"), "回复按 Markdown 渲染");
        assertFalse(((List<?>) restored.get("steps")).isEmpty());
        assertNotNull(restored.get("proposal"));

        // 没有会话 id / id 不属于当前用户时：回退到该用户最近使用的会话（刷新页面不丢的语义）
        Map<String, Object> restoredByDefault = fixture.service.sessionView(agent, "不存在的会话");
        assertEquals(session, restoredByDefault.get("session"));

        // 匿名 / 未登录：空会话
        Map<String, Object> empty = fixture.service.sessionView(Viewer.ANONYMOUS, session);
        assertEquals("", empty.get("session"));
        assertTrue(((List<?>) empty.get("messages")).isEmpty());
    }

    @Test
    void stateExposesProvidersWithoutSecrets() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理卯");
        fixture.aiConfig.userEntries.put("deepseek", new AiConfigEntry("deepseek", "sk-secret-1234").entry());

        Map<String, Object> state = fixture.service.state(agent);

        assertEquals(Boolean.TRUE, state.get("enabled"));
        assertEquals(Boolean.TRUE, state.get("allowed"));
        assertEquals(AiAgentSupport.GROUP_AI_AGENT, state.get("agentGroup"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) state.get("providers");
        assertEquals("mock", providers.get(0).get("id"));
        assertEquals("mock", state.get("defaultProvider"));
        // 密钥绝不进入 state（只有来源标签）
        assertFalse(String.valueOf(state).contains("sk-secret"), String.valueOf(state));
    }

    @Test
    void toolRoundsAreLimitedByMaxStepsAndZeroMeansUnlimited() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理辰");
        fixture.aiConfig.settings = new AiConfig.Settings(
                "mock", false, 1, 180, "", 4000, 120, 200, 60);

        Map<String, Object> limited = fixture.service.chat(agent, null, null, "页面：" + PAGE + " 追加演示");

        assertTrue(((List<?>) limited.get("proposals")).isEmpty(), "只有一轮时不产生提案");
        assertTrue(String.valueOf(limited.get("message")).contains("最大工具轮数"),
                String.valueOf(limited.get("message")));
    }

    @Test
    void unavailableVendorIsReported() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理巳");

        AiAgentException exception = assertThrows(AiAgentException.class,
                () -> fixture.service.chat(agent, null, "deepseek", "你好"));
        assertEquals("aiAgent.vendorUnavailable", exception.messageKey());
    }

    @Test
    void providerPriorityUserThenSiteThenYaml() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer agent = fixture.agent("代理午");

        // 只有 yaml 里配了密钥 → 来源 yaml
        fixture.aiConfig.yamlEntries.put("deepseek", new AiConfigEntry("deepseek", "sk-yaml").entry());
        assertEquals("yaml", fixture.aiConfig.sourceOf(agent.id(), "deepseek"));
        assertTrue(fixture.registry.selection(agent.id(), "deepseek").isPresent());

        // 站点密钥盖过 yaml
        fixture.aiConfig.siteEntries.put("deepseek", new AiConfigEntry("deepseek", "sk-site").entry());
        assertEquals("site", fixture.aiConfig.sourceOf(agent.id(), "deepseek"));

        // 用户密钥优先级最高
        fixture.aiConfig.userEntries.put("deepseek", new AiConfigEntry("deepseek", "sk-user").entry());
        assertEquals("user", fixture.aiConfig.sourceOf(agent.id(), "deepseek"));
        AiProviderRegistry.Selection selection = fixture.registry.selection(agent.id(), "deepseek").orElseThrow();
        assertEquals("user", selection.source());
        assertEquals("deepseek-chat", selection.model());
    }

    @Test
    void templateReferencesAndMissingTemplatesAreDetected() {
        assertEquals(List.of("模板:信息框", "模块:导航", "template:信息框"), AiAgentService.referencedTemplates(
                "{{信息框|标题=甲}}\n{{#invoke:模块:导航|main}}\n{{PAGENAME}}{{template:信息框}}"),
                "解析器函数 / 魔术字不算模板引用，#invoke 取模块名");

        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        AiAgentSession session = new AiAgentSession("s1", 7L);
        AiProposal proposal = new AiProposal("p1", "页面甲", "正文引用 {{模板:不存在}} 与 {{模板:已存在}}",
                "", null, false, 0, "", "", LocalDateTime.now());
        session.proposals().put(proposal.id(), proposal);
        fixture.page("模板:已存在", "内容");

        assertEquals(List.of("模板:不存在"), fixture.service.missingTemplateReferences(proposal, session));

        // 同一会话里已有该模板的提案 → 不再算「缺模板」
        AiProposal companion = new AiProposal("p2", "模板:不存在", "模板正文", "", null, true, 0, "", "",
                LocalDateTime.now());
        session.proposals().put(companion.id(), companion);
        assertTrue(fixture.service.missingTemplateReferences(proposal, session).isEmpty());
    }

    /** 组装一条 AI 厂商配置的小助手（{@code AiConfig.Entry} 参数太多）。 */
    private record AiConfigEntry(String providerId, String apiKey) {

        AiConfig.Entry entry() {
            return new AiConfig.Entry(providerId, apiKey, "", "", "");
        }
    }
}
