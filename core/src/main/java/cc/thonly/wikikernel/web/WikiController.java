package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.WikiKernelInfo;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Wiki 基础接口。 */
@RestController
@RequestMapping("/api")
public class WikiController {

    private final PluginManager pluginManager;
    private final DataSource dataSource;
    private final WikiPageMapper wikiPageMapper;

    public WikiController(PluginManager pluginManager,
                          DataSource dataSource,
                          WikiPageMapper wikiPageMapper) {
        this.pluginManager = pluginManager;
        this.dataSource = dataSource;
        this.wikiPageMapper = wikiPageMapper;
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

    private String databaseProductName() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException exception) {
            return "unknown";
        }
    }
}
