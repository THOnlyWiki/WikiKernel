package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：爬虫访问策略（供 core 的 HTTP 过滤器和 {@code /robots.txt} 使用）。
 * <p>
 * 插件可以实现它来决定「哪些 User-Agent 不应访问本站」，并把自己的 robots 规则交给
 * {@code /robots.txt}。core 只负责执行策略，不认识具体的 AI 厂商；没有插件时不影响请求。
 * <p>
 * 返回的 User-Agent 匹配由策略自己完成；core 只会在普通页面/API 请求上调用
 * {@link #blocks(String)}，{@code /robots.txt} 本身始终放行，方便爬虫读取规则。
 */
public interface CrawlerPolicy extends Extension {

    /**
     * 是否拦截这个 User-Agent。
     *
     * @param userAgent 请求的原始 User-Agent（可能为空）
     * @return true = 返回 403；false = 放行
     */
    boolean blocks(String userAgent);

    /**
     * 追加到 {@code /robots.txt} 的规则（可空）。应只包含本策略负责的 User-agent 段，
     * 不要重复写全站默认规则；返回空串表示不追加。
     */
    default String robotsTxt() {
        return "";
    }
}
