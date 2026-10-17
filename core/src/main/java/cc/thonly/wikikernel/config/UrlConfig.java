package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.web.WikiUrlRewriteFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code wikikernel.urls.*}（{@link UrlProperties}）应用到 {@link WikiUrls}：
 * 页面路径前缀（默认 {@code /wiki/}，可配 {@code /p/} 或裸根 {@code /}）与特殊页前缀
 * （默认 {@code 特殊}，可配 {@code Special} 等）。
 * <p>
 * 裸根部署时 {@link WikiUrlRewriteFilter} 会把
 * {@code /首页} 这类请求转发到内部 {@code /wiki/} 路由。
 */
@Configuration
public class UrlConfig {

    private static final Logger log = LoggerFactory.getLogger(UrlConfig.class);

    public UrlConfig(UrlProperties properties) {
        WikiUrls.configure(properties.getPagePrefix(), properties.getSpecialPrefix());
        if (!WikiUrls.DEFAULT_PAGE_PREFIX.equals(WikiUrls.pagePrefix())
                || !WikiUrls.DEFAULT_SPECIAL_PREFIX.equals(WikiUrls.specialPrefix())) {
            log.info("已应用站点 URL 配置：页面路径前缀 = {}，特殊页前缀 = {}",
                    WikiUrls.pagePrefix(), WikiUrls.specialPrefix());
        }
    }
}
