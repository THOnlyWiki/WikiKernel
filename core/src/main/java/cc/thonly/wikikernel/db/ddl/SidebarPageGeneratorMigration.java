package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 一次性迁移 Sidebar 里的页面生成器入口：
 * {@code Help:页面生成器 / 帮助:页面生成器} → {@code 快速创建页面}，
 * {@code Help:子页面生成器 / 帮助:子页面生成器} → {@code 快速创建子页面}。
 * <p>
 * 只替换侧栏定义中精确的旧条目，不覆盖用户自己写的其它内容；迁移完成后写一个站点设置标记，
 * 之后不再重复修改（用户后续可以自由改回）。
 */
@Component
@Order(6)
public class SidebarPageGeneratorMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SidebarPageGeneratorMigration.class);
    private static final String SIDEBAR_PAGE = "WikiKernel:Sidebar";
    private static final String MIGRATED_KEY = "setup.sidebar-page-generator-routes-v2";

    private final WikiPageService wikiPageService;
    private final SiteSettingsService siteSettingsService;

    public SidebarPageGeneratorMigration(WikiPageService wikiPageService,
                                         SiteSettingsService siteSettingsService) {
        this.wikiPageService = wikiPageService;
        this.siteSettingsService = siteSettingsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (siteSettingsService.getBoolean(MIGRATED_KEY, false)) {
            return;
        }
        var page = wikiPageService.getByTitle(SIDEBAR_PAGE).orElse(null);
        if (page == null) {
            return;
        }
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");
        String migrated = content
                .replace("Help:页面生成器|快速创建活动页面", "快速创建页面|快速创建页面")
                .replace("帮助:页面生成器|快速创建活动页面", "快速创建页面|快速创建页面")
                .replace("Help:子页面生成器|快速创建活动子页面", "快速创建子页面|快速创建子页面")
                .replace("帮助:子页面生成器|快速创建活动子页面", "快速创建子页面|快速创建子页面")
                .replace("Help:页面生成器|页面生成器", "快速创建页面|快速创建页面")
                .replace("帮助:页面生成器|页面生成器", "快速创建页面|快速创建页面")
                .replace("Help:子页面生成器|子页面生成器", "快速创建子页面|快速创建子页面")
                .replace("帮助:子页面生成器|子页面生成器", "快速创建子页面|快速创建子页面");
        if (!migrated.equals(content)) {
            try {
                wikiPageService.editPage(null, SIDEBAR_PAGE, migrated, "迁移页面生成器入口");
                log.info("已把 Sidebar 的页面生成器入口迁移到「快速创建页面 / 快速创建子页面」");
            } catch (RuntimeException exception) {
                log.warn("迁移 Sidebar 页面生成器入口失败（下次启动会重试）", exception);
                return;
            }
        }
        siteSettingsService.set(MIGRATED_KEY, "true");
    }
}
