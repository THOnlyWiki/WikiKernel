package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 站点信息配置（LocalSettings.yaml 的 {@code wikikernel.site}），例如：
 * <pre>
 * wikikernel:
 *   site: {name: 我的维基}
 * </pre>
 * 站点名称显示在浏览器标题栏（「页面名 - 站点名」）、页眉图标提示、页脚、搜索框占位符、
 * 邮件主题与 API 的 {@code sitename} 等；留空 = 使用默认名 {@code WikiKernel}。
 * <p>
 * 文件里显式写了的每次启动导入数据库设置（{@code site.name}），会覆盖「特殊:权限配置」里的修改。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.site")
public class SiteProperties {

    /** 站点名称（{@code wikikernel.site.name}）。 */
    private String name;

    /** 站点图标 URL（{@code wikikernel.site.favicon}）；留空 = 使用内置 wiki-logo.svg。 */
    private String favicon;

    /** 是否输出 no-referrer meta（{@code wikikernel.site.no-referrer}；默认 false）。 */
    private Boolean noReferrer;
}
