package cc.thonly.wikikernel.plugin.news;

import cc.thonly.wikikernel.common.plugin.*;
import cc.thonly.wikikernel.common.extension.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

public class NewsPlugin implements WikiPlugin {
    @Autowired private NewsService news;
    @Autowired private NewsJobs jobs;
    public PluginDescriptor descriptor() { return new PluginDescriptor("news", "活动资讯", "1.0.0", "投稿审核、AI 草稿与首页资讯"); }
    public List<Class<?>> schemaEntities() { return List.of(NewsItem.class, NewsRevision.class, NewsJob.class); }
    public void onStart(PluginContext context) {
        news.initialize();
        NewsLogType newsLogs = new NewsLogType(news);
        context.register(LogType.class, newsLogs);
        context.register(MessageBundle.class, newsLogs);
        NewsPages pages = new NewsPages(news);
        context.register(PageDisplayHook.class, pages);
        context.register(HtmlHeadProvider.class, pages);
        context.register(HtmlHeadProvider.class, new HtmlHeadProvider() {
            public List<String> headItems(HeadContext context) {
                return context.properties().containsKey("news-used") ? pages.headItems(context) : List.of();
            }
        });
        context.register(SpecialPage.class, new NewsManagementPage());
        context.register(SiteSettingsSection.class, new SiteSettingsSection() {
            public String id() { return "news"; }
            public String title() { return "活动资讯"; }
            public String description() { return "投稿需登录。下列用户组用 | 分隔，AI 场景在特殊:AI管理中设置。"; }
            public List<SettingField> fields() {
                return List.of(SettingField.checkbox("features.news", "启用活动资讯", true),
                        SettingField.text("news.editor-groups", "可编辑所有稿件的用户组", "管理员|行政员|新闻编辑"),
                        SettingField.text("news.reviewer-groups", "可审核发布和撤稿的用户组", "管理员|行政员|新闻审核"));
            }
        });
        context.register(ParserTag.class, new NewsListTag(news));
        jobs.start();
    }
    public void onStop(PluginContext context) { jobs.close(); }
}
