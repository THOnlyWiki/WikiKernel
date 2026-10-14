package cc.thonly.wikikernel.plugin.wikiseo.model;

/**
 * 解析后的 SEO 图片（OpenGraph / Schema.org / Twitter 共用）。
 *
 * @param url    图片 URL（空串 = 没有图片）
 * @param width  像素宽度（0 = 未知）
 * @param height 像素高度（0 = 未知）
 */
public record SeoImage(String url, int width, int height) {

    public static final SeoImage NONE = new SeoImage("", 0, 0);

    public SeoImage {
        url = url == null ? "" : url.trim();
        width = Math.max(0, width);
        height = Math.max(0, height);
    }

    /** 是否有可用的图片地址。 */
    public boolean present() {
        return !url.isEmpty();
    }
}
