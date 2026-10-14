package cc.thonly.wikikernel.plugin.moderation.service;

import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.FileWriter;
import cc.thonly.wikikernel.common.service.PageWriter;
import cc.thonly.wikikernel.plugin.moderation.ModerationFakes;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核服务（队列 + 通过 / 拒绝，不依赖 Spring）：
 * 豁免组 / 审核人判断、编辑入队与通过（以原提交人身份 + AI 标记保留）、拒绝、上传暂存 / 通过 / 拒绝、
 * 删除审核、队列过滤与计数、开关关闭时不拦截。
 */
class ModerationServiceTest {

    // ==================== 权限语义 ====================

    @Test
    void reviewerAndExemptGroupsFollowTheMigratedSemantics() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long checkuser = fixture.addUser("核查员甲", ModerationSupport.GROUP_CHECKUSER);
        long admin = fixture.addUser("管理员甲", ModerationSupport.GROUP_ADMIN);
        long plain = fixture.addUser("普通用户甲");
        long exempt = fixture.addUser("豁免用户甲", ModerationSupport.GROUP_MODERATION_EXEMPT);

        ModerationService service = fixture.service();
        assertTrue(service.isReviewer(fixture.viewer(checkuser)));
        assertTrue(service.isReviewer(fixture.viewer(admin)));
        assertFalse(service.isReviewer(fixture.viewer(plain)));
        assertFalse(service.isReviewer(Viewer.ANONYMOUS));

        assertTrue(service.isExempt(fixture.viewer(exempt)));
        assertFalse(service.isExempt(fixture.viewer(plain)));
        assertFalse(service.isExempt(Viewer.ANONYMOUS));

        assertTrue(service.shouldModerate(fixture.viewer(plain)));
        assertFalse(service.shouldModerate(fixture.viewer(exempt)));
        assertFalse(service.shouldModerate(Viewer.ANONYMOUS), "匿名由登录要求拦截，不排队");

        fixture.enabled(false);
        assertFalse(service.shouldModerate(fixture.viewer(plain)), "开关关闭时不拦截");
        assertFalse(service.enabled());
    }

    // ==================== 编辑：入队 → 通过 / 拒绝 ====================

    @Test
    void approveEditWritesAsOriginalAuthorAndKeepsAiMarker() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("通过作者");
        long reviewerId = fixture.addUser("通过核查员", ModerationSupport.GROUP_CHECKUSER);
        String title = "通过页";
        fixture.addPage(title, "旧正文");

        WikiPendingChange change = fixture.service().submitEdit(fixture.viewer(authorId),
                fixture.pageIds.get(title), 3L, title, "新正文", "提交摘要", true, true, "mock/model");
        assertTrue(change.getId() > 0);
        assertEquals(WikiPendingChange.STATUS_PENDING, change.getStatus());
        assertTrue(Boolean.TRUE.equals(change.getMinor()));
        assertTrue(Boolean.TRUE.equals(change.getAiAssisted()));

        assertEquals(ModerationService.ReviewResult.OK,
                fixture.service().approve(fixture.viewer(reviewerId), change.getId(), "通过"));

        // 以原提交人身份写版本（PageWriter 桥），小编辑 / AI 水印一并保留
        assertEquals(1, fixture.pageWriter.requests.size());
        PageWriter.Request request = fixture.pageWriter.requests.get(0);
        assertEquals(authorId, request.actorUserId());
        assertEquals(title, request.title());
        assertEquals("新正文", request.content());
        assertEquals("提交摘要", request.summary());
        assertTrue(request.minor());
        assertTrue(request.aiAssisted());
        assertEquals("mock/model", request.aiModel());

        WikiPendingChange reviewed = fixture.store.find(change.getId()).orElseThrow();
        assertEquals(WikiPendingChange.STATUS_APPROVED, reviewed.getStatus());
        assertEquals(reviewerId, reviewed.getReviewerId());
        assertNotNull(reviewed.getReviewedAt());
        assertEquals("通过", reviewed.getReviewComment());
        // 日志：moderation|<审核人>|<标题>|<提交人>|edit|意见
        assertEquals(List.of("moderation|" + reviewerId + "|" + title + "|" + authorId + "|edit|通过"),
                fixture.logWriter.logs);

        // 重复审核不再应用
        assertEquals(ModerationService.ReviewResult.ALREADY_REVIEWED,
                fixture.service().approve(fixture.viewer(reviewerId), change.getId(), "再来一次"));
        assertEquals(1, fixture.pageWriter.requests.size());
        assertEquals(ModerationService.ReviewResult.NOT_FOUND,
                fixture.service().approve(fixture.viewer(reviewerId), 999L, ""));
    }

    @Test
    void rejectDiscardsTheChangeWithoutApplyingAnything() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("拒绝作者");
        long reviewerId = fixture.addUser("拒绝核查员", ModerationSupport.GROUP_CHECKUSER);
        String title = "拒绝页";
        fixture.addPage(title, "甲甲甲");

        WikiPendingChange change = fixture.service().submitEdit(fixture.viewer(authorId),
                fixture.pageIds.get(title), 1L, title, "乙乙乙", "想改", false, false, "");
        assertEquals(ModerationService.ReviewResult.OK,
                fixture.service().reject(fixture.viewer(reviewerId), change.getId(), "不合适"));

        assertTrue(fixture.pageWriter.requests.isEmpty(), "拒绝不写任何版本");
        WikiPendingChange reviewed = fixture.store.find(change.getId()).orElseThrow();
        assertEquals(WikiPendingChange.STATUS_REJECTED, reviewed.getStatus());
        assertEquals(reviewerId, reviewed.getReviewerId());
        assertEquals("不合适", reviewed.getReviewComment());
        assertEquals(List.of("moderationreject|" + reviewerId + "|" + title + "|" + authorId + "|edit|不合适"),
                fixture.logWriter.logs);
        assertEquals(ModerationService.ReviewResult.ALREADY_REVIEWED,
                fixture.service().reject(fixture.viewer(reviewerId), change.getId(), ""));
    }

    @Test
    void approveFallsBackToTheReviewerWhenTheAuthorIsGone() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long reviewerId = fixture.addUser("兜底核查员", ModerationSupport.GROUP_CHECKUSER);
        // 匿名提交（迁移前的 userId = null 队列项 / 提交人已被删除）：以审核人身份应用
        WikiPendingChange change = fixture.service().submitEdit(Viewer.ANONYMOUS,
                null, null, "作者已删除页", "正文", "", false, false, "");

        assertEquals(ModerationService.ReviewResult.OK,
                fixture.service().approve(fixture.viewer(reviewerId), change.getId(), ""));
        assertEquals(reviewerId, fixture.pageWriter.requests.get(0).actorUserId());
        assertEquals(reviewerId, fixture.store.find(change.getId()).orElseThrow().getReviewerId());
    }

    @Test
    void applyFailureIsReportedAndTheChangeStaysPending() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long reviewerId = fixture.addUser("失败核查员", ModerationSupport.GROUP_CHECKUSER);
        WikiPendingChange change = fixture.service().submitEdit(fixture.viewer(77L),
                null, null, "作者不存在页", "正文", "", false, false, "");
        fixture.pageWriter.missingUsers.add(77L);

        // 提交人已被删除、审核人也不存在 → APPLY_FAILED，状态保持待审
        assertEquals(ModerationService.ReviewResult.APPLY_FAILED,
                fixture.service().approve(null, change.getId(), ""));
        assertEquals(WikiPendingChange.STATUS_PENDING,
                fixture.store.find(change.getId()).orElseThrow().getStatus());

        // 未知变更
        assertEquals(ModerationService.ReviewResult.NOT_FOUND,
                fixture.service().approve(fixture.viewer(reviewerId), 1234L, ""));
    }

    // ==================== 上传 ====================

    @Test
    void uploadIsStagedApprovedAndRejected() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("上传审核作者");
        long reviewerId = fixture.addUser("上传审核核查员", ModerationSupport.GROUP_CHECKUSER);
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 9, 9};

        WikiPendingChange approved = fixture.service().submitUpload(fixture.viewer(authorId),
                "审核上传.png", "说明文字", png);
        assertArrayEquals(png, fixture.service().pendingFileData(approved.getId()).orElseThrow());
        assertTrue(fixture.fileWriter.stored.isEmpty(), "通过前不写正式存储");

        assertEquals(ModerationService.ReviewResult.OK,
                fixture.service().approve(fixture.viewer(reviewerId), approved.getId(), ""));
        assertEquals(List.of("审核上传.png"), fixture.fileWriter.stored);
        assertArrayEquals(png, fixture.fileWriter.contents.get("审核上传.png"));
        assertTrue(fixture.pendingFiles.files.isEmpty(), "通过后清理暂存");
        // 首次上传顺带创建说明页（文件不存在时）
        assertEquals(1, fixture.pageWriter.requests.size());
        assertEquals("文件:审核上传.png", fixture.pageWriter.requests.get(0).title());
        assertEquals("说明文字", fixture.pageWriter.requests.get(0).content());
        assertEquals(authorId, fixture.pageWriter.requests.get(0).actorUserId());

        // 拒绝：清掉暂存，不写文件
        WikiPendingChange rejected = fixture.service().submitUpload(fixture.viewer(authorId),
                "审核拒绝.png", "", png);
        assertEquals(ModerationService.ReviewResult.OK,
                fixture.service().reject(fixture.viewer(reviewerId), rejected.getId(), "不合适"));
        assertEquals(1, fixture.fileWriter.stored.size());
        assertTrue(fixture.pendingFiles.files.isEmpty(), "拒绝后清理暂存");
        assertTrue(fixture.service().pendingFileData(rejected.getId()).isEmpty());
    }

    @Test
    void uploadApprovalFailsWhenTheStagedFileIsGone() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("丢失文件作者");
        long reviewerId = fixture.addUser("丢失文件核查员", ModerationSupport.GROUP_CHECKUSER);

        WikiPendingChange change = fixture.service().submitUpload(fixture.viewer(authorId),
                "丢失.png", "", new byte[]{1, 2, 3});
        fixture.pendingFiles.files.clear();

        assertEquals(ModerationService.ReviewResult.APPLY_FAILED,
                fixture.service().approve(fixture.viewer(reviewerId), change.getId(), ""));
        assertEquals(WikiPendingChange.STATUS_PENDING,
                fixture.store.find(change.getId()).orElseThrow().getStatus());
        assertTrue(fixture.fileWriter.stored.isEmpty());
    }

    @Test
    void uploadStoreFailureIsReported() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("写入失败作者");
        long reviewerId = fixture.addUser("写入失败核查员", ModerationSupport.GROUP_CHECKUSER);
        WikiPendingChange change = fixture.service().submitUpload(fixture.viewer(authorId),
                "太大.png", "", new byte[]{1});
        fixture.fileWriter.failStore = true;

        assertEquals(ModerationService.ReviewResult.APPLY_FAILED,
                fixture.service().approve(fixture.viewer(reviewerId), change.getId(), ""));
    }

    // ==================== 删除文件 ====================

    @Test
    void fileDeleteIsAppliedOnApproval() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("删除审核作者");
        long reviewerId = fixture.addUser("删除审核核查员", ModerationSupport.GROUP_CHECKUSER);

        WikiPendingChange soft = fixture.service().submitFileDelete(fixture.viewer(authorId),
                11L, "要删的.png", false, "清理");
        WikiPendingChange hard = fixture.service().submitFileDelete(fixture.viewer(authorId),
                12L, "彻底删的.png", true, "");

        assertEquals(ModerationService.ReviewResult.OK,
                fixture.service().approve(fixture.viewer(reviewerId), soft.getId(), ""));
        assertEquals(ModerationService.ReviewResult.OK,
                fixture.service().approve(fixture.viewer(reviewerId), hard.getId(), ""));
        assertEquals(List.of(authorId + ":11|false", authorId + ":12|true"), fixture.fileWriter.deleted,
                "以原提交人身份删除，hard 标记保留");
        // 文件写入桥说删不掉（文件已不存在）→ APPLY_FAILED
        WikiPendingChange missing = fixture.service().submitFileDelete(fixture.viewer(authorId),
                13L, "不存在的.png", false, "");
        fixture.fileWriter.missingUsers.add(authorId);
        assertEquals(ModerationService.ReviewResult.APPLY_FAILED,
                fixture.service().approve(fixture.viewer(reviewerId), missing.getId(), ""));
    }

    // ==================== 队列过滤与计数 ====================

    @Test
    void queueFiltersAndCounts() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("队列作者");
        long reviewerId = fixture.addUser("队列核查员", ModerationSupport.GROUP_CHECKUSER);
        ModerationService service = fixture.service();

        WikiPendingChange first = service.submitEdit(fixture.viewer(authorId), null, null,
                "队列页甲", "甲", "摘要甲", false, false, "");
        service.submitUpload(fixture.viewer(authorId), "队列图.png", "", new byte[]{1});
        WikiPendingChange third = service.submitFileDelete(fixture.viewer(authorId),
                5L, "队列删.png", false, "");
        service.reject(fixture.viewer(reviewerId), third.getId(), "不删");

        // 统计
        assertEquals(2, service.countByStatus(WikiPendingChange.STATUS_PENDING));
        assertEquals(1, service.countByStatus(WikiPendingChange.STATUS_REJECTED));
        assertEquals(3, service.countByStatus(""));
        assertEquals(0, service.countByStatus(WikiPendingChange.STATUS_APPROVED));

        // 列表：状态 / 类型 / 标题片段过滤（新的在前）
        assertEquals(List.of(WikiPendingChange.TYPE_UPLOAD, WikiPendingChange.TYPE_EDIT),
                service.list(WikiPendingChange.STATUS_PENDING, "", "", 10).stream()
                        .map(WikiPendingChange::getChangeType).toList());
        assertEquals(1, service.list("", WikiPendingChange.TYPE_UPLOAD, "", 10).size());
        assertEquals(1, service.list("", "", "队列页甲", 10).size());
        assertEquals(3, service.list("", "", "队列", 10).size(), "标题片段匹配（文件页标题含「队列」）");
        assertEquals(1, service.list(WikiPendingChange.STATUS_REJECTED, "", "", 10).size());
        assertEquals(1, service.list("", "", "", 1).size(), "limit 生效");
        assertTrue(service.list("", "", "不存在的标题", 10).isEmpty());

        // 页面维度
        assertEquals(1, service.pendingCount("队列页甲"));
        assertEquals(0, service.pendingCount("没有待审的页"));
        assertEquals(0, service.pendingCount(""));
        assertEquals(List.of(first.getId()), service.pendingForPage("队列页甲").stream()
                .map(WikiPendingChange::getId).toList());

        // 待审文件的预览数据只对上传类型有
        assertTrue(service.pendingFileData(first.getId()).isEmpty());
        assertTrue(service.find(first.getId()).isPresent());
    }

    @Test
    void queueTrimsTitlesAndDefaultsFields() {
        ModerationFakes.Fixture fixture = new ModerationFakes.Fixture();
        long authorId = fixture.addUser("空白作者");
        WikiPendingChange change = fixture.service().submitEdit(fixture.viewer(authorId),
                null, null, "  带空格标题  ", "正文", null, false, false, "");
        assertEquals("带空格标题", change.getTitle());
        assertEquals("", change.getComment());
        assertEquals("", change.getAiModel());
        assertEquals(authorId, change.getUserId());
        assertEquals(WikiPendingChange.STATUS_PENDING, change.getStatus());
        assertFalse(Boolean.TRUE.equals(change.getMinor()));
        assertFalse(Boolean.TRUE.equals(change.getAiAssisted()));
        assertFalse(Boolean.TRUE.equals(change.getHardDelete()));
    }
}
