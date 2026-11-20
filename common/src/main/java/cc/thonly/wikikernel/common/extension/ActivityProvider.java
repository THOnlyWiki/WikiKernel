package cc.thonly.wikikernel.common.extension;

import java.util.List;
import cc.thonly.wikikernel.common.plugin.Extension;

/** Public activity metadata for news and other plugins. */
public interface ActivityProvider extends Extension {
    record Activity(int year, long id, String name, String region, String date, String status,
                    String pageUrl, String calendarUrl, String revision) { }
    List<Activity> activities(int year);
    default List<String> regions() { return List.of(); }
}
