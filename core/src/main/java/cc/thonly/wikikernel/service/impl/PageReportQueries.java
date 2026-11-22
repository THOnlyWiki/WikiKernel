package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.PageReportStore;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@Service
public class PageReportQueries implements PageReportStore {
    private static final int BATCH_SIZE = 250;
    private static final String VISIBLE = "p.deleted = FALSE AND p.hided = FALSE";
    private static final String CURRENT_LINK = """
            JOIN wiki_page_report_link l ON l.page_id = p.id AND l.revision_id = p.latest_revision_id
            """;

    private final JdbcTemplate jdbc;
    private final PageIndexGate gate;
    private final AccessControlService access;

    PageReportQueries(JdbcTemplate jdbc, PageIndexGate gate, AccessControlService access) {
        this.jdbc = jdbc;
        this.gate = gate;
        this.access = access;
    }

    public List<String> propertyNames(WikiUser viewer) {
        gate.awaitIdle();
        Set<String> seen = new LinkedHashSet<>();
        String query = """
                SELECT p.title, v.prop_name FROM wiki_page_property v JOIN wiki_page p ON p.id = v.page_id
                WHERE %s ORDER BY v.id LIMIT ? OFFSET ?
                """.formatted(VISIBLE);
        for (int offset = 0; ; offset += BATCH_SIZE) {
            List<Row> rows = jdbc.query(query, (rs, index) -> new Row(rs.getString(1), rs.getString(2)),
                    BATCH_SIZE, offset);
            for (Row row : access.filterReadable(rows, Row::title, viewer)) {
                seen.add(row.value());
            }
            if (rows.size() < BATCH_SIZE) {
                return seen.stream().sorted().toList();
            }
        }
    }

    @Override
    public Results withProperty(String name, String namespace, boolean descending, boolean byValue,
                                int offset, int limit, WikiUser viewer) {
        String direction = descending ? " DESC" : " ASC";
        String order = (byValue ? "v.prop_value" + direction + ", " : "") + "p.id" + direction + ", v.id";
        String sql = """
                SELECT p.title, v.prop_value FROM wiki_page_property v JOIN wiki_page p ON p.id = v.page_id
                WHERE %s AND v.prop_name = ? ORDER BY %s
                """.formatted(VISIBLE, order);
        return read(sql, viewer, offset, limit, namespaceFilter(namespace), false, name);
    }

    @Override
    public Results linkingToProperty(String name, int offset, int limit, WikiUser viewer) {
        String sql = """
                SELECT DISTINCT p.title, target.title FROM wiki_page p
                %s
                JOIN wiki_page target ON LOWER(target.title) = LOWER(l.link_target)
                WHERE %s AND l.link_kind = 'internal'
                    AND target.deleted = FALSE AND target.hided = FALSE
                    AND EXISTS (SELECT 1 FROM wiki_page_property v WHERE v.page_id = target.id AND v.prop_name = ?)
                ORDER BY p.title, target.title
                """.formatted(CURRENT_LINK, VISIBLE);
        return read(sql, viewer, offset, limit, namespaceFilter("main"), true, name);
    }

    public Results externalLinks(Predicate<String> matches, String namespace, int offset, int limit, WikiUser viewer) {
        String sql = """
                SELECT p.title, l.link_target FROM wiki_page p
                %s WHERE %s AND l.link_kind = 'external' ORDER BY l.link_target, l.id
                """.formatted(CURRENT_LINK, VISIBLE);
        return read(sql, viewer, offset, limit, namespaceFilter(namespace).and(row -> matches.test(row.value())),
                false);
    }

    public Results redirects(int offset, int limit, WikiUser viewer) {
        String sql = """
                SELECT p.title, l.link_target FROM wiki_page p
                %s WHERE %s AND l.link_kind = 'redirect'
                AND NOT EXISTS (SELECT 1 FROM wiki_page target
                    WHERE LOWER(target.title) = LOWER(l.link_target) AND target.hided = TRUE)
                ORDER BY p.title, p.id
                """.formatted(CURRENT_LINK, VISIBLE);
        return read(sql, viewer, offset, limit, row -> true, true);
    }

    public Results prefix(String prefix, String namespace, boolean hideRedirects,
                          int offset, int limit, WikiUser viewer) {
        String sql = """
                SELECT p.title, '' FROM wiki_page p WHERE %s
                """.formatted(VISIBLE);
        if (hideRedirects) {
            sql += """
                     AND NOT EXISTS (SELECT 1 FROM wiki_page_report_link l
                         WHERE l.page_id = p.id AND l.revision_id = p.latest_revision_id AND l.link_kind = 'redirect')
                    """;
        }
        return read(sql + " ORDER BY p.title, p.id", viewer, offset, limit, namespaceFilter(namespace).and(row -> {
            String bare = bareTitle(row.title());
            return bare.regionMatches(true, 0, prefix, 0, prefix.length());
        }), false);
    }

    public static String bareTitle(String title) {
        return WikiNamespaces.inAnyNamespace(title) ? title.substring(title.indexOf(':') + 1) : title;
    }

    private static Predicate<Row> namespaceFilter(String namespace) {
        WikiNamespaces.Namespace selected = WikiNamespaces.byId(namespace).orElse(null);
        return row -> WikiNamespaces.matches(row.title(), selected);
    }

    private Results read(String sql, WikiUser viewer, int offset, int limit,
                         Predicate<Row> matches, boolean checkTarget, Object... parameters) {
        gate.awaitIdle();
        List<Row> visible = new ArrayList<>(limit);
        int remaining = offset;
        for (int from = 0; ; from += BATCH_SIZE) {
            Object[] args = Arrays.copyOf(parameters, parameters.length + 2);
            args[parameters.length] = BATCH_SIZE;
            args[parameters.length + 1] = from;
            List<Row> rows = jdbc.query(sql + " LIMIT ? OFFSET ?",
                    (rs, index) -> new Row(rs.getString(1), rs.getString(2)), args);
            List<Row> matchesInBatch = rows.stream().filter(matches).toList();
            List<Row> readable = access.filterReadable(matchesInBatch, Row::title, viewer);
            if (checkTarget) {
                readable = access.filterReadable(readable, Row::value, viewer);
            }
            for (Row row : readable) {
                if (remaining > 0) {
                    remaining--;
                } else if (visible.size() == limit) {
                    return new Results(List.copyOf(visible), true);
                } else {
                    visible.add(row);
                }
            }
            if (rows.size() < BATCH_SIZE) {
                return new Results(List.copyOf(visible), false);
            }
        }
    }
}
