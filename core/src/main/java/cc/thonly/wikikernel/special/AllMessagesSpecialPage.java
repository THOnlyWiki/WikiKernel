package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.*;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.i18n.InterfaceLanguage;
import cc.thonly.wikikernel.i18n.SystemMessages;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.*;

/** Behaviour adapted from MediaWiki SpecialAllMessages and AllMessagesTablePager.
 * Upstream: https://doc.wikimedia.org/mediawiki-core/REL1_39/php/AllMessagesTablePager_8php_source.html
 * Lists this installation's real messages; edits remain normal versioned interface pages.
 */
@Component
public class AllMessagesSpecialPage implements SpecialPage {
    private final SystemMessages messages;
    private final MessageSource labels;
    public AllMessagesSpecialPage(SystemMessages messages, MessageSource labels) { this.messages = messages; this.labels = labels; }
    public String name() { return "所有消息"; }
    public String displayName() { return labels.getMessage("allmessages", null, LocaleContextHolder.getLocale()); }
    public List<String> aliases() { return List.of("AllMessages", "Allmessages", "系统消息"); }
    public String category() { return SpecialPageCategories.WIKI; }
    public String template() { return "special/all-messages"; }

    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> p = request.parameters();
        InterfaceLanguage current = InterfaceLanguage.fromLocale(LocaleContextHolder.getLocale());
        InterfaceLanguage requested = p.containsKey("lang") ? InterfaceLanguage.parse(p.get("lang")) : current;
        boolean unknown = p.containsKey("lang") && requested == null;
        InterfaceLanguage selectedLanguage = requested == null ? InterfaceLanguage.SIMPLIFIED_CHINESE : requested;
        String language = selectedLanguage.getMessageCode();
        String prefix = p.getOrDefault("prefix", "");
        String filter = p.getOrDefault("filter", "all");
        if (!Set.of("all", "modified", "unmodified").contains(filter)) filter = "all";
        int limit = 50;
        try { int value = Integer.parseInt(p.getOrDefault("limit", "50")); if (List.of(20, 50, 100, 250, 500, 5000).contains(value)) limit = value; }
        catch (NumberFormatException ignored) { }
        String selected = filter;
        var rows = messages.entries(selectedLanguage.getLocale()).stream()
                .filter(row -> row.name().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
                .filter(row -> selected.equals("all") || row.modified() == selected.equals("modified")).toList();
        String offset = p.getOrDefault("offset", "");
        boolean previous = p.getOrDefault("dir", "next").equals("prev");
        int start = 0;
        if (previous && offset.isEmpty()) start = Math.max(0, rows.size() - limit);
        else if (!offset.isEmpty()) {
            while (start < rows.size() && rows.get(start).name().compareTo(offset) < 0) start++;
            if (previous) start = Math.max(0, start - limit);
            else if (start < rows.size() && rows.get(start).name().equals(offset)) start++;
        }
        int end = Math.min(rows.size(), start + limit);
        var shown = rows.subList(Math.min(start, rows.size()), end);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : shown) {
            String discussion = row.title().startsWith("MediaWiki:") ? row.title().replaceFirst("MediaWiki:", "MediaWiki讨论:")
                    : row.title().replaceFirst("WikiKernel:", "WikiKernel讨论:");
            items.add(Map.of("entry", row, "editUrl", WikiUrls.pagePath(row.title()) + "?action=edit",
                    "talkUrl", WikiUrls.pagePath(discussion)));
        }
        model.put("allMessagesRows", items);
        model.put("amPrefix", prefix); model.put("amFilter", filter); model.put("amLang", language); model.put("amLimit", limit);
        model.put("amUnknownLanguage", unknown);
        model.put("amLimits", List.of(20, 50, 100, 250, 500, 5000)); model.put("amTotal", rows.size());
        model.put("amFirst", start > 0 ? url(prefix, filter, language, limit, "", "next") : "");
        model.put("amPrev", start > 0 && !shown.isEmpty() ? url(prefix, filter, language, limit, shown.getFirst().name(), "prev") : "");
        model.put("amNext", end < rows.size() && !shown.isEmpty() ? url(prefix, filter, language, limit, shown.getLast().name(), "next") : "");
        model.put("amLast", end < rows.size() ? url(prefix, filter, language, limit, "", "prev") : "");
        model.put("amPager", List.of(
                Map.of("url", model.get("amFirst"), "label", labels.getMessage("allmessages-first", null, LocaleContextHolder.getLocale())),
                Map.of("url", model.get("amPrev"), "label", labels.getMessage("allmessages-prev", null, LocaleContextHolder.getLocale())),
                Map.of("url", model.get("amNext"), "label", labels.getMessage("allmessages-next", null, LocaleContextHolder.getLocale())),
                Map.of("url", model.get("amLast"), "label", labels.getMessage("allmessages-last", null, LocaleContextHolder.getLocale()))));
        model.put("amUrl", WikiUrls.pagePath("特殊:所有消息"));
    }

    private static String url(String prefix, String filter, String lang, int limit, String offset, String direction) {
        return WikiUrls.pagePath("特殊:所有消息") + UriComponentsBuilder.newInstance()
                .queryParam("prefix", prefix).queryParam("filter", filter).queryParam("lang", lang).queryParam("limit", limit)
                .queryParam("offset", offset).queryParam("dir", direction).build().encode().toUriString();
    }
}
