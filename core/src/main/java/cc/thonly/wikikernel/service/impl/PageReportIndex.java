package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.extension.BehaviorSwitch;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.wikitext.WikiRedirect;
import cc.thonly.wikikernel.web.WikiTitles;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
class PageReportIndex {
    private record Link(String kind, String target) {}

    private final JdbcTemplate jdbc;
    private final PagePropertyStore properties;
    private final PluginManager plugins;
    private final TransactionTemplate transaction;

    PageReportIndex(JdbcTemplate jdbc, PagePropertyStore properties, PluginManager plugins,
                    PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.plugins = plugins;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    String indexVersion() {
        // A newly enabled parser switch can add properties to articles that have never been edited.
        List<String> switches = plugins.extensions(BehaviorSwitch.class).stream()
                .map(BehaviorSwitch::name).sorted().toList();
        return "1:" + String.join(",", switches);
    }

    void replace(long pageId, long revisionId, String title, String source, String html,
                 Map<String, String> renderedProperties, String version) {
        Set<Link> links = new LinkedHashSet<>();
        links.add(new Link("version", version));
        WikiRedirect.targetOf(source, title)
                .ifPresent(target -> links.add(new Link("redirect", WikiTitles.normalize(target))));
        for (var anchor : Jsoup.parseBodyFragment(html).select("a[href]")) {
            String href = anchor.attr("href");
            if (anchor.hasClass("mw-interwiki") || anchor.hasClass("image")) {
                continue;
            }
            if (isExternal(href)) {
                links.add(new Link("external", href));
            } else {
                String target = localTarget(href);
                if (!target.isEmpty()) {
                    links.add(new Link("internal", target));
                }
            }
        }
        transaction.executeWithoutResult(status -> {
            Integer current = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM wiki_page
                    WHERE id = ? AND latest_revision_id = ? AND deleted = FALSE AND title = ?
                    """, Integer.class, pageId, revisionId, title);
            if (current == null || current == 0) {
                return;
            }
            deleteLinks(pageId);
            jdbc.batchUpdate("""
                    INSERT INTO wiki_page_report_link (page_id, revision_id, link_kind, link_target)
                    VALUES (?, ?, ?, ?)
                    """, links.stream().map(link ->
                    new Object[]{pageId, revisionId, link.kind(), link.target()}).toList());
            properties.replaceRendered(pageId, renderedProperties);
        });
    }

    void clear(long pageId) {
        transaction.executeWithoutResult(status -> {
            deleteLinks(pageId);
            properties.removeAll(pageId);
        });
    }

    private void deleteLinks(long pageId) {
        jdbc.update("DELETE FROM wiki_page_report_link WHERE page_id = ?", pageId);
    }

    static boolean isExternal(String href) {
        String lower = href.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ftp://");
    }

    private static String localTarget(String href) {
        if (href.startsWith("//")) {
            return "";
        }
        String rest = WikiUrls.pagePathRest(href);
        if (rest == null) {
            return "";
        }
        int suffix = rest.indexOf('#');
        if (suffix >= 0) {
            rest = rest.substring(0, suffix);
        }
        if (rest.contains("?")) {
            return "";
        }
        try {
            return WikiTitles.normalize(URLDecoder.decode(rest.replace("+", "%2B"), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalidEncoding) {
            return "";
        }
    }
}
