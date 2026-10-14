package cc.thonly.wikikernel.common.extension;

/**
 * 搜索结果。
 *
 * @param title   标题
 * @param url     页面地址
 * @param snippet 摘要
 * @param score   相关度（用于聚合排序，越大越靠前）
 */
public record SearchHit(String title, String url, String snippet, double score) {

    public SearchHit {
        title = title == null ? "" : title;
        url = url == null ? "" : url;
        snippet = snippet == null ? "" : snippet;
    }
}
