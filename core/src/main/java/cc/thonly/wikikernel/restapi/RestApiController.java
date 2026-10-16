package cc.thonly.wikikernel.restapi;

import cc.thonly.wikikernel.actionapi.ActionApiTokens;
import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SearchHit;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.search.SearchService;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.PageBody;
import cc.thonly.wikikernel.service.PageCacheService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MediaWiki {@code rest.php}（REST API v1）兼容层，仿 {@code /w/rest.php/v1/…}：
 * <ul>
 *     <li><b>读</b>：{@code GET /rest.php/v1/page/{title}}、{@code …/bare}、{@code …/html}、{@code …/history}、
 *     {@code GET /rest.php/v1/search/page?q=&limit=}、{@code GET /rest.php/v1/search/title?q=&limit=}；</li>
 *     <li><b>写</b>：{@code POST /rest.php/v1/page/{title}}（编辑）、{@code PUT}（创建）、
 *     {@code DELETE}（软删除）、{@code PUT …/restore}（恢复）。</li>
 * </ul>
 *
 * <b>鉴权规则（每一类都必须走，写完就按这几条自查）</b>
 * <ol>
 *     <li><b>读</b>：先 {@code AccessControlService.canRead(title, user)}，再取
 *         {@code getVisibleByTitle}（已删除 / 已隐藏都取不到）→ 无权 / 不存在统一 <b>404</b>，
 *         与网页行为一致，不泄露“存在但你不能看”的信息。</li>
 *     <li><b>写</b>：① 必须登录；② 封禁用户拒绝；③ <b>必须带 Action API 的 CSRF 令牌</b>
 *         （查询参数 {@code csrf-token} 或请求头 {@code X-CSRF-Token}）且校验通过，缺失 / 过期 / 不符
 *         一律 <b>403</b> —— 绝不因为“登录了就放行”；④ 再按 {@code edit} / {@code create} / {@code delete}
 *         权限判定（删除 / 恢复另要求删除权限）。</li>
 *     <li><b>搜索</b>：结果逐条过 {@code canRead}，无权查看的页面连标题都不出现。</li>
 * </ol>
 *
 * 响应结构与 MediaWiki 对齐（{@code id}/{@code key}/{@code title}/{@code latest}/{@code content_model}/
 * {@code license}/{@code html_url}/{@code html}/{@code revisions}/{@code pages}）；错误为
 * {@code {errorKey, message, httpCode, httpReason}}。
 * <p>
 * 已知未覆盖：{@code /revision/{id}} 系列（本移植的数据层没有「按版本号找页面」的接口，无法为它做
 * 读权限校验；宁可不提供，也不做成越权读取）。
 */
@RestController
@RequestMapping("/rest.php/v1")
public class RestApiController {

    private static final String CONTENT_MODEL = "wikitext";
    private static final String LICENSE = "CC BY 4.0";
    private static final int SEARCH_DEFAULT_LIMIT = 20;
    private static final int SEARCH_MAX_LIMIT = 100;
    private static final int HISTORY_MAX_LIMIT = 100;
    private static final String PAGE_PREFIX = "/rest.php/v1/page/";

    private final WikiPageService wikiPageService;
    private final PageCacheService pageCacheService;
    private final SearchService searchService;
    private final AccessControlService accessControlService;
    private final BlockService blockService;

    public RestApiController(WikiPageService wikiPageService,
                             PageCacheService pageCacheService,
                             SearchService searchService,
                             AccessControlService accessControlService,
                             BlockService blockService) {
        this.wikiPageService = wikiPageService;
        this.pageCacheService = pageCacheService;
        this.searchService = searchService;
        this.accessControlService = accessControlService;
        this.blockService = blockService;
    }

    // ==================== 读 ====================

    /** {@code GET /rest.php/v1/page/{title}}（标题里允许有斜杠，所以用 {@code /**} 通配再自己拆后缀）。 */
    @GetMapping("/page/**")
    public ResponseEntity<Object> page(HttpServletRequest request) {
        String path = pathAfterPrefix(request, PAGE_PREFIX);
        String suffix = "";
        String title = path;
        for (String candidate : List.of("/html", "/bare", "/history")) {
            if (path.endsWith(candidate)) {
                suffix = candidate;
                title = path.substring(0, path.length() - candidate.length());
                break;
            }
        }
        WikiUser user = UserSession.current(request);
        Optional<WikiPage> found = readable(title, user);
        if (found.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "rest-nonexistent-title", "The specified page does not exist.");
        }
        WikiPage page = found.get();
        if ("/html".equals(suffix)) {
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            PageBody body = pageCacheService.body(page, content);
            Map<String, Object> data = pageInfoBase(page);
            data.put("html", body.html());
            return ResponseEntity.ok(data);
        }
        if ("/bare".equals(suffix)) {
            return ResponseEntity.ok(pageInfoBase(page));
        }
        if ("/history".equals(suffix)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("latest", revisionReference(page));
            data.put("revisions", historyOf(page));
            return ResponseEntity.ok(data);
        }
        Map<String, Object> data = pageInfoBase(page);
        data.put("html_url", WikiUrls.pagePath(page.getTitle()));
        return ResponseEntity.ok(data);
    }

    /** {@code GET /rest.php/v1/search/page?q=&limit=} / {@code …/search/title}。 */
    @GetMapping("/search/page")
    public ResponseEntity<Object> searchPage(@RequestParam(value = "q", required = false) String query,
                                             @RequestParam(value = "limit", required = false) Integer limit,
                                             HttpServletRequest request) {
        return search(query, limit, request);
    }

    @GetMapping("/search/title")
    public ResponseEntity<Object> searchTitle(@RequestParam(value = "q", required = false) String query,
                                              @RequestParam(value = "limit", required = false) Integer limit,
                                              HttpServletRequest request) {
        return search(query, limit, request);
    }

    // ==================== 版本（revision）====================
    //
    // 鉴权同页面读接口：先按版本号反查页面（getById），页面已删除 / 隐藏 / 无权读 → 一律 404，
    // 不区分“不存在”与“不能看”；这样 /revision/{id} 不会变成绕过页面读权限的后门。

    /** 正文渲染器（不写缓存：任意旧版本渲染结果不能污染「最新版本」的页面缓存）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private cc.thonly.wikikernel.service.PageBodyRenderer pageBodyRenderer;

    /** {@code GET /rest.php/v1/revision/{id}}(+{@code /bare}、{@code /html})。 */
    @GetMapping("/revision/**")
    public ResponseEntity<Object> revision(HttpServletRequest request) {
        String path = pathAfterPrefix(request, "/rest.php/v1/revision/");
        String suffix = "";
        if (path.endsWith("/html")) {
            suffix = "html";
            path = path.substring(0, path.length() - "/html".length());
        } else if (path.endsWith("/bare")) {
            path = path.substring(0, path.length() - "/bare".length());
        }
        long revisionId;
        try {
            revisionId = Long.parseLong(path.trim());
        } catch (NumberFormatException exception) {
            return error(HttpStatus.BAD_REQUEST, "rest-invalid-revision", "The revision id must be a number.");
        }
        WikiUser user = UserSession.current(request);
        WikiRevision revision = wikiPageService.getRevision(revisionId).orElse(null);
        if (revision == null || Boolean.TRUE.equals(revision.getDeleted())) {
            return error(HttpStatus.NOT_FOUND, "rest-nonexistent-revision", "The specified revision does not exist.");
        }
        WikiPage page = wikiPageService.getById(revision.getPageId()).orElse(null);
        // 页面已删除 / 无权读 → 统一 404（与 /page/{title} 同一条规则）
        if (page == null || Boolean.TRUE.equals(page.getDeleted())
                || !accessControlService.canRead(page.getTitle(), user)) {
            return error(HttpStatus.NOT_FOUND, "rest-nonexistent-revision", "The specified revision does not exist.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", revision.getId());
        data.put("page", Map.of("id", page.getId(), "title", page.getTitle()));
        data.put("size", revision.getContent() == null ? 0 : revision.getContent().length());
        data.put("minor", false);
        data.put("timestamp", revision.getCreateAt() == null ? null : revision.getCreateAt().toString());
        data.put("comment", revision.getComment() == null ? "" : revision.getComment());
        data.put("content_model", CONTENT_MODEL);
        data.put("license", LICENSE);
        if ("html".equals(suffix)) {
            PageBody body = pageBodyRenderer.render(page.getTitle(),
                    revision.getContent() == null ? "" : revision.getContent(), revision.getId());
            data.put("html", body.html());
        }
        return ResponseEntity.ok(data);
    }

    // ==================== 写（登录 + 封禁 + CSRF 令牌 + 权限）====================

    /** {@code POST /rest.php/v1/page/{title}}：编辑（可选 {@code latest.id} 做乐观锁）。 */
    @PostMapping("/page/**")
    public ResponseEntity<Object> editPage(@RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest request) {
        String title = pathAfterPrefix(request, PAGE_PREFIX);
        Object guard = requireWrite(request, AccessAction.EDIT, title, false);
        if (guard instanceof ResponseEntity<?> failure) {
            return asResponse(failure);
        }
        WikiUser user = (WikiUser) guard;
        Map<String, Object> payload = body == null ? Map.of() : body;
        String source = stringOf(payload.get("source"));
        if (source == null) {
            return error(HttpStatus.BAD_REQUEST, "missing-source", "The 'source' field is required.");
        }
        WikiPage existing = wikiPageService.getByTitle(title).orElse(null);
        Long baseRevision = longOf(payload.get("latest"));
        if (baseRevision != null && existing != null) {
            WikiRevision latest = wikiPageService.getLatestRevision(existing.getId()).orElse(null);
            Long currentId = latest == null ? existing.getLatestRevisionId() : latest.getId();
            if (currentId != null && !currentId.equals(baseRevision)) {
                return error(HttpStatus.CONFLICT, "edit-conflict", "The page was edited by someone else.");
            }
        }
        WikiPage page = wikiPageService.editPage(user, title, source,
                Strings.trimToEmpty(stringOf(payload.get("comment"))));
        if (page == null) {
            return error(HttpStatus.CONFLICT, "edit-conflict", "The page was edited by someone else.");
        }
        return ResponseEntity.ok(pageInfo(page));
    }

    /** {@code PUT /rest.php/v1/page/{title}}：创建（已存在 → 409）；{@code …/restore} 走恢复分支。 */
    @PutMapping("/page/**")
    public ResponseEntity<Object> putPage(@RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        if (pathAfterPrefix(request, PAGE_PREFIX).endsWith("/restore")) {
            return restorePage(request);
        }
        return createPage(body, request);
    }

    private ResponseEntity<Object> createPage(Map<String, Object> body, HttpServletRequest request) {
        String title = pathAfterPrefix(request, PAGE_PREFIX);
        Object guard = requireWrite(request, AccessAction.CREATE, title, false);
        if (guard instanceof ResponseEntity<?> failure) {
            return asResponse(failure);
        }
        WikiUser user = (WikiUser) guard;
        if (wikiPageService.getByTitle(title).isPresent()) {
            return error(HttpStatus.CONFLICT, "page-exists", "The page already exists.");
        }
        Map<String, Object> payload = body == null ? Map.of() : body;
        String source = stringOf(payload.get("source"));
        if (source == null) {
            return error(HttpStatus.BAD_REQUEST, "missing-source", "The 'source' field is required.");
        }
        // 已用 getByTitle 判过存在性（存在 → 409），这里走「不存在即创建」的保存路径
        WikiPage page = wikiPageService.editPage(user, title, source,
                Strings.trimToEmpty(stringOf(payload.get("comment"))));
        if (page == null) {
            return error(HttpStatus.CONFLICT, "page-exists", "The page already exists.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(pageInfo(page));
    }

    /** {@code DELETE /rest.php/v1/page/{title}}：软删除。 */
    @DeleteMapping("/page/**")
    public ResponseEntity<Object> deletePage(HttpServletRequest request) {
        String title = pathAfterPrefix(request, PAGE_PREFIX);
        Object guard = requireWrite(request, AccessAction.DELETE, title, false);
        if (guard instanceof ResponseEntity<?> failure) {
            return asResponse(failure);
        }
        WikiPage page = wikiPageService.deletePage((WikiUser) guard, title);
        if (page == null) {
            return error(HttpStatus.NOT_FOUND, "rest-nonexistent-title", "The specified page does not exist.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", page.getTitle());
        data.put("deleted", true);
        return ResponseEntity.ok(data);
    }

    /** {@code PUT /rest.php/v1/page/{title}/restore}：恢复（同删除权限；由上面的 PUT 处理器分派）。 */
    private ResponseEntity<Object> restorePage(HttpServletRequest request) {
        String path = pathAfterPrefix(request, PAGE_PREFIX);
        String title = path.endsWith("/restore")
                ? path.substring(0, path.length() - "/restore".length()) : path;
        Object guard = requireWrite(request, AccessAction.DELETE, title, true);
        if (guard instanceof ResponseEntity<?> failure) {
            return asResponse(failure);
        }
        WikiPage page = wikiPageService.restorePage((WikiUser) guard, title);
        if (page == null) {
            return error(HttpStatus.NOT_FOUND, "rest-nonexistent-title", "The specified page does not exist.");
        }
        return ResponseEntity.ok(pageInfo(page));
    }

    // ==================== 内部 ====================

    private ResponseEntity<Object> search(String query, Integer limit, HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        int max = limit == null ? SEARCH_DEFAULT_LIMIT : Math.max(1, Math.min(limit, SEARCH_MAX_LIMIT));
        List<Map<String, Object>> pages = new ArrayList<>();
        if (!Strings.isBlank(query)) {
            for (SearchHit hit : searchService.search(query, max * 2)) {
                if (Strings.isBlank(hit.title())) {
                    continue;
                }
                WikiPage page = wikiPageService.getVisibleByTitle(hit.title()).orElse(null);
                // 逐条过读权限：无权查看的页面不出现在结果里（标题也不泄露）
                if (page == null || !accessControlService.canRead(page.getTitle(), user)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", page.getId());
                row.put("key", keyOf(page.getTitle()));
                row.put("title", page.getTitle());
                row.put("excerpt", Strings.trimToEmpty(hit.snippet()));
                row.put("matched_title", null);
                row.put("description", null);
                row.put("thumbnail", null);
                pages.add(row);
                if (pages.size() >= max) {
                    break;
                }
            }
        }
        return ResponseEntity.ok(Map.of("pages", pages));
    }

    /**
     * 写接口统一守卫：登录 → 封禁 → CSRF 令牌 → 权限。
     * 通过返回 {@link WikiUser}；任一步失败返回 {@link ResponseEntity}（调用方直接透传）。
     */
    private Object requireWrite(HttpServletRequest request, AccessAction action, String title, boolean adminOnly) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return error(HttpStatus.UNAUTHORIZED, "rest-permission-denied", "You must be logged in.");
        }
        if (blockService.currentBlock(user).isPresent()) {
            return error(HttpStatus.FORBIDDEN, "rest-permission-denied", "You are blocked from editing.");
        }
        String provided = Strings.trimToEmpty(request.getParameter("csrf-token"));
        if (provided.isEmpty()) {
            provided = Strings.trimToEmpty(request.getHeader("X-CSRF-Token"));
        }
        if (!ActionApiTokens.valid(request, ActionApiTokens.csrf(request), provided)) {
            return error(HttpStatus.FORBIDDEN, "rest-csrf-token",
                    "A valid CSRF token is required (pass the Action API csrf token as csrf-token).");
        }
        boolean allowed = adminOnly
                ? accessControlService.can(AccessAction.DELETE, title, user)
                : accessControlService.can(action, title, user);
        if (!allowed) {
            return error(HttpStatus.FORBIDDEN, "rest-permission-denied",
                    "You do not have permission to perform this action.");
        }
        return user;
    }

    /** 可读页面：先权限后可见性；不可读 / 已删除 / 不存在统一返回空（对外都是 404）。 */
    private Optional<WikiPage> readable(String title, WikiUser user) {
        String normalized = Strings.trimToEmpty(title);
        if (normalized.isEmpty() || !accessControlService.canRead(normalized, user)) {
            return Optional.empty();
        }
        return wikiPageService.getVisibleByTitle(normalized);
    }

    private Map<String, Object> pageInfo(WikiPage page) {
        Map<String, Object> data = pageInfoBase(page);
        data.put("html_url", WikiUrls.pagePath(page.getTitle()));
        return data;
    }

    private Map<String, Object> pageInfoBase(WikiPage page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", page.getId());
        data.put("key", keyOf(page.getTitle()));
        data.put("title", page.getTitle());
        data.put("latest", revisionReference(page));
        data.put("content_model", CONTENT_MODEL);
        data.put("license", LICENSE);
        return data;
    }

    private Map<String, Object> revisionReference(WikiPage page) {
        WikiRevision latest = wikiPageService.getLatestRevision(page.getId()).orElse(null);
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("id", latest == null ? page.getLatestRevisionId() : latest.getId());
        reference.put("timestamp", latest == null || latest.getCreateAt() == null
                ? null : latest.getCreateAt().toString());
        return reference;
    }

    private List<Map<String, Object>> historyOf(WikiPage page) {
        List<WikiRevision> history = wikiPageService.getHistory(page.getId());
        List<Map<String, Object>> rows = new ArrayList<>();
        int count = 0;
        for (WikiRevision revision : history) {
            if (count++ >= HISTORY_MAX_LIMIT) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", revision.getId());
            row.put("timestamp", revision.getCreateAt() == null ? null : revision.getCreateAt().toString());
            row.put("comment", revision.getComment() == null ? "" : revision.getComment());
            row.put("size", revision.getContent() == null ? 0 : revision.getContent().length());
            rows.add(row);
        }
        return rows;
    }

    private static String keyOf(String title) {
        return Strings.trimToEmpty(title).replace(' ', '_');
    }

    /** 取 {@code /rest.php/v1/page/} 之后、URL 解码过的原始片段（标题可能含斜杠 / 中文）。 */
    private static String pathAfterPrefix(HttpServletRequest request, String prefix) {
        String uri = request.getRequestURI();
        int index = uri.indexOf(prefix);
        if (index < 0) {
            return "";
        }
        String rest = uri.substring(index + prefix.length());
        try {
            return java.net.URLDecoder.decode(rest, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return rest;
        }
    }

    private static ResponseEntity<Object> error(HttpStatus status, String errorKey, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorKey", errorKey);
        body.put("message", message);
        body.put("httpCode", status.value());
        body.put("httpReason", status.getReasonPhrase());
        return ResponseEntity.status(status).body(body);
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<Object> asResponse(ResponseEntity<?> response) {
        return (ResponseEntity<Object>) response;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean booleanOf(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        String text = value == null ? "" : String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(text) || "1".equals(text);
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Map<?, ?> map && map.get("id") != null) {
            return longOf(map.get("id"));
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
