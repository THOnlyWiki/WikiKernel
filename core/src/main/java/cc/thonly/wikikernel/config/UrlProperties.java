package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 站点 URL 配置（LocalSettings.yaml → {@code wikikernel.urls}）：
 *
 * <pre>
 * wikikernel:
 *   urls:
 *     page-prefix: wiki        # 页面路径前缀：wiki（默认）→ /wiki/首页；空串或 / → 裸根路径 /首页
 *     special-prefix: 特殊      # 特殊页前缀（生成的链接用）：特殊（默认）或 Special 等
 * </pre>
 *
 * <ul>
 *     <li>{@code page-prefix} 空串 = **裸根部署**：页面直接是 {@code /首页}，内部路由仍走
 *     {@code /wiki/}（由 {@code WikiUrlRewriteFilter} 转发），{@code /api}、{@code /css} 等
 *     保留路径不受影响；</li>
 *     <li>{@code special-prefix} 只影响**生成的链接**（如 {@code /wiki/Special:搜索}）；
 *     输入侧 {@code 特殊:} / {@code Special:} 两种前缀始终都能识别。</li>
 * </ul>
 *
 * 这两个键只在 LocalSettings.yaml 里配置（启动时直接生效；不支持在站点设置页里改）。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.urls")
public class UrlProperties {

    /** 页面路径前缀（{@code wikikernel.urls.page-prefix}）；null = 默认 {@code wiki}。 */
    private String pagePrefix;

    /** 特殊页前缀（{@code wikikernel.urls.special-prefix}）；null = 默认 {@code 特殊}。 */
    private String specialPrefix;
}
