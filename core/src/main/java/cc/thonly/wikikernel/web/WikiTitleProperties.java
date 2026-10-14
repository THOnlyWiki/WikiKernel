package cc.thonly.wikikernel.web;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 标题规则配置（仿 MediaWiki 的 {@code $wgCapitalLinks}）：
 *
 * <pre>
 * wikikernel:
 *   title:
 *     capitalize-first-letter: true
 * </pre>
 */
@ConfigurationProperties(prefix = "wikikernel.title")
public class WikiTitleProperties {

    /** 首字母自动大写；{@code false} = 标题完全区分大小写（仿 {@code $wgCapitalLinks=false}）。 */
    private boolean capitalizeFirstLetter = true;

    /** 绑定完成后同步给 {@link WikiTitles}（标题规范化是静态工具，被服务 / 控制器 / API 共用）。 */
    @PostConstruct
    void apply() {
        WikiTitles.setCapitalizeFirstLetter(capitalizeFirstLetter);
    }

    public boolean isCapitalizeFirstLetter() {
        return capitalizeFirstLetter;
    }

    public void setCapitalizeFirstLetter(boolean capitalizeFirstLetter) {
        this.capitalizeFirstLetter = capitalizeFirstLetter;
    }
}
