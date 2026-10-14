package cc.thonly.wikikernel.plugin.moderation.service;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.FileWriter;
import cc.thonly.wikikernel.common.service.LogWriter;
import cc.thonly.wikikernel.common.service.PageWriter;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;
import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationLogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 审核服务实现（仿 MediaWiki Moderation 扩展）——原 core 的 {@code ModerationServiceImpl} 迁入：
 * 队列读写走插件自己的 {@link ModerationStoreJdbc}（表 {@code wiki_pending_change}）、待审文件暂存走
 * {@link PendingFileStoreImpl}（插件数据目录下的 {@code pending/}）、
 * 通过待审编辑用 {@code PageWriter}（绕过表单，以**原提交人**身份生成版本，AI 标记一并保留）、
 * 通过 / 拒绝待审上传与删除用 {@code FileWriter}（core 的 {@code FileService} 仍是唯一写入方）。
 * <p>
 * 页面说明页（{@code 文件:X}）的创建、日志写入分别走 {@code PageWriter} / {@code LogWriter}。
 */
@Service
public class ModerationServiceImpl implements ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationServiceImpl.class);

    /** 通过待审变更的日志类型（{@link ModerationLogType#ID_APPROVE}）。 */
    public static final String LOG_APPROVE = ModerationLogType.ID_APPROVE;
    /** 拒绝待审变更的日志类型（{@link ModerationLogType#ID_REJECT}）。 */
    public static final String LOG_REJECT = ModerationLogType.ID_REJECT;

    private final ModerationStore store;
    private final PendingFileStore pendingFileStore;
    private final PageWriter pageWriter;
    private final FileWriter fileWriter;
    private final WikiPageStore wikiPageStore;
    private final LogWriter logWriter;
    private final ModerationSupport support;

    public ModerationServiceImpl(ModerationStore store,
                                PendingFileStore pendingFileStore,
                                PageWriter pageWriter,
                                FileWriter fileWriter,
                                WikiPageStore wikiPageStore,
                                LogWriter logWriter,
                                ModerationSupport support) {
        this.store = store;
        this.pendingFileStore = pendingFileStore;
        this.pageWriter = pageWriter;
        this.fileWriter = fileWriter;
        this.wikiPageStore = wikiPageStore;
        this.logWriter = logWriter;
        this.support = support;
    }

    @Override
    public boolean enabled() {
        return support.enabled();
    }

    @Override
    public boolean isReviewer(Viewer viewer) {
        return ModerationSupport.isReviewer(viewer);
    }

    @Override
    public boolean isExempt(Viewer viewer) {
        return ModerationSupport.isExempt(viewer);
    }

    @Override
    public boolean shouldModerate(Viewer viewer) {
        return support.shouldModerate(viewer);
    }

    // ==================== 入队 ====================

    @Override
    public WikiPendingChange submitEdit(Viewer submitter, Long pageId, Long baseRevisionId, String title,
                                        String content, String comment, boolean minor,
                                        boolean aiAssisted, String aiModel) {
        WikiPendingChange change = base(submitter, WikiPendingChange.TYPE_EDIT, title, comment);
        change.setPageId(pageId);
        change.setContent(Strings.trimToEmpty(content));
        change.setMinor(minor);
        change.setBaseRevisionId(baseRevisionId);
        change.setAiAssisted(aiAssisted);
        change.setAiModel(aiAssisted ? Strings.trimToEmpty(aiModel) : "");
        store.insert(change);
        return change;
    }

    @Override
    public WikiPendingChange submitUpload(Viewer submitter, String fileName, String description, byte[] content) {
        WikiPendingChange change = base(submitter, WikiPendingChange.TYPE_UPLOAD,
                ModerationSupport.fileTitle(fileName), support.message("files.uploadComment"));
        change.setFileName(Strings.trimToEmpty(fileName));
        change.setFileDescription(Strings.trimToEmpty(description));
        change.setFileSize(content == null ? 0L : (long) content.length);
        change.setFileStoredName(pendingFileStore.stage(fileName, content));
        store.insert(change);
        return change;
    }

    @Override
    public WikiPendingChange submitFileDelete(Viewer submitter, long fileId, String fileName,
                                              boolean hard, String reason) {
        WikiPendingChange change = base(submitter, WikiPendingChange.TYPE_FILE_DELETE,
                ModerationSupport.fileTitle(fileName), reason);
        change.setFileId(fileId);
        change.setFileName(Strings.trimToEmpty(fileName));
        change.setHardDelete(hard);
        store.insert(change);
        return change;
    }

    private WikiPendingChange base(Viewer submitter, String type, String title, String comment) {
        WikiPendingChange change = new WikiPendingChange();
        change.setChangeType(type);
        change.setTitle(Strings.trimToEmpty(title));
        change.setUserId(submitter == null || !submitter.loggedIn() ? null : submitter.id());
        change.setComment(Strings.trimToEmpty(comment));
        change.setStatus(WikiPendingChange.STATUS_PENDING);
        change.setCreateAt(LocalDateTime.now());
        change.setMinor(false);
        change.setAiAssisted(false);
        change.setAiModel("");
        change.setHardDelete(false);
        return change;
    }

    // ==================== 查询 ====================

    @Override
    public Optional<byte[]> pendingFileData(long id) {
        WikiPendingChange change = store.find(id).orElse(null);
        if (change == null || !WikiPendingChange.TYPE_UPLOAD.equals(change.getChangeType())
                || Strings.isBlank(change.getFileStoredName())) {
            return Optional.empty();
        }
        return pendingFileStore.read(change.getFileStoredName());
    }

    @Override
    public long pendingCount(String title) {
        if (Strings.isBlank(title)) {
            return 0;
        }
        return store.countForPage(title, WikiPendingChange.STATUS_PENDING);
    }

    @Override
    public long countByStatus(String status) {
        return store.count(status);
    }

    @Override
    public List<WikiPendingChange> pendingForPage(String title) {
        if (Strings.isBlank(title)) {
            return List.of();
        }
        return store.forPage(title);
    }

    @Override
    public List<WikiPendingChange> list(String status, String type, String titleLike, int limit) {
        return store.list(status, type, titleLike, limit);
    }

    @Override
    public Optional<WikiPendingChange> find(long id) {
        return store.find(id);
    }

    // ==================== 审核 ====================

    @Override
    public ReviewResult approve(Viewer reviewer, long id, String comment) {
        WikiPendingChange change = store.find(id).orElse(null);
        if (change == null) {
            return ReviewResult.NOT_FOUND;
        }
        if (!WikiPendingChange.STATUS_PENDING.equals(change.getStatus())) {
            return ReviewResult.ALREADY_REVIEWED;
        }
        // 以原提交人身份应用变更（提交人已被删除时退回审核人，与迁移前一致）
        Long reviewerId = reviewer == null || !reviewer.loggedIn() ? null : reviewer.id();
        Long authorId = change.getUserId() != null ? change.getUserId() : reviewerId;
        if (authorId == null) {
            return ReviewResult.APPLY_FAILED;
        }
        try {
            switch (Strings.trimToEmpty(change.getChangeType())) {
                case WikiPendingChange.TYPE_EDIT -> {
                    if (!applyEdit(authorId, change)) {
                        return ReviewResult.APPLY_FAILED;
                    }
                }
                case WikiPendingChange.TYPE_UPLOAD -> {
                    if (!applyUpload(authorId, change)) {
                        return ReviewResult.APPLY_FAILED;
                    }
                }
                case WikiPendingChange.TYPE_FILE_DELETE -> {
                    if (change.getFileId() == null
                            || !fileWriter.delete(authorId, change.getFileId(),
                                    Boolean.TRUE.equals(change.getHardDelete()))) {
                        return ReviewResult.APPLY_FAILED;
                    }
                }
                default -> {
                    return ReviewResult.APPLY_FAILED;
                }
            }
        } catch (RuntimeException exception) {
            log.warn("审核：应用待审变更 #{} 失败：{}", change.getId(), exception.toString());
            return ReviewResult.APPLY_FAILED;
        }
        mark(change, reviewerId, WikiPendingChange.STATUS_APPROVED, comment);
        logWriter.record(LOG_APPROVE, reviewerId, change.getTitle(),
                change.getUserId(), detailOf(change, comment));
        return ReviewResult.OK;
    }

    /**
     * 通过页面编辑 / 创建：以**原提交人**身份写新版本（绕过网页表单的 {@code PageWriter}），
     * 原提交是 AI 代理协助的（历史队列项 / 未来的 AI 排队）时同样保留 AI 水印。
     */
    private boolean applyEdit(Long authorId, WikiPendingChange change) {
        PageWriter.Result result = pageWriter.save(new PageWriter.Request(
                authorId, change.getTitle(), Strings.trimToEmpty(change.getContent()),
                Strings.trimToEmpty(change.getComment()),
                Boolean.TRUE.equals(change.getMinor()), false, null, null,
                Boolean.TRUE.equals(change.getAiAssisted()),
                Boolean.TRUE.equals(change.getAiAssisted()) ? Strings.trimToEmpty(change.getAiModel()) : null));
        return result.saved();
    }

    /** 通过上传：把暂存文件写入正式存储，首次上传顺带创建说明页（均以原提交人身份）。 */
    private boolean applyUpload(Long authorId, WikiPendingChange change) {
        byte[] content = pendingFileStore.read(change.getFileStoredName()).orElse(null);
        if (content == null) {
            log.warn("审核：待审上传 #{} 的暂存文件已丢失（{}）", change.getId(), change.getFileStoredName());
            return false;
        }
        FileWriter.Result stored = fileWriter.store(authorId, Strings.trimToEmpty(change.getFileName()),
                Strings.trimToEmpty(change.getFileDescription()), content);
        if (!stored.ok()) {
            log.warn("审核：写入文件 {} 失败：{}", change.getFileName(), stored.error());
            return false;
        }
        pendingFileStore.delete(change.getFileStoredName());
        String descriptionTitle = ModerationSupport.fileTitle(stored.name());
        if (wikiPageStore.byTitle(descriptionTitle).isEmpty()) {
            pageWriter.save(PageWriter.Request.of(authorId, descriptionTitle,
                    Strings.trimToEmpty(change.getFileDescription()),
                    support.message("files.uploadComment")));
        }
        return true;
    }

    @Override
    public ReviewResult reject(Viewer reviewer, long id, String comment) {
        WikiPendingChange change = store.find(id).orElse(null);
        if (change == null) {
            return ReviewResult.NOT_FOUND;
        }
        if (!WikiPendingChange.STATUS_PENDING.equals(change.getStatus())) {
            return ReviewResult.ALREADY_REVIEWED;
        }
        Long reviewerId = reviewer == null || !reviewer.loggedIn() ? null : reviewer.id();
        mark(change, reviewerId, WikiPendingChange.STATUS_REJECTED, comment);
        if (WikiPendingChange.TYPE_UPLOAD.equals(change.getChangeType())) {
            pendingFileStore.delete(change.getFileStoredName());
        }
        logWriter.record(LOG_REJECT, reviewerId, change.getTitle(),
                change.getUserId(), detailOf(change, comment));
        return ReviewResult.OK;
    }

    private void mark(WikiPendingChange change, Long reviewerId, String status, String comment) {
        change.setStatus(status);
        change.setReviewerId(reviewerId);
        change.setReviewedAt(LocalDateTime.now());
        change.setReviewComment(Strings.trimToEmpty(comment));
        store.update(change);
    }

    /** 日志详情：{@code 变更类型|审核意见}（展示文案由 {@link ModerationLogType#detailText} 本地化）。 */
    static String detailOf(WikiPendingChange change, String comment) {
        return Strings.trimToEmpty(change.getChangeType()) + "|" + Strings.trimToEmpty(comment);
    }
}
