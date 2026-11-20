package cc.thonly.wikikernel.ai;

import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.ai.AiConfig;
import cc.thonly.wikikernel.db.ddl.PluginSchemaDdl;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Arrays;

@Component
@Order(20)
public class AiCoreBootstrap implements ApplicationRunner {
    private final PluginSchemaDdl ddl;
    private final SiteConfig settings;
    private final AiConfig config;
    private final AiProperties properties;
    private final PluginDatabase database;
    private final AiSecrets secrets;
    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;
    @org.springframework.beans.factory.annotation.Value("${wikikernel.database.auto-ddl:true}")
    private boolean autoDdl;

    public AiCoreBootstrap(PluginSchemaDdl ddl, SiteConfig settings, AiConfig config,
                           AiProperties properties, PluginDatabase database, AiSecrets secrets) {
        this.ddl = ddl;
        this.settings = settings;
        this.config = config;
        this.properties = properties;
        this.database = database;
        this.secrets = secrets;
    }

    @Override
    public void run(ApplicationArguments args) {
        ddl.ensureSchema("core-ai", List.of(WikiUserAiKey.class, AiCall.class));
        widenLegacyKeyColumn();
        if (properties.getAllowNormalUsers() != null) config.saveAllowNormalUsers(properties.getAllowNormalUsers());
        if (!settings.getBoolean("ai.migration.v1", false)) {
            database.transaction(() -> {
                String base = settings.get("thonly-ai.base-url", "");
                if (!base.isBlank()) {
                    String id = "thonly-legacy";
                    int suffix = 1;
                    while (config.site(id).configured() || config.yaml(id).configured()) id = "thonly-legacy-" + suffix++;
                    config.saveSite(id, settings.get("thonly-ai.api-key", ""),
                            base.replaceAll("/chat/completions/?$", ""), settings.get("thonly-ai.model", ""), "", false);
                    settings.set("ai.provider." + id + ".type", "openai");
                    settings.set("ai.provider." + id + ".label", "活动整理（迁入）");
                    settings.set("ai.provider." + id + ".private", settings.get("thonly-ai.allow-private", "false"));
                    var ids = new LinkedHashSet<>(Arrays.asList(settings.get("ai.profiles", "").split(",")));
                    ids.remove(""); ids.add(id);
                    settings.set("ai.profiles", String.join(",", ids));
                    settings.set("ai.scene.activity.provider", id);
                    settings.set("ai.scene.activity.enabled", settings.get("thonly-ai.enabled", "false"));
                    settings.set("thonly-ai.api-key", "");
                }
                for (var row : database.query("SELECT id, api_key FROM wiki_user_ai_key")) {
                    database.update("UPDATE wiki_user_ai_key SET api_key = ? WHERE id = ?",
                            secrets.seal(row.string("api_key")), row.longValue("id", 0));
                }
                var providerIds = new LinkedHashSet<>(cc.thonly.wikikernel.common.ai.AiVendorCatalog.ids());
                providerIds.addAll(config.yamlProviders().keySet());
                for (String id : providerIds) {
                    String key = settings.get("ai.provider." + id + ".key", "");
                    if (!key.isBlank()) settings.set("ai.provider." + id + ".key", secrets.seal(key));
                }
                settings.set("ai.migration.v1", "true");
                return null;
            });
        }
    }

    private void widenLegacyKeyColumn() {
        if (!autoDdl) return;
        boolean widen = false;
        try (var connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
            if (!product.contains("mysql") && !product.contains("mariadb")) return;
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "wiki_user_ai_key", "api_key")) {
                widen = columns.next() && columns.getInt("COLUMN_SIZE") < 2048;
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Cannot inspect AI credential storage", exception);
        }
        if (widen) database.update("ALTER TABLE wiki_user_ai_key MODIFY COLUMN api_key VARCHAR(2048)");
    }
}
