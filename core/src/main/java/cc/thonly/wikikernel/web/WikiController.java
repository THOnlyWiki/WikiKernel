package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.WikiKernelInfo;
import cc.thonly.wikikernel.common.extension.PageRenderHook;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wiki 基础接口示例。 */
@RestController
@RequestMapping("/api")
public class WikiController {

    private final PluginManager pluginManager;
    private final DataSource dataSource;
    private final WikiPageMapper wikiPageMapper;
    private final AccessControlService accessControlService;

    public WikiController(PluginManager pluginManager,
                          DataSource dataSource,
                          WikiPageMapper wikiPageMapper,
                          AccessControlService accessControlService) {
        this.pluginManager = pluginManager;
        this.dataSource = dataSource;
        this.wikiPageMapper = wikiPageMapper;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/version")
    public Map<String, Object> version() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", WikiKernelInfo.NAME);
        info.put("version", WikiKernelInfo.VERSION);
        info.put("db", databaseProductName());
        info.put("pages", wikiPageMapper.selectCount(null));
        info.put("plugins", pluginManager.loadedPlugins().size());
        return info;
    }

    /** 演示 MyBatis-Plus 查询（表由 Auto-DDL 根据 WikiPage 实体自动创建）；按访问规则过滤。 */
    @GetMapping("/pages")
    public List<WikiPage> pages(HttpServletRequest request) {
        return accessControlService.filterReadable(
                wikiPageMapper.selectList(null), WikiPage::getTitle, UserSession.current(request));
    }

    /** 演示 PageRenderHook 扩展点的聚合执行。 */
    @GetMapping("/render")
    public String render(@RequestParam(name = "slug", defaultValue = "home") String slug) {
        String html = "<article data-slug=\"" + slug + "\">" + slug + "</article>";
        List<PageRenderHook> hooks = pluginManager.extensions(PageRenderHook.class).stream()
                .sorted(Comparator.comparingInt(PageRenderHook::order))
                .toList();
        for (PageRenderHook hook : hooks) {
            html = hook.afterRender(slug, html);
        }
        return html;
    }

    private String databaseProductName() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException exception) {
            return "unknown";
        }
    }
}
