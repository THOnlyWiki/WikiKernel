package cc.thonly.wikikernel.plugin.news;

import cc.thonly.wikikernel.common.ai.AiTasks;
import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.*;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.net.URI;
import java.util.*;

@Service
public class NewsService {
    public static final int HOMEPAGE_LIMIT = 6;
    public static final int MAX_LIST_LIMIT = 50;
    private static final java.time.format.DateTimeFormatter UTC = new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    public static final List<String> CATEGORIES = List.of("定档", "变更", "报名", "预告", "回顾", "站务");
    private final PluginDatabase db;
    private final AccessCheck access;
    private final SiteConfig settings;
    private final ObjectMapper mapper;
    private final LogWriter logs;
    private final ActivityDirectory activities;
    private final AiTasks ai;

    public NewsService(PluginDatabase db, AccessCheck access, SiteConfig settings, ObjectMapper mapper,
                       LogWriter logs, ActivityDirectory activities, AiTasks ai) {
        this.db = db; this.access = access; this.settings = settings; this.mapper = mapper;
        this.logs = logs; this.activities = activities; this.ai = ai;
    }

    public boolean enabled() { return settings.getBoolean("features.news", true); }
    public void initialize() {
        for (var row : db.query("SELECT id, published FROM wiki_news WHERE published <> '' AND (released_at IS NULL OR released_at = '')")) {
            var c = validate(decode(row.string("published")));
            db.update("UPDATE wiki_news SET published = ?, released_at = ?, expires_at = ?, pin_until = ?, public_category = ?, public_region = ?, public_search = ? WHERE id = ?",
                    encode(c), c.publishAt(), c.expiresAt(), c.pinUntil(), c.category(), c.region(), c.title() + " " + c.summary(), row.longValue("id", 0));
        }
    }
    public boolean editor(Viewer actor) { return groups(actor, "news.editor-groups", "管理员|行政员|新闻编辑"); }
    public boolean reviewer(Viewer actor) { return groups(actor, "news.reviewer-groups", "管理员|行政员|新闻审核"); }
    private boolean groups(Viewer actor, String key, String fallback) {
        return actor != null && actor.loggedIn() && Arrays.stream(settings.get(key, fallback).split("\\|")).anyMatch(actor::inGroup);
    }

    public void requireUser(Viewer actor) {
        if (!enabled() || actor == null || !actor.loggedIn() || access.blockNotice(actor).isPresent()
                || !access.can(AccessAction.EDIT, "特殊:新闻管理", actor)) {
            throw new IllegalArgumentException("请登录有投稿权限且未被封禁的账号。");
        }
    }

    private DbRow find(long id) {
        return db.queryOne("SELECT * FROM wiki_news WHERE id = ?", id)
                .orElseThrow(() -> new IllegalArgumentException("新闻不存在。"));
    }

    private void requireEdit(Viewer actor, DbRow row) {
        requireUser(actor);
        if (row.longValue("owner_id", 0) != actor.id() && !editor(actor) && !reviewer(actor)) {
            throw new IllegalArgumentException("你不能编辑这篇新闻。");
        }
    }

    public Map<String, Object> draft(Viewer actor, long id) {
        DbRow row = find(id); requireEdit(actor, row);
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("version", row.longValue("version", 0));
        result.put("status", row.string("status")); result.put("content", decode(row.string("draft")));
        result.put("hasPublished", !row.string("published").isBlank());
        result.put("canReview", reviewer(actor));
        return result;
    }

    public List<Map<String, Object>> drafts(Viewer actor) {
        requireUser(actor);
        var rows = editor(actor) || reviewer(actor)
                ? db.query("SELECT * FROM wiki_news ORDER BY id DESC LIMIT 100")
                : db.query("SELECT * FROM wiki_news WHERE owner_id = ? ORDER BY id DESC LIMIT 100", actor.id());
        return rows.stream().map(row -> draft(actor, row.longValue("id", 0))).toList();
    }

    public long save(Viewer actor, long id, long version, NewsContent input) {
        requireUser(actor);
        NewsContent validated = validate(input);
        return db.transaction(() -> {
            NewsContent content = validated;
            String now = Instant.now().toString();
            long newsId = id;
            long next = version + 1;
            if (id == 0) {
                next = 1;
                newsId = db.insert("INSERT INTO wiki_news (draft, published, status, version, owner_id, updated_at, created_at)"
                                + " VALUES (?, '', 'DRAFT', 1, ?, ?, ?)", encode(content), actor.id(), now, now);
            } else {
                DbRow row = find(id); requireEdit(actor, row);
                var previous = decode(row.string("draft"));
                // AI provenance cannot be removed by changing a form field.
                content = new NewsContent(content.title(), content.summary(), content.body(), content.category(), content.region(),
                        content.sourceName(), content.sourceUrl(), content.cover(), content.activityYear(), content.activityId(),
                        content.activityRevision(), content.publishAt(), content.expiresAt(), content.pinUntil(),
                        previous.aiAssisted() || content.aiAssisted(), previous.aiAssisted() ? previous.aiModel() : content.aiModel());
                changed(db.update("UPDATE wiki_news SET draft = ?, status = 'DRAFT', version = ?, updated_at = ? WHERE id = ? AND version = ?",
                        encode(content), next, now, id, version));
            }
            revision(newsId, next, content, actor, id == 0 ? "create" : "save");
            return newsId;
        });
    }

    public void transition(Viewer actor, long id, long version, String action) {
        requireUser(actor);
        db.transaction(() -> {
            DbRow row = find(id); requireEdit(actor, row);
            NewsContent content = validate(decode(row.string("draft")));
            String next;
            String published = row.string("published");
            switch (action) {
                case "submit" -> {
                    if (content.sourceUrl().isBlank() && !content.category().equals("站务")) {
                        throw new IllegalArgumentException("提交审核前请填写消息来源链接。");
                    }
                    next = "PENDING";
                }
                case "publish" -> {
                    if (!reviewer(actor) || !row.string("status").equals("PENDING")) {
                        throw new IllegalArgumentException("只有审核人员可以发布待审核的版本。");
                    }
                    verifyActivity(content);
                    String publishAt = content.publishAt().isBlank() ? UTC.format(Instant.now()) : content.publishAt();
                    content = new NewsContent(content.title(), content.summary(), content.body(), content.category(), content.region(),
                            content.sourceName(), content.sourceUrl(), content.cover(), content.activityYear(), content.activityId(),
                            content.activityRevision(), publishAt, content.expiresAt(), content.pinUntil(), content.aiAssisted(), content.aiModel());
                    published = encode(content);
                    next = "PUBLISHED";
                }
                case "reject" -> {
                    if (!reviewer(actor)) throw new IllegalArgumentException("你没有审核权限。");
                    next = "REJECTED";
                }
                case "withdraw" -> {
                    if (!reviewer(actor)) throw new IllegalArgumentException("你没有撤稿权限。");
                    published = ""; next = "WITHDRAWN";
                }
                default -> throw new IllegalArgumentException("操作不支持。");
            }
            NewsContent publicContent = published.isBlank() ? null : decode(published);
            changed(db.update("UPDATE wiki_news SET draft = ?, published = ?, status = ?, version = ?, updated_at = ?,"
                            + " released_at = ?, expires_at = ?, pin_until = ?, public_category = ?, public_region = ?, public_search = ? WHERE id = ? AND version = ?",
                    encode(content), published, next, version + 1, Instant.now().toString(),
                    publicContent == null ? "" : publicContent.publishAt(), publicContent == null ? "" : publicContent.expiresAt(),
                    publicContent == null ? "" : publicContent.pinUntil(), publicContent == null ? "" : publicContent.category(),
                    publicContent == null ? "" : publicContent.region(), publicContent == null ? "" : publicContent.title() + " " + publicContent.summary(), id, version));
            revision(id, version + 1, content, actor, action);
            return null;
        });
    }

    private void verifyActivity(NewsContent content) {
        if (content.activityId() == 0) return;
        var activity = activities.activities(content.activityYear()).stream().filter(a -> a.id() == content.activityId())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("关联活动不存在，请重新核对。"));
        if (!activity.revision().equals(content.activityRevision())) {
            throw new IllegalArgumentException("活动信息已变化，请重新选择活动并核对新闻。");
        }
    }

    private static void changed(int count) {
        if (count != 1) throw new IllegalArgumentException("内容已被其他人修改，请重新载入后处理。");
    }

    private void revision(long id, long version, NewsContent content, Viewer actor, String action) {
        db.insert("INSERT INTO wiki_news_revision (news_id, version, payload, action, actor_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, version, encode(content), action, actor.id(), Instant.now().toString());
        logs.record("news", actor.id(), "新闻/" + id, action + " / revision " + version);
    }

    public List<DbRow> history(Viewer actor, long id) {
        requireEdit(actor, find(id));
        return db.query("SELECT version, action, actor_id, created_at FROM wiki_news_revision WHERE news_id = ? ORDER BY version DESC LIMIT 100", id);
    }

    public boolean canViewLog(Viewer actor, String target) {
        if (!enabled() || target == null || !target.matches("新闻/[0-9]+")) return false;
        if (!access.canRead("新闻", actor) || !access.canRead(target, actor)) return false;
        var row = db.queryOne("SELECT owner_id, published, released_at FROM wiki_news WHERE id = ?", Long.parseLong(target.substring(3)));
        if (row.isEmpty()) return false;
        if (!row.get().string("published").isBlank() && !row.get().string("released_at").isBlank()
                && !Instant.parse(row.get().string("released_at")).isAfter(Instant.now())) return true;
        try { requireUser(actor); }
        catch (IllegalArgumentException exception) { return false; }
        return row.get().longValue("owner_id", 0) == actor.id() || editor(actor) || reviewer(actor);
    }

    public List<Map<String, Object>> published(Viewer actor, boolean homepage, String query, String category, String region, int offset) {
        return published(actor, homepage, query, category, region, offset, null);
    }

    public List<Map<String, Object>> published(Viewer actor, boolean homepage, String query, String category, String region, int offset, Integer requestedLimit) {
        int limit = requestedLimit == null ? (homepage ? HOMEPAGE_LIMIT : 20) : requestedLimit;
        if (limit < 1 || limit > MAX_LIST_LIMIT) throw new IllegalArgumentException("新闻条数应为 1 至 " + MAX_LIST_LIMIT + "。");
        if (!enabled() || !access.canRead("新闻", actor)) return List.of();
        Instant now = Instant.now();
        if (offset < 0 || offset > 10000) throw new IllegalArgumentException("分页范围不合法。");
        String sql = "SELECT id, published FROM wiki_news WHERE published <> '' AND released_at <= ?";
        List<Object> args = new ArrayList<>(); args.add(UTC.format(now));
        if (homepage) { sql += " AND (expires_at = '' OR expires_at > ?)"; args.add(UTC.format(now)); }
        if (!query.isBlank()) { sql += " AND LOWER(public_search) LIKE ?"; args.add("%" + query.toLowerCase(Locale.ROOT) + "%"); }
        if (!category.isBlank()) { sql += " AND public_category = ?"; args.add(category); }
        if (!region.isBlank()) { sql += " AND public_region = ?"; args.add(region); }
        sql += " ORDER BY CASE WHEN pin_until > ? THEN 1 ELSE 0 END DESC, released_at DESC, id DESC LIMIT 100 OFFSET ?";
        args.add(UTC.format(now));
        List<Map<String, Object>> result = new ArrayList<>();
        int scanned = 0, visible = 0;
        while (result.size() < limit) {
            var batchArgs = new ArrayList<>(args); batchArgs.add(scanned);
            var rows = db.query(sql, batchArgs.toArray());
            for (var row : rows) {
                long id = row.longValue("id", 0);
                if (access.canRead("新闻/" + id, actor) && visible++ >= offset) {
                    result.add(Map.of("id", id, "content", decode(row.string("published"))));
                    if (result.size() == limit) break;
                }
            }
            if (rows.size() < 100) break;
            scanned += 100;
        }
        return result;
    }

    private boolean visible(NewsContent c, Instant now) { return !c.publishAt().isBlank() && !Instant.parse(c.publishAt()).isAfter(now); }

    public NewsContent article(Viewer actor, long id) {
        if (!enabled() || !access.canRead("新闻", actor) || !access.canRead("新闻/" + id, actor)) {
            throw new IllegalArgumentException("新闻不存在或不可查看。");
        }
        var row = find(id);
        if (row.string("published").isBlank()) throw new IllegalArgumentException("新闻尚未发布。");
        var content = decode(row.string("published"));
        if (!visible(content, Instant.now())) throw new IllegalArgumentException("新闻尚未发布。");
        return content;
    }

    public NewsContent validate(NewsContent c) {
        if (c == null) throw new IllegalArgumentException("新闻内容为空。");
        String title = text(c.title(), 120), body = text(c.body(), 60000);
        if (title.isBlank() || body.isBlank()) throw new IllegalArgumentException("请填写标题和正文。");
        if (!CATEGORIES.contains(c.category())) throw new IllegalArgumentException("新闻类型不合法。");
        return new NewsContent(title, text(c.summary(), 300), body, c.category(), text(c.region(), 80),
                text(c.sourceName(), 120), url(c.sourceUrl(), false), url(c.cover(), true), c.activityYear(), c.activityId(),
                text(c.activityRevision(), 1000), instant(c.publishAt()), instant(c.expiresAt()), instant(c.pinUntil()), c.aiAssisted(), text(c.aiModel(), 120));
    }

    private static String instant(String value) {
        try { return value == null || value.isBlank() ? "" : UTC.format(Instant.parse(value)); }
        catch (java.time.format.DateTimeParseException exception) { throw new IllegalArgumentException("时间格式不合法。"); }
    }
    private static String text(String value, int limit) {
        String v = value == null ? "" : value.strip();
        if (v.length() > limit) throw new IllegalArgumentException("输入超过允许长度。");
        return v;
    }
    private static String url(String value, boolean localOnly) {
        String v = text(value, 2048);
        if (v.isBlank()) return v;
        if (v.indexOf('\\') >= 0 || v.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("链接不合法。");
        if (localOnly) {
            if (!v.startsWith("/files/") || v.contains("..")) throw new IllegalArgumentException("封面请选择本站文件地址。");
        } else {
            URI uri = URI.create(v);
            if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("来源需要完整的 http 或 https 链接。");
            }
        }
        return v;
    }

    public String encode(NewsContent content) { return mapper.writeValueAsString(content); }
    public NewsContent decode(String json) { return mapper.readValue(json, NewsContent.class); }
    public AiTasks ai() { return ai; }
}
