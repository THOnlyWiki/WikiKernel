package cc.thonly.wikikernel.plugin.moderation.hook;

import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;
import cc.thonly.wikikernel.common.extension.ActionGuard;
import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.moderation.ModerationFakes;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核的三个入口钩子（不依赖 Spring）：
 * <ul>
 *     <li>{@link ModerationEditGuard}：编辑 / 创建进队列（{@code HANDLED} + 跳转），豁免组 / 开关关闭 / AI 提交放行；</li>
 *     <li>{@link ModerationActionGuard}：上传**暂存**（不是拒绝）+ 删除进队列，并回给前端 {@code pending} / {@code moderated}；</li>
 *     <li>{@link ModerationEditFormField}：编辑页「保存后进入审核队列」提示；</li>
 *     <li>{@link ModerationPageDisplayHook}：待审提示条 / 已提交提示 / 「待审创建」页。</li>
 * </ul>
 */
class ModerationHookTest {

    // ==================== 编辑 ====================

    @Test
    void editIsQueuedWithHandledAndRedirect() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("作者");
        String title = "钩子待审页";
        fixture.addPage(title, "旧正文");

        ModerationFakes.FakeEditContext context = new ModerationFakes.FakeEditContext();
        context.viewer = fixture.viewer(authorId);
        context.title = title;
        context.content = "新正文";
        context.summary = "改一下";
        context.baseRevisionId = 7L;

        EditGuard.Outcome outcome = fixture.editGuard().beforeSave(context);
        assertEquals(EditGuard.Outcome.Result.HANDLED, outcome.result());
        assertTrue(outcome.redirect().contains("?moderation=pending"), outcome.redirect());
        assertTrue(outcome.redirect().startsWith("/wiki/"), outcome.redirect());

        List<WikiPendingChange> pendings = fixture.store.forPage(title);
        assertEquals(1, pendings.size());
        WikiPendingChange change = pendings.get(0);
        assertEquals(WikiPendingChange.TYPE_EDIT, change.getChangeType());
        assertEquals(authorId, change.getUserId());
        assertEquals("新正文", change.getContent());
        assertEquals("改一下", change.getComment());
        assertEquals(fixture.pageIds.get(title), change.getPageId());
        assertEquals(7L, change.getBaseRevisionId());
        assertFalse(Boolean.TRUE.equals(change.getAiAssisted()));
    }

    @Test
    void newPageIsQueuedWithoutPageId() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("新页作者");

        ModerationFakes.FakeEditContext context = new ModerationFakes.FakeEditContext();
        context.viewer = fixture.viewer(authorId);
        context.title = "钩子新建页";
        context.content = "第一版";
        context.action = EditContext.ACTION_CREATE;

        assertEquals(EditGuard.Outcome.Result.HANDLED, fixture.editGuard().beforeSave(context).result());
        WikiPendingChange change = fixture.store.forPage("钩子新建页").get(0);
        assertNull(change.getPageId(), "新页面还没有 pageId");
        assertNull(change.getBaseRevisionId());
    }

    @Test
    void exemptGroupsAndSwitchOffLetEditsThrough() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        ModerationEditGuard guard = fixture.editGuard();

        // 豁免组：免审查用户 / 页面管理员 / 小部件管理员 / 小部件编辑者
        List<String> exempt = List.of(ModerationSupport.GROUP_MODERATION_EXEMPT,
                ModerationSupport.GROUP_PAGE_ADMIN, ModerationSupport.GROUP_GADGET_ADMIN,
                ModerationSupport.GROUP_WIDGET_EDITOR);
        for (String group : exempt) {
            long userId = fixture.addUser("豁免" + group, group);
            ModerationFakes.FakeEditContext context = new ModerationFakes.FakeEditContext();
            context.viewer = fixture.viewer(userId);
            context.title = "豁免页" + group;
            assertEquals(EditGuard.Outcome.Result.ALLOW, guard.beforeSave(context).result(), group);
        }
        assertTrue(fixture.store.changes.isEmpty(), "豁免组不进队列");

        // 审核人自己提交也要审核（迁移前语义一致：审核人不豁免）
        long reviewerId = fixture.addUser("核查员", ModerationSupport.GROUP_CHECKUSER);
        ModerationFakes.FakeEditContext reviewerContext = new ModerationFakes.FakeEditContext();
        reviewerContext.viewer = fixture.viewer(reviewerId);
        reviewerContext.title = "审核人自己的页";
        assertEquals(EditGuard.Outcome.Result.HANDLED, guard.beforeSave(reviewerContext).result());

        // 匿名用户放行（登录由 core 的访问控制负责）
        ModerationFakes.FakeEditContext anonymous = new ModerationFakes.FakeEditContext();
        anonymous.title = "匿名页";
        assertEquals(EditGuard.Outcome.Result.ALLOW, guard.beforeSave(anonymous).result());

        // 开关关闭：一律放行
        fixture.enabled(false);
        ModerationFakes.FakeEditContext off = new ModerationFakes.FakeEditContext();
        off.viewer = fixture.viewer(fixture.addUser("关闭后用户"));
        off.title = "关闭审核页";
        assertEquals(EditGuard.Outcome.Result.ALLOW, fixture.editGuard().beforeSave(off).result());
    }

    @Test
    void aiSubmissionsAreNotQueuedAndAiMarkersAreKeptWhenQueued() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("AI作者");

        // source=ai：AI 代理的提案由用户核对后直接保存（迁移前语义）
        ModerationFakes.FakeEditContext ai = new ModerationFakes.FakeEditContext();
        ai.viewer = fixture.viewer(authorId);
        ai.title = "AI页";
        ai.source = EditContext.SOURCE_AI;
        assertEquals(EditGuard.Outcome.Result.ALLOW, fixture.editGuard().beforeSave(ai).result());
        assertTrue(fixture.store.changes.isEmpty());

        // 表单里带 AI 标记（历史 / 未来的排队入口）：入队并保留标记
        ModerationFakes.FakeEditContext marked = new ModerationFakes.FakeEditContext();
        marked.viewer = fixture.viewer(authorId);
        marked.title = "带标记的页";
        marked.parameters = Map.of("aiAssisted", "1", "aiModel", "deepseek/deepseek-chat");
        assertEquals(EditGuard.Outcome.Result.HANDLED, fixture.editGuard().beforeSave(marked).result());
        WikiPendingChange change = fixture.store.forPage("带标记的页").get(0);
        assertTrue(Boolean.TRUE.equals(change.getAiAssisted()));
        assertEquals("deepseek/deepseek-chat", change.getAiModel());
    }

    // ==================== 上传 / 删除 ====================

    @Test
    void uploadIsStagedInsteadOfStored() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("上传作者");
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

        ModerationFakes.FakeActionContext context = new ModerationFakes.FakeActionContext();
        context.viewer = fixture.viewer(authorId);
        context.action = "upload";
        context.target = "文件:待审图.png";
        context.summary = "上传说明";
        context.data.put("name", "待审图.png");
        context.payload = png;

        ActionGuard.Outcome outcome = fixture.actionGuard().beforeAction(context);
        assertEquals(ActionGuard.Outcome.Result.HANDLED, outcome.result());
        assertTrue(outcome.message().contains("等待审核"), outcome.message());
        assertEquals(Boolean.TRUE, outcome.data().get("pending"));
        assertEquals(Boolean.TRUE, outcome.data().get("moderated"));
        assertEquals("待审图.png", outcome.data().get("name"));

        // 文件进暂存，没有写正式存储
        assertTrue(fixture.fileWriter.stored.isEmpty(), "待审上传不应写入正式存储");
        assertEquals(1, fixture.pendingFiles.files.size());
        WikiPendingChange change = fixture.store.forPage("文件:待审图.png").get(0);
        assertEquals(WikiPendingChange.TYPE_UPLOAD, change.getChangeType());
        assertEquals("待审图.png", change.getFileName());
        assertEquals("上传说明", change.getFileDescription());
        assertEquals((long) png.length, change.getFileSize());
        assertNotNull(change.getFileStoredName());
        assertArrayEqualsAtPending(fixture, change, png);
    }

    @Test
    void uploadWithoutPayloadOrForExemptUsersIsLetThrough() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("上传豁免", ModerationSupport.GROUP_MODERATION_EXEMPT);

        // 豁免组：放行（core 自己写正式存储）
        ModerationFakes.FakeActionContext exempt = new ModerationFakes.FakeActionContext();
        exempt.viewer = fixture.viewer(authorId);
        exempt.action = "upload";
        exempt.payload = new byte[]{1};
        assertEquals(ActionGuard.Outcome.Result.ALLOW, fixture.actionGuard().beforeAction(exempt).result());

        // 没有载荷（其它上传入口）：放行而不是把文件吞掉
        ModerationFakes.FakeActionContext noPayload = new ModerationFakes.FakeActionContext();
        noPayload.viewer = fixture.viewer(fixture.addUser("普通上传者"));
        noPayload.action = "upload";
        assertEquals(ActionGuard.Outcome.Result.ALLOW, fixture.actionGuard().beforeAction(noPayload).result());
        assertTrue(fixture.store.changes.isEmpty());
    }

    @Test
    void fileDeleteIsQueuedAndOtherActionsAreIgnored() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("删除作者");

        ModerationFakes.FakeActionContext delete = new ModerationFakes.FakeActionContext();
        delete.viewer = fixture.viewer(authorId);
        delete.action = "delete";
        delete.target = "文件:要删的.png";
        delete.data.put("id", "42");
        delete.data.put("name", "要删的.png");
        delete.data.put("hard", "true");

        ActionGuard.Outcome outcome = fixture.actionGuard().beforeAction(delete);
        assertEquals(ActionGuard.Outcome.Result.HANDLED, outcome.result());
        assertTrue(outcome.message().contains("等待审核"), outcome.message());
        WikiPendingChange change = fixture.store.forPage("文件:要删的.png").get(0);
        assertEquals(WikiPendingChange.TYPE_FILE_DELETE, change.getChangeType());
        assertEquals(42L, change.getFileId());
        assertTrue(Boolean.TRUE.equals(change.getHardDelete()));

        // 移动 / 保护等其它动作不审核（core 迁移前也没有 move 审核）
        ModerationFakes.FakeActionContext move = new ModerationFakes.FakeActionContext();
        move.viewer = fixture.viewer(authorId);
        move.action = "move";
        assertEquals(ActionGuard.Outcome.Result.ALLOW, fixture.actionGuard().beforeAction(move).result());

        // 没有文件 ID 的删除请求：放行（不去猜）
        ModerationFakes.FakeActionContext noId = new ModerationFakes.FakeActionContext();
        noId.viewer = fixture.viewer(authorId);
        noId.action = "delete";
        assertEquals(ActionGuard.Outcome.Result.ALLOW, fixture.actionGuard().beforeAction(noId).result());
    }

    // ==================== 编辑页提示 ====================

    @Test
    void editFormNoticeShowsOnlyForModeratedUsers() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("提示作者");
        long exemptId = fixture.addUser("提示豁免", ModerationSupport.GROUP_PAGE_ADMIN);

        ModerationFakes.FakeEditFormContext context = new ModerationFakes.FakeEditFormContext();
        context.viewer = fixture.viewer(authorId);
        String notice = fixture.editFormField().notice(context);
        assertNotNull(notice);
        assertTrue(notice.contains("moderation-edit-notice"), notice);
        assertTrue(notice.contains("审核"), notice);
        assertNull(fixture.editFormField().html(context), "审核不插入表单字段");

        context.viewer = fixture.viewer(exemptId);
        assertNull(fixture.editFormField().notice(context), "豁免组不提示");

        fixture.enabled(false);
        context.viewer = fixture.viewer(authorId);
        assertNull(fixture.editFormField().notice(context), "开关关闭不提示");
    }

    // ==================== 页面提示条 / 待审创建页 ====================

    @Test
    void bannerIsShownToSubmitterAndReviewerOnly() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("提示条作者");
        long otherId = fixture.addUser("路人");
        long reviewerId = fixture.addUser("提示条核查员", ModerationSupport.GROUP_CHECKUSER);
        String title = "提示条页";
        fixture.addPage(title, "已通过正文");
        fixture.service().submitEdit(fixture.viewer(authorId), fixture.pageIds.get(title), 1L, title,
                "待审正文", "", false, false, "");

        ModerationFakes.FakePageDisplayContext context = new ModerationFakes.FakePageDisplayContext();
        context.title = title;
        context.pageId = fixture.pageIds.get(title);

        context.viewer = fixture.viewer(authorId);
        String banner = fixture.pageDisplayHook().beforeContent(context);
        assertNotNull(banner);
        assertTrue(banner.contains("moderation-note"), banner);
        assertTrue(banner.contains("1 条待审更改"), banner);
        assertTrue(banner.contains("查看审核队列"), banner);

        context.viewer = fixture.viewer(reviewerId);
        assertNotNull(fixture.pageDisplayHook().beforeContent(context), "审核员也能看到提示条");

        context.viewer = fixture.viewer(otherId);
        assertNull(fixture.pageDisplayHook().beforeContent(context), "其他人无感（看到的是已通过版本）");

        // 旧版本视图不显示提示条
        context.viewer = fixture.viewer(authorId);
        context.oldRevision = true;
        assertNull(fixture.pageDisplayHook().beforeContent(context));

        // 保存后重定向带 ?moderation=pending：显示「已提交」提示
        context.oldRevision = false;
        context.query = Map.of("moderation", "pending");
        String submitted = fixture.pageDisplayHook().beforeContent(context);
        assertNotNull(submitted);
        assertTrue(submitted.contains("moderation-submitted"), submitted);
        assertTrue(submitted.contains("等待审核"), submitted);

        // 开关关闭：什么都不显示
        fixture.enabled(false);
        assertNull(fixture.pageDisplayHook().beforeContent(context));
    }

    @Test
    void pendingCreationPageIsClaimedForSubmitterAndReviewer() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("待审创建作者");
        long otherId = fixture.addUser("待审创建路人");
        long reviewerId = fixture.addUser("待审创建核查员", ModerationSupport.GROUP_CHECKUSER);
        String title = "待审创建页";
        fixture.service().submitEdit(fixture.viewer(authorId), null, null, title,
                "'''待审'''正文", "初次提交", false, false, "");

        ModerationFakes.FakePageDisplayContext context = new ModerationFakes.FakePageDisplayContext();
        context.title = title;

        context.viewer = fixture.viewer(authorId);
        String html = fixture.pageDisplayHook().renderMissingPage(context);
        assertNotNull(html);
        assertTrue(html.contains("待审的创建"), html);
        assertTrue(html.contains("你的创建正在等待审核"), html);
        assertTrue(html.contains("<div class=\"rendered\">'''待审'''正文</div>"), "预览走 WikiText 桥");
        assertTrue(html.contains("?title="), html);

        context.viewer = fixture.viewer(reviewerId);
        String reviewerHtml = fixture.pageDisplayHook().renderMissingPage(context);
        assertNotNull(reviewerHtml);
        assertFalse(reviewerHtml.contains("你的创建正在等待审核"), "审核员看到的是通用说明");

        context.viewer = fixture.viewer(otherId);
        assertNull(fixture.pageDisplayHook().renderMissingPage(context), "其他人照常 404");

        // 已存在的页面 / 没有待审创建时不认领
        context.viewer = fixture.viewer(authorId);
        context.title = "别的页";
        assertNull(fixture.pageDisplayHook().renderMissingPage(context));

        // 开关关闭：不认领
        fixture.enabled(false);
        context.title = title;
        assertNull(fixture.pageDisplayHook().renderMissingPage(context));
    }

    private static void assertArrayEqualsAtPending(ModerationFakes.Fixture fixture,
                                                   WikiPendingChange change, byte[] expected) {
        byte[] staged = fixture.pendingFiles.read(change.getFileStoredName()).orElseThrow();
        assertArrayEquals(expected, staged);
    }
}
