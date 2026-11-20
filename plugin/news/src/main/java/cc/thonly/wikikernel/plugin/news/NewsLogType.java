package cc.thonly.wikikernel.plugin.news;

import cc.thonly.wikikernel.common.extension.*;
import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import org.springframework.context.i18n.LocaleContextHolder;

public final class NewsLogType extends ResourceMessageBundle implements LogType {
    private final NewsService news;
    public NewsLogType(NewsService news) { this.news = news; }
    public String id() { return "news"; }
    public boolean inRecentChanges() { return true; }
    public TargetKind targetKind() { return TargetKind.PAGE; }
    public boolean visibleTo(WikiLog log, Viewer viewer) { return news.canViewLog(viewer, log.getTarget()); }
    public String detailText(String detail) {
        String[] parts = detail.split(" / revision ", 2);
        String language = LocaleContextHolder.getLocale().getLanguage().equals("en") ? "en" : "zh";
        String action = messages().getOrDefault(language, java.util.Map.of()).get("news.log." + parts[0]);
        return action == null ? detail : action + (parts.length > 1 ? " · #" + parts[1] : "");
    }
}
