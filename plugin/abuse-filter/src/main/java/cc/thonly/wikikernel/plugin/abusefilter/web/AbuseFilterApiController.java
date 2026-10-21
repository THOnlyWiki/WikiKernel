package cc.thonly.wikikernel.plugin.abusefilter.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseCondition;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 滥用过滤器 API（插件里的 {@code @RestController}，路径与迁移前完全一致）：
 * 保存 / 恢复过滤器（只有管理员 / 行政员）、测试过滤器、隐藏滥用日志条目。
 * <p>
 * 迁移前在 core 的 {@code controller/AbuseFilterApiController}；现在由插件的 Spring 子容器注册，
 * 插件未加载时这些接口不存在（404）。
 */
@RestController
@RequestMapping("/api")
public class AbuseFilterApiController {

    private final AbuseFilterService service;
    private final AbuseFilterSupport support;
    private final CurrentUser currentUser;

    /** 预览信息页正文用；插件独立测试 / 无页面桥时为空。 */
    @Autowired(required = false)
    private WikiPageStore pageStore;

    public AbuseFilterApiController(AbuseFilterService service, AbuseFilterSupport support,
                                    CurrentUser currentUser) {
        this.service = service;
        this.support = support;
        this.currentUser = currentUser;
    }

    /** 保存 / 新建过滤器；条件语法错误返回 400 与具体原因。 */
    @PostMapping("/abuse-filters/save")
    public AjaxResult<Long> save(@RequestParam Map<String, String> params,
                                 @RequestParam(value = "actions", required = false) List<String> actions) {
        AjaxResult<Long> denied = requireManager();
        if (denied != null) {
            return denied;
        }
        try {
            long saved = service.save(parseLong(params.get("id")), new AbuseFilterService.FilterForm(
                    params.get("description"), params.get("pattern"),
                    actions == null ? "" : String.join(",", actions),
                    params.get("tags"),
                    "1".equals(params.get("enabled")),
                    "1".equals(params.get("deleted")),
                    "1".equals(params.get("hidden")),
                    parseInt(params.get("throttleCount")),
                    parseInt(params.get("throttlePeriod")),
                    params.get("throttleGroup"),
                    params.get("comment"),
                    params.get("notes"),
                    params.get("warnMessage"), params.get("warnPage"),
                    params.get("disallowMessage"), params.get("disallowPage"),
                    "1".equals(params.get("blockTalk")),
                    parseDefaultInt(params.get("blockAnonDays"), 3),
                    parseDefaultInt(params.get("blockUserDays"), 3)), actorId());
            return AjaxResult.success(support.message("abusefilter.saved"), saved);
        } catch (AbuseCondition.SyntaxException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("abusefilter.patternError", null, syntaxDetail(exception)));
        } catch (IllegalArgumentException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, exception.getMessage());
        }
    }

    /** 语法检查（保存前预览）：只编译，不写数据库。 */
    @PostMapping("/abuse-filters/check")
    public AjaxResult<Map<String, Object>> check(
            @RequestParam(value = "pattern", required = false) String pattern) {
        AjaxResult<Map<String, Object>> denied = requireManager();
        if (denied != null) {
            return denied;
        }
        try {
            AbuseCondition.compile(pattern);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", true);
            return AjaxResult.success(support.message("abusefilter.syntaxOK"), data);
        } catch (AbuseCondition.SyntaxException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("abusefilter.patternError", null, syntaxDetail(exception)));
        }
    }

    /** 预览警告 / 阻止文案：消息键或字面文案 → 指定页面最新正文 → 过滤器说明。 */
    @PostMapping("/abuse-filters/message-preview")
    public AjaxResult<Map<String, Object>> messagePreview(
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "fallback", required = false) String fallback) {
        AjaxResult<Map<String, Object>> denied = requireManager();
        if (denied != null) {
            return denied;
        }
        String text = Strings.trimToEmpty(message);
        String source = "message";
        String pageTitle = AbuseFilterSupport.messagePageTitle(page);
        if (!text.isEmpty()) {
            String resolved = support.messageOrNull(text);
            if (resolved != null) {
                text = resolved;
                source = "message-key";
            }
        } else {
            if (!pageTitle.isEmpty() && pageStore != null) {
                try {
                    text = pageStore.byTitle(pageTitle)
                            .flatMap(snapshot -> pageStore.content(snapshot.id()))
                            .map(String::trim)
                            .orElse("");
                    source = "page";
                } catch (RuntimeException exception) {
                    text = "";
                }
            }
        }
        if (text.isEmpty()) {
            text = Strings.trimToEmpty(fallback);
            source = "fallback";
        }
        // 与运行时一致：$1 = 过滤器说明
        text = text.replace("$1", Strings.trimToEmpty(fallback));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        data.put("source", source);
        data.put("page", pageTitle);
        return AjaxResult.success(text.isEmpty()
                ? support.message("abusefilter.previewEmpty") : text, data);
    }

    /** 恢复到历史版本。 */
    @PostMapping("/abuse-filters/restore")
    public AjaxResult<Void> restore(@RequestParam("id") String id,
                                    @RequestParam("revision") String revision) {
        AjaxResult<Void> denied = requireManager();
        if (denied != null) {
            return denied;
        }
        boolean restored = service.restore(parseLong(id), parseLong(revision), actorId());
        return restored
                ? AjaxResult.success(support.message("abusefilter.restored"), null)
                : AjaxResult.error(AjaxCode.NOT_FOUND,
                support.message("abusefilter.historyMissing"));
    }

    /** 测试过滤器：用表达式匹配最近编辑，返回逐条结果。 */
    @PostMapping("/abuse-filters/test")
    public AjaxResult<List<Map<String, Object>>> test(
            @RequestParam(value = "pattern", required = false) String pattern,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        AjaxResult<List<Map<String, Object>>> denied = requireManager();
        if (denied != null) {
            return denied;
        }
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            long matched = 0;
            for (AbuseFilterService.TestHit hit : service.test(pattern, limit)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("revisionId", hit.revisionId());
                row.put("pageTitle", hit.pageTitle());
                row.put("userName", hit.userName());
                row.put("timeText", support.formatTime(hit.time()));
                row.put("summary", hit.summary());
                row.put("matched", hit.matched());
                rows.add(row);
                if (hit.matched()) {
                    matched++;
                }
            }
            return AjaxResult.success(support.message("abusefilter.testResults", null, matched, rows.size()), rows);
        } catch (AbuseCondition.SyntaxException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("abusefilter.patternError", null, syntaxDetail(exception)));
        }
    }

    /** 隐藏 / 显示一条滥用日志（只有管理员 / 行政员）。 */
    @PostMapping("/abuse-log/hide")
    public AjaxResult<Void> hideLog(@RequestParam("id") String id,
                                    @RequestParam(value = "hided", required = false, defaultValue = "1")
                                    String hided) {
        AjaxResult<Void> denied = requireManager();
        if (denied != null) {
            return denied;
        }
        boolean changed = service.hideLog(parseLong(id), !"0".equals(hided));
        return changed
                ? AjaxResult.success(support.message("abusefilter.logSaved"), null)
                : AjaxResult.error(AjaxCode.NOT_FOUND,
                support.message("abusefilter.logMissing"));
    }

    // ==================== 工具 ====================

    /** 语法错误详情：优先按消息键本地化（解析器同时保留中文原文作兜底）。 */
    private String syntaxDetail(AbuseCondition.SyntaxException exception) {
        if (exception.messageKey() == null) {
            return exception.getMessage();
        }
        return support.message(exception.messageKey(), exception.getMessage(), exception.args());
    }

    /** 当前修改者 ID（未登录 = null；权限判断已先拦下匿名）。 */
    private Long actorId() {
        Viewer viewer = currentUser == null ? Viewer.ANONYMOUS : currentUser.viewer();
        return viewer.loggedIn() ? viewer.id() : null;
    }

    private <T> AjaxResult<T> requireManager() {
        Viewer viewer = currentUser == null ? Viewer.ANONYMOUS : currentUser.viewer();
        if (!viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED,
                    support.message("permissions.loginRequired"));
        }
        if (!viewer.inGroup(AbuseFilterSupport.GROUP_ADMIN)
                && !viewer.inGroup(AbuseFilterSupport.GROUP_BUREAUCRAT)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN,
                    support.message("abusefilter.forbidden"));
        }
        return null;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static int parseDefaultInt(String value, int fallback) {
        try {
            return Integer.parseInt(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
