package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:大量删除?name=用户名&prefix=标题前缀&limit=50} / {@code Special:Nuke}（仿 MediaWiki Nuke 扩展）：
 * 列出「由指定用户创建的页面」和 / 或「标题以 prefix 开头的页面」，供管理员批量软删除；
 * 也支持 {@code ?titles=标题1%0A标题2…}（**每行一个标题**，可粘贴 {@code [[链接]]} 形式）直接指定要删除的页面，
 * 填写后优先于用户名 / 前缀条件。
 * <p>
 * 创建者 = 该页面第一条修订的作者（匿名编辑视为匿名）；最后编辑者 / 最后编辑时间取最新修订与页面更新时间。
 * 仅管理员 / 行政员可用（无权限只显示 {@code nuke.noPermission}）；表单由 {@code /js/nuke.js} 提交到
 * {@code POST /api/nuke/delete}。
 */
@Component
public class NukeSpecialPage implements SpecialPage {

    /** 单次扫描的页面上限（创建者过滤在内存里做）。 */
    private static final int MAX_PAGES = 2000;

    /** 单次扫描的修订上限（一次取出，避免逐页 N+1 查询）。 */
    private static final int MAX_REVISIONS = 20000;

    /** 默认列出条数。 */
    private static final int DEFAULT_LIMIT = 50;

    /** 可选的列出条数。 */
    private static final List<Integer> LIMIT_OPTIONS = List.of(50, 100, 250, 500);

    /** 直接指定标题模式（每行一个）的单次上限。 */
    private static final int MAX_TITLES = 500;

    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final UserService userService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public NukeSpecialPage(WikiPageMapper wikiPageMapper,
                           WikiRevisionMapper wikiRevisionMapper,
                           UserService userService,
                           PermissionService permissionService,
                           MessageSource messageSource) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.userService = userService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "大量删除";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.nuke", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Nuke");
    }

    @Override
    public String category() {
        return SpecialPageCategories.MAINTENANCE;
    }

    @Override
    public String template() {
        return "special/nuke";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        boolean canManage = user != null && permissionService.canManage(user.getId());
        model.put("canManage", canManage);

        String name = Strings.trimToEmpty(request.parameters().get("name"));
        String prefix = Strings.trimToEmpty(request.parameters().get("prefix"));
        String titlesText = Strings.trimToEmpty(request.parameters().get("titles"));
        int limit = parseLimit(request.parameters().get("limit"));
        model.put("nameValue", name);
        model.put("prefixValue", prefix);
        model.put("titlesValue", titlesText);
        model.put("limitValue", limit);
        model.put("limitOptions", LIMIT_OPTIONS);
        model.put("needCriteria", false);
        model.put("empty", false);
        model.put("rows", List.of());
        model.put("missingTitles", List.of());
        model.put("invalidTitles", List.of());
        model.put("titleLimitExceeded", false);
        model.put("maxTitles", MAX_TITLES);

        if (!canManage) {
            return; // 模板只显示 nuke.noPermission
        }

        // 直接指定标题模式（每行一个）：优先于用户名 / 前缀条件
        if (!titlesText.isEmpty()) {
            listByTitles(titlesText, model);
            return;
        }
        if (name.isEmpty() && prefix.isEmpty()) {
            model.put("needCriteria", true);
            return;
        }

        Long creatorId = null;
        if (!name.isEmpty()) {
            creatorId = userService.findByUsername(name).map(WikiUser::getId).orElse(null);
            if (creatorId == null) {
                // 用户名不存在：按空结果处理（不报错），展示 nuke.empty
                model.put("empty", true);
                return;
            }
        }

        List<Map<String, Object>> rows = listPages(prefix, creatorId, limit);
        model.put("rows", rows);
        model.put("empty", rows.isEmpty());
    }

    /**
     * 列出「标题以 prefix 开头（prefix 为空则不限）」且「创建者 = creatorId（为空则不限）」的页面。
     * 页面与修订都只查一次（修订按 id 升序：每个页面的第一条 = 创建者，最后一条 = 最后编辑者）。
     */
    private List<Map<String, Object>> listPages(String prefix, Long creatorId, int limit) {
        List<WikiPage> candidates = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getHided, false)
                .eq(WikiPage::getDeleted, false)
                .likeRight(!prefix.isEmpty(), WikiPage::getTitle, prefix)
                .orderByAsc(WikiPage::getTitle)
                .last("LIMIT " + MAX_PAGES));

        Map<Long, Long> creators = new LinkedHashMap<>();
        Map<Long, Long> lastEditors = new LinkedHashMap<>();
        for (WikiRevision revision : wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .select(WikiRevision::getPageId, WikiRevision::getUserId, WikiRevision::getId)
                .eq(WikiRevision::getDeleted, false)
                .orderByAsc(WikiRevision::getId)
                .last("LIMIT " + MAX_REVISIONS))) {
            Long pageId = revision.getPageId();
            if (pageId == null) {
                continue;
            }
            creators.putIfAbsent(pageId, revision.getUserId());
            lastEditors.put(pageId, revision.getUserId());
        }

        List<WikiPage> selected = new ArrayList<>();
        Map<Long, Long> editorOf = new LinkedHashMap<>();
        Set<Long> editorIds = new HashSet<>();
        for (WikiPage page : candidates) {
            if (selected.size() >= limit) {
                break;
            }
            if (creatorId != null && !creatorId.equals(creators.get(page.getId()))) {
                continue;
            }
            selected.add(page);
            Long editorId = lastEditors.get(page.getId());
            editorOf.put(page.getId(), editorId);
            if (editorId != null) {
                editorIds.add(editorId);
            }
        }

        Map<Long, String> names = userService.usernames(editorIds);
        String anonymous = message("common.anonymous");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiPage page : selected) {
            Long editorId = editorOf.get(page.getId());
            String userName = editorId == null ? anonymous : names.getOrDefault(editorId, anonymous);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", page.getTitle());
            row.put("url", WikiUrls.pagePath(page.getTitle()));
            row.put("userName", userName);
            // 匿名 / 找不到用户名时不加链接（否则会链到「用户:匿名用户」这种不存在的标题）
            row.put("userUrl", editorId == null || !names.containsKey(editorId)
                    ? null : WikiUrls.pagePath("用户:" + userName));
            row.put("time", page.getUpdateAt());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 直接指定标题模式：每行一个标题（可粘贴 {@code [[链接]]} 形式，自动去掉方括号与竖线后面的文字）。
     * 校验合法性与去重（忽略大小写），只列出仍然存在（未删除）的页面，其余进入「不存在」提示。
     */
    private void listByTitles(String titlesText, Map<String, Object> model) {
        List<String> invalid = new ArrayList<>();
        List<String> lookup = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : titlesText.split("[\\r\\n]+")) {
            String candidate = unwrapLink(line.trim());
            if (candidate.isEmpty()) {
                continue;
            }
            if (WikiTitles.validate(candidate).isPresent()) {
                invalid.add(candidate);
                continue;
            }
            String normalized = WikiTitles.normalize(candidate);
            if (normalized.isEmpty() || !seen.add(normalized.toLowerCase(java.util.Locale.ROOT))) {
                continue; // 空标题 / 重复标题只处理一次
            }
            lookup.add(normalized);
        }
        if (lookup.size() > MAX_TITLES) {
            model.put("titleLimitExceeded", true);
            return;
        }

        Map<String, WikiPage> found = new LinkedHashMap<>();
        if (!lookup.isEmpty()) {
            for (WikiPage page : wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                    .in(WikiPage::getTitle, lookup)
                    .eq(WikiPage::getDeleted, false))) {
                found.put(page.getTitle().toLowerCase(java.util.Locale.ROOT), page);
            }
        }
        List<WikiPage> pages = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String title : lookup) {
            WikiPage page = found.get(title.toLowerCase(java.util.Locale.ROOT));
            if (page == null) {
                missing.add(title); // 不存在或已删除
            } else {
                pages.add(page);
            }
        }
        List<Map<String, Object>> rows = rowsOf(pages);
        model.put("rows", rows);
        model.put("missingTitles", missing);
        model.put("invalidTitles", invalid);
        model.put("empty", rows.isEmpty() && !missing.isEmpty() && invalid.isEmpty());
    }

    /** 取 {@code [[标题|文字]]} / {@code [[标题]]} 里的标题部分（普通标题原样返回）。 */
    private static String unwrapLink(String value) {
        String text = value.trim();
        if (text.startsWith("[[") && text.endsWith("]]")) {
            text = text.substring(2, text.length() - 2).trim();
            int pipe = text.indexOf('|');
            if (pipe >= 0) {
                text = text.substring(0, pipe).trim();
            }
        }
        return text;
    }

    /** 一组页面的展示行（标题 / URL / 最后编辑者 / 最后编辑时间）；修订一次查询。 */
    private List<Map<String, Object>> rowsOf(List<WikiPage> pages) {
        if (pages.isEmpty()) {
            return List.of();
        }
        List<Long> pageIds = pages.stream().map(WikiPage::getId).toList();
        Map<Long, Long> lastEditors = new LinkedHashMap<>();
        for (WikiRevision revision : wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .select(WikiRevision::getPageId, WikiRevision::getUserId, WikiRevision::getId)
                .in(WikiRevision::getPageId, pageIds)
                .eq(WikiRevision::getDeleted, false)
                .orderByAsc(WikiRevision::getId))) {
            if (revision.getPageId() != null) {
                lastEditors.put(revision.getPageId(), revision.getUserId());
            }
        }
        Set<Long> editorIds = new HashSet<>();
        lastEditors.values().forEach(id -> {
            if (id != null) {
                editorIds.add(id);
            }
        });
        Map<Long, String> names = userService.usernames(editorIds);
        String anonymous = message("common.anonymous");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiPage page : pages) {
            Long editorId = lastEditors.get(page.getId());
            String userName = editorId == null ? anonymous : names.getOrDefault(editorId, anonymous);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", page.getTitle());
            row.put("url", WikiUrls.pagePath(page.getTitle()));
            row.put("userName", userName);
            // 匿名 / 找不到用户名时不加链接（否则会链到「用户:匿名用户」这种不存在的标题）
            row.put("userUrl", editorId == null || !names.containsKey(editorId)
                    ? null : WikiUrls.pagePath("用户:" + userName));
            row.put("time", page.getUpdateAt());
            rows.add(row);
        }
        return rows;
    }

    /** limit 只接受 {@link #LIMIT_OPTIONS} 里的值，其余（含非法值）回退默认值。 */
    private static int parseLimit(String value) {
        try {
            int parsed = Integer.parseInt(Strings.trimToEmpty(value));
            return LIMIT_OPTIONS.contains(parsed) ? parsed : DEFAULT_LIMIT;
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
