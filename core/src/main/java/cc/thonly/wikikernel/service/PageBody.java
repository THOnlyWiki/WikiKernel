package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.wikitext.WikiLinkScanner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次页面正文渲染的完整产出（页面正文缓存里存的就是它）：
 * HTML + 渲染元信息（显示标题 / 分类 / 跨语言链接 / 页面属性）。
 * <p>
 * 元信息必须和 HTML 一起缓存：命中缓存时要跳过整次渲染，这些值就再也算不出来了
 * （分类栏、侧栏「语言」、{@code notitle} 等插件页面属性都靠它们）。
 *
 * @param revisionId   产出该 HTML 的版本号（页面 {@code latestRevisionId}）——缓存命中时用它比对版本
 * @param html         渲染后的正文 HTML（已执行插件 {@code PageRenderHook}；不含按用户注入的段落编辑链接）
 * @param displayTitle {@code {{DISPLAYTITLE:}}}} 指定的显示标题；没有则为 {@code null}
 * @param categories   正文里的分类（已从 HTML 剥离）
 * @param interwikiLinks 正文里的跨语言链接（已从 HTML 剥离）
 * @param properties   渲染时通过扩展点写入的页面属性（如 {@code notitle}、SEO 元数据）
 */
public record PageBody(long revisionId,
                       String html,
                       String displayTitle,
                       List<WikiLinkScanner.CategoryRef> categories,
                       List<InterwikiService.Link> interwikiLinks,
                       Map<String, String> properties) {

    public PageBody {
        html = html == null ? "" : html;
        categories = categories == null ? List.of() : List.copyOf(categories);
        interwikiLinks = interwikiLinks == null ? List.of() : List.copyOf(interwikiLinks);
        properties = copyProperties(properties);
    }

    /** 只有 HTML 的最小产出（插件桥 {@code PageCache.put} 用：元信息留空）。 */
    public static PageBody of(long revisionId, String html) {
        return new PageBody(revisionId, html, null, List.of(), List.of(), Map.of());
    }

    /** 替换 HTML、保留其余元信息（插件桥在「同版本」上覆盖正文时用）。 */
    public PageBody withHtml(String newHtml) {
        return new PageBody(revisionId, newHtml, displayTitle, categories, interwikiLinks, properties);
    }

    /** 属性表可能来自渲染上下文，容忍 null 键 / 值（Redis 反序列化也要能过）。 */
    private static Map<String, String> copyProperties(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                values.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(values);
    }
}
