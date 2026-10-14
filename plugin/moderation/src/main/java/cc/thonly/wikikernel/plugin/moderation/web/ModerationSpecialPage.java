package cc.thonly.wikikernel.plugin.moderation.web;

import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiDiff;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:审核} / {@code Special:Moderation}（仿 MediaWiki Moderation 扩展；原 core 的
 * {@code special/ModerationSpecialPage} 迁入，模板随之放到插件 jar 的 {@code templates/} 下）：
 * <ul>
 *     <li>列出待审 / 已通过 / 已拒绝的变更（页面编辑 / 创建、文件上传、文件删除）；</li>
 *     <li>编辑显示「当前版本 → 待审内容」的行级差异（差异算法走 core 的 {@code WikiDiff} 桥）；</li>
 *     <li>上传可预览图片（{@code /api/moderation/pending-file/{id}}，只有审核员能取）；</li>
 *     <li>通过 / 拒绝由 {@code POST /api/moderation/approve|reject} 完成；只有核查员 / 管理员可用。</li>
 * </ul>
 */
public class ModerationSpecialPage implements SpecialPage {

    public static final String PARAM_STATUS = "status";
    public static final String PARAM_TYPE = "type";
    public static final String PARAM_TITLE = "title";

    /** 一次最多展示多少条。 */
    private static final int LIMIT = 100;

    private static final List<String> STATUSES = List.of(
            WikiPendingChange.STATUS_PENDING, WikiPendingChange.STATUS_APPROVED, WikiPendingChange.STATUS_REJECTED);

    private static final List<String> TYPES = List.of(
            WikiPendingChange.TYPE_EDIT, WikiPendingChange.TYPE_UPLOAD, WikiPendingChange.TYPE_FILE_DELETE);

    private final ModerationService service;
    private final ModerationSupport support;
    private final WikiPageStore wikiPageStore;
    private final WikiDiff wikiDiff;
    private final UserStore userStore;

    public ModerationSpecialPage(ModerationService service, ModerationSupport support, WikiPageStore wikiPageStore,
                                 WikiDiff wikiDiff, UserStore userStore) {
        this.service = service;
        this.support = support;
        this.wikiPageStore = wikiPageStore;
        this.wikiDiff = wikiDiff;
        this.userStore = userStore;
    }

    @Override
    public String name() {
        return "审核";
    }

    @Override
    public String displayName() {
        return support.message("special.moderation");
    }

    @Override
    public List<String> aliases() {
        return List.of("Moderation");
    }

    @Override
    public String template() {
        return "special/moderation";
    }

    @Override
    public String category() {
        return SpecialPageCategories.MAINTENANCE;
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser viewer = request.user();
        boolean canReview = isReviewer(viewer);
        model.put("canReview", canReview);
        model.put("enabled", service.enabled());
        if (!canReview) {
            return;
        }
        if (!service.enabled()) {
            return;
        }

        String status = normalizeStatus(request.parameters().get(PARAM_STATUS));
        String type = normalizeType(request.parameters().get(PARAM_TYPE));
        String titleFilter = Strings.trimToEmpty(request.parameters().get(PARAM_TITLE));

        List<WikiPendingChange> changes = service.list(status, type, titleFilter, LIMIT);
        model.put("rows", rows(changes));
        model.put("titleFilter", titleFilter);
        model.put("typeFilter", type);

        // 状态页签（保留类型 / 标题过滤）
        String pagePath = ModerationSupport.pagePath("特殊:" + name());
        List<Map<String, Object>> tabs = new ArrayList<>();
        tabs.add(tab(pagePath, "", support.message("moderation.status.all"), status, type, titleFilter));
        for (String candidate : STATUSES) {
            tabs.add(tab(pagePath, candidate,
                    support.message("moderation.status." + candidate, candidate), status, type, titleFilter));
        }
        model.put("tabs", tabs);
    }

    private boolean isReviewer(WikiUser user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return false;
        }
        Set<String> groups = userStore == null ? Set.of() : userStore.groups(user.getId());
        return ModerationSupport.isReviewer(groups);
    }

    /** 一行待审变更 → 模板模型（含编辑差异 / 上传预览信息）。 */
    private List<Map<String, Object>> rows(List<WikiPendingChange> changes) {
        if (changes.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (WikiPendingChange change : changes) {
            if (change.getUserId() != null) {
                userIds.add(change.getUserId());
            }
            if (change.getReviewerId() != null) {
                userIds.add(change.getReviewerId());
            }
        }
        Map<Long, String> names = userStore == null ? Map.of() : userStore.names(userIds);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiPendingChange change : changes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", change.getId());
            row.put("changeType", change.getChangeType());
            row.put("typeLabel", support.message("moderation.type." + change.getChangeType(),
                    change.getChangeType()));
            row.put("title", change.getTitle());
            row.put("titleUrl", ModerationSupport.pagePath(change.getTitle()));
            String authorName = change.getUserId() == null ? null : names.get(change.getUserId());
            row.put("author", authorName == null
                    ? (change.getUserId() == null
                    ? support.message("common.anonymous") : "#" + change.getUserId())
                    : authorName);
            // 有用户名时链到用户页（匿名 / 找不到用户名时不链接）
            row.put("authorUrl", authorName == null ? null : ModerationSupport.pagePath("用户:" + authorName));
            row.put("timeText", support.formatTime(change.getCreateAt()));
            row.put("comment", Strings.trimToEmpty(change.getComment()));
            row.put("status", change.getStatus());
            row.put("statusLabel", support.message("moderation.status." + change.getStatus(),
                    change.getStatus()));
            if (!WikiPendingChange.STATUS_PENDING.equals(change.getStatus())) {
                String reviewerName = change.getReviewerId() == null ? null : names.get(change.getReviewerId());
                row.put("reviewer", reviewerName == null
                        ? (change.getReviewerId() == null ? null : "#" + change.getReviewerId())
                        : reviewerName);
                row.put("reviewerUrl", reviewerName == null ? null
                        : ModerationSupport.pagePath("用户:" + reviewerName));
                row.put("reviewedAtText", support.formatTime(change.getReviewedAt()));
                row.put("reviewComment", Strings.trimToEmpty(change.getReviewComment()));
            }
            switch (Strings.trimToEmpty(change.getChangeType())) {
                case WikiPendingChange.TYPE_EDIT -> {
                    row.put("isNew", change.getPageId() == null);
                    String current = change.getPageId() == null ? ""
                            : wikiPageStore.content(change.getPageId()).orElse("");
                    row.put("diffRows", wikiDiff.rows(current, Strings.trimToEmpty(change.getContent())));
                }
                case WikiPendingChange.TYPE_UPLOAD -> {
                    row.put("fileName", Strings.trimToEmpty(change.getFileName()));
                    row.put("fileSizeText", ModerationSupport.sizeText(change.getFileSize()));
                    row.put("isImage", ModerationSupport.isImageName(change.getFileName()));
                    row.put("previewUrl", "/api/moderation/pending-file/" + change.getId());
                    row.put("fileDescription", Strings.trimToEmpty(change.getFileDescription()));
                }
                case WikiPendingChange.TYPE_FILE_DELETE -> {
                    row.put("fileName", Strings.trimToEmpty(change.getFileName()));
                    row.put("hardDelete", Boolean.TRUE.equals(change.getHardDelete()));
                }
                default -> {
                    // 未知类型：仅展示基本信息
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> tab(String pagePath, String status, String label,
                                    String selectedStatus, String type, String title) {
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("label", label);
        tab.put("count", service.countByStatus(status));
        tab.put("selected", status.equals(selectedStatus));
        tab.put("url", filterUrl(pagePath, status, type, title));
        return tab;
    }

    private static String filterUrl(String pagePath, String status, String type, String title) {
        StringBuilder url = new StringBuilder(pagePath);
        List<String> params = new ArrayList<>();
        if (!status.isEmpty()) {
            params.add("status=" + status);
        }
        if (!type.isEmpty()) {
            params.add("type=" + type);
        }
        if (!title.isEmpty()) {
            params.add("title=" + ModerationSupport.encodeQuery(title));
        }
        if (!params.isEmpty()) {
            url.append('?').append(String.join("&", params));
        }
        return url.toString();
    }

    /** 状态：pending / approved / rejected 原样；其余（含 all / 空）→ 空 = 全部。 */
    private static String normalizeStatus(String value) {
        String status = Strings.trimToEmpty(value).toLowerCase(Locale.ROOT);
        return STATUSES.contains(status) ? status : "";
    }

    private static String normalizeType(String value) {
        String type = Strings.trimToEmpty(value).toLowerCase(Locale.ROOT);
        return TYPES.contains(type) ? type : "";
    }
}
