package cc.thonly.wikikernel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 特殊页面配置：
 *
 * <pre>
 * wikikernel:
 *   special-page:
 *     prefixes:
 *       - "特殊:"
 *       - "Special:"
 * </pre>
 */
@ConfigurationProperties(prefix = "wikikernel.special-page")
public class SpecialPageProperties {

    /** 特殊页前缀（默认中英双前缀，可增删）。 */
    private List<String> prefixes = new ArrayList<>(List.of("特殊:", "Special:"));

    public List<String> getPrefixes() {
        return this.prefixes;
    }

    public void setPrefixes(List<String> prefixes) {
        this.prefixes = prefixes;
    }
}
