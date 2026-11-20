package cc.thonly.wikikernel.i18n;

import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.WikiTitles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;

/** Raw, readable interface messages. A request reads one snapshot, never one SQL query per label. */
@Service
public class InterfaceMessageOverrides {
    public record Value(String title, String text) { }
    private static final String ATTRIBUTE = InterfaceMessageOverrides.class.getName();
    private final JdbcTemplate jdbc;
    private final AccessControlService access;
    private final cc.thonly.wikikernel.sidebar.SidebarProperties sidebar;
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private record Snapshot(long generation, Map<String, Value> values) { }

    @org.springframework.context.event.EventListener
    public void changed(cc.thonly.wikikernel.service.WikiPageChangedEvent event) {
        if (event.title() != null && (event.title().startsWith("WikiKernel:") || event.title().startsWith("MediaWiki:")
                || WikiTitles.normalize(event.title()).equals(WikiTitles.normalize(sidebar.getPage())))) generation.incrementAndGet();
    }

    public InterfaceMessageOverrides(JdbcTemplate jdbc, AccessControlService access,
                                     cc.thonly.wikikernel.sidebar.SidebarProperties sidebar) {
        this.jdbc = jdbc; this.access = access; this.sidebar = sidebar;
    }

    public Map<String, Value> visible() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet
                && servlet.getRequest().getAttribute(ATTRIBUTE) instanceof Snapshot existing
                && existing.generation() == generation.get()) {
            return existing.values();
        }
        List<Value> rows;
        try {
            rows = jdbc.query("SELECT p.title, r.content FROM wiki_page p JOIN wiki_revision r ON r.id ="
                            + " (SELECT MAX(v.id) FROM wiki_revision v WHERE v.page_id = p.id AND v.deleted = false)"
                            + " WHERE p.deleted = ? AND p.hided = ? AND r.deleted = ?"
                            + " AND (p.title LIKE 'WikiKernel:%' OR p.title LIKE 'MediaWiki:%' OR p.title = ?)"
                            + " AND p.title NOT LIKE '%.css' AND p.title NOT LIKE '%.js'"
                            + " AND p.title NOT LIKE '%.ts' AND p.title NOT LIKE '%.vue'",
                    (rs, index) -> new Value(rs.getString("title"), Objects.requireNonNullElse(rs.getString("content"), "")),
                    false, false, false, WikiTitles.normalize(sidebar.getPage()));
        } catch (BadSqlGrammarException exception) {
            // MessageSource can be used by Spring before the auto-DDL startup runner.
            return Map.of();
        }
        var user = attrs instanceof ServletRequestAttributes servlet ? UserSession.current(servlet.getRequest()) : null;
        Map<String, Value> result = new HashMap<>();
        for (var row : access.filterReadable(rows, Value::title, user)) result.put(WikiTitles.normalize(row.title()), row);
        Map<String, Value> snapshot = Map.copyOf(result);
        if (attrs instanceof ServletRequestAttributes servlet) servlet.getRequest().setAttribute(ATTRIBUTE, new Snapshot(generation.get(), snapshot));
        return snapshot;
    }

    /** Content language is simplified Chinese; foreign messages use /language, not the base override. */
    public Value find(String key, Locale locale) {
        return find(visible(), key, locale);
    }

    public Value find(Map<String, Value> values, String key, Locale locale) {
        return find(values, key, locale, false);
    }

    public Value find(Map<String, Value> values, String key, Locale locale, boolean legacyConfig) {
        Locale language = locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
        var suffixes = new LinkedHashSet<String>();
        String tag = language.toLanguageTag().toLowerCase(Locale.ROOT);
        if (!language.getLanguage().isEmpty()) {
            suffixes.add("/" + tag);
            if (tag.equals("zh-cn") || tag.equals("zh-hans")) { suffixes.add("/zh-hans"); suffixes.add("/zh-cn"); }
            suffixes.add("/" + language.getLanguage());
        }
        if (legacyConfig || language.getLanguage().isEmpty() || language.getLanguage().equals("zh")) suffixes.add("");
        for (String namespace : List.of("WikiKernel:", "MediaWiki:")) {
            for (String suffix : suffixes) {
                Value value = values.get(WikiTitles.normalize(namespace + key + suffix));
                if (value != null) return value;
            }
        }
        return null;
    }

    public static String title(String key, Locale locale) {
        String language = locale == null ? "zh" : locale.toLanguageTag().toLowerCase(Locale.ROOT);
        return WikiTitles.normalize("WikiKernel:" + key + (language.equals("zh") || language.equals("zh-cn")
                || language.equals("zh-hans") ? "" : "/" + language));
    }
}
