package cc.thonly.wikikernel.plugin.news;

import cc.thonly.wikikernel.common.extension.*;
import java.util.List;

public class NewsManagementPage implements SpecialPage {
    public String name() { return "新闻管理"; }
    public String displayName() { return "新闻管理"; }
    public List<String> aliases() { return List.of("Newsroom"); }
    public String template() { return "special/newsroom"; }
}
