package cc.thonly.wikikernel.plugin.oss;

import cc.thonly.wikikernel.common.plugin.PluginConfig;

import java.util.Locale;

/** OSS 扩展配置（wikikernel.plugins.settings.oss-extension.*）。 */
public final class OssSettings {

    public enum Mode {
        THUMBNAIL,
        ALL
    }

    private final boolean enabled;
    private final String operationUrl;
    private final String readUrl;
    private final String cdnUrl;
    private final String key;
    private final Mode mode;
    private final boolean migrateExisting;

    public OssSettings(PluginConfig config) {
        this.enabled = config.getBoolean("enabled", false);
        this.operationUrl = trimSlash(config.get("operation-url", "http://127.0.0.1:18081"));
        this.readUrl = trimSlash(config.get("read-url", "http://127.0.0.1:18081"));
        this.cdnUrl = trimSlash(config.get("cdn-url", ""));
        this.key = config.get("key", "change-me");
        String rawMode = config.get("mode", "thumbnail").trim().toLowerCase(Locale.ROOT);
        this.mode = "all".equals(rawMode) ? Mode.ALL : Mode.THUMBNAIL;
        this.migrateExisting = config.getBoolean("migrate-existing", true);
    }

    public boolean enabled() {
        return enabled && !operationUrl.isBlank() && !readBase().isBlank();
    }

    public String operationUrl() {
        return operationUrl;
    }

    public String readBase() {
        return cdnUrl.isBlank() ? readUrl : cdnUrl;
    }

    public String key() {
        return key == null ? "" : key;
    }

    public Mode mode() {
        return mode;
    }

    public boolean handlesOriginals() {
        return mode == Mode.ALL;
    }

    public boolean migrateExisting() {
        return migrateExisting;
    }

    private static String trimSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
