package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.ddl.SetupContentSeeder;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.sidebar.SidebarSection;
import cc.thonly.wikikernel.sidebar.SidebarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安装向导内容种子：由向导创建的 wiki（配置里有 initial-accounts）首次启动时
 * 创建示例首页 / MediaWiki 风格默认侧边栏 / 编辑帮助；已存在的页面不覆盖，且只种一次。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.setup.mode=off",
        "wikikernel.setup.initial-accounts[0].username=SeederAdmin",
        "wikikernel.setup.initial-accounts[0].password-hash=5d7845ac6ee7cfffafc5fe5f35cf666d",
        "wikikernel.setup.initial-accounts[0].groups[0]=管理员",
        "wikikernel.setup.initial-accounts[0].groups[1]=行政员",
        "wikikernel.setup.initial-accounts[1].username=SeederLegacy",
        "wikikernel.setup.initial-accounts[1].password-md5=5d7845ac6ee7cfffafc5fe5f35cf666d"
})
class SetupContentSeederTest {

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private UserService userService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private SiteSettingsService siteSettingsService;

    @Autowired
    private SidebarService sidebarService;

    @Autowired
    private SetupContentSeeder setupContentSeeder;

    @Test
    void seedsHomeSidebarAndHelpForWizardCreatedWiki() {
        // 初始账号：管理员 + 行政员
        WikiUser user = userService.findByUsername("SeederAdmin").orElseThrow();
        assertTrue(permissionService.has(user.getId(), PermissionService.GROUP_ADMIN));
        assertTrue(permissionService.has(user.getId(), PermissionService.GROUP_BUREAUCRAT));

        // 旧的 password-md5 字段仍兼容：账号创建成功且能登录（登录后自动升级为新格式）
        WikiUser legacy = userService.findByUsername("SeederLegacy").orElseThrow();
        WikiUser loggedIn = userService.login(legacy.getUsername(), "secret123");
        assertNotNull(loggedIn);
        assertTrue(loggedIn.getPassword().startsWith(":pbkdf2:"));

        // 示例首页
        assertTrue(contentOf("首页").contains("欢迎来到 WikiKernel"), contentOf("首页"));
        // 默认侧边栏页面与解析结果（导航 / 工具；工具包含完整 MediaWiki 工具箱，$title$ 占位按当前页面替换）
        assertTrue(contentOf("WikiKernel:Sidebar").contains("导航"));
        assertTrue(contentOf("WikiKernel:Sidebar").contains("$title$"));
        assertTrue(contentOf("WikiKernel:Sidebar").contains("可打印版"));
        assertTrue(contentOf("WikiKernel:Sidebar").contains("固定链接"));
        assertTrue(contentOf("WikiKernel:Sidebar").contains("页面信息"));
        List<SidebarSection> sections = sidebarService.sidebar("首页");
        assertEquals("导航", sections.get(0).getTitle());
        SidebarSection tools = sections.stream()
                .filter(section -> "工具".equals(section.getTitle()))
                .findFirst()
                .orElseThrow();
        assertTrue(tools.getItems().stream().anyMatch(item -> "可打印版".equals(item.getLabel())));
        assertTrue(tools.getItems().stream().anyMatch(item -> "固定链接".equals(item.getLabel())));
        assertTrue(tools.getItems().stream().anyMatch(item -> "页面信息".equals(item.getLabel())));
        // $title$ 已替换为当前页面
        assertTrue(tools.getItems().stream().anyMatch(item ->
                "页面信息".equals(item.getLabel()) && "/wiki/首页?action=info".equals(item.getHref())));
        // 编辑帮助
        assertTrue(contentOf("帮助:编辑").contains("编辑帮助"));
        // 只种一次（标记已写入）
        assertTrue(siteSettingsService.getBoolean(SetupContentSeeder.SEEDED_KEY, false));

        // 已存在的页面不会被覆盖：清掉标记再跑一次，内容保持管理员的修改
        wikiPageService.editPage(null, "首页", "管理员改过的内容");
        siteSettingsService.set(SetupContentSeeder.SEEDED_KEY, "false");
        setupContentSeeder.run(null);
        assertTrue(contentOf("首页").contains("管理员改过的内容"));
        assertTrue(siteSettingsService.getBoolean(SetupContentSeeder.SEEDED_KEY, false));
    }

    private String contentOf(String title) {
        WikiPage page = wikiPageService.getByTitle(title).orElseThrow();
        return wikiPageService.getLatestContent(page.getId()).orElseThrow();
    }
}
