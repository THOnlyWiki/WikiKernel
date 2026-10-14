package cc.thonly.wikikernel.plugin.wikiseo.service;

import cc.thonly.wikikernel.plugin.wikiseo.model.SeoImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Twitter Card 生成器（对应 WikiSEO 的 {@code Generator\Plugins\Twitter}）：
 * <ul>
 *     <li>{@code twitter:card}：页面 {@code twitter_card} 参数优先，否则配置的 {@code twitter-card-type}
 *     （默认 {@code summary_large_image}）；</li>
 *     <li>{@code twitter:site}：全局 {@code twitter-site-handle} 优先，没有全局时用页面 {@code twitter_site}
 *     （与原版一致：设置了全局句柄后页面参数被忽略）；</li>
 *     <li>{@code twitter:title} / {@code twitter:description} / {@code twitter:image}：回退到 OpenGraph 数据。</li>
 * </ul>
 * 与 MediaWiki 原版不同：原版的 Twitter 生成器直接继承 OpenGraph，会把 {@code og:*} 再输出一遍
 * （靠 head item 键去重）；WikiKernel 的 Head 是列表，所以这里只输出 {@code twitter:*}，避免重复。
 */
public final class TwitterGenerator implements SeoGenerator {

    /** 生成器名。 */
    public static final String NAME = "Twitter";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> allowedParameters() {
        return List.of("twitter_site", "twitter_card", "description", "image", "image_alt",
                "image_width", "image_height", "title");
    }

    @Override
    public List<String> build(SeoHead head) {
        List<String> items = new ArrayList<>();

        String card = head.has("twitter_card") ? head.get("twitter_card") : head.config().twitterCardType();
        if (!card.isBlank()) {
            items.add(head.meta("twitter:card", card));
        }

        String site = head.config().twitterSiteHandle();
        if (site.isEmpty()) {
            site = head.get("twitter_site");
        }
        if (!site.isEmpty()) {
            items.add(head.meta("twitter:site", site));
        }

        String title = head.pageTitle().isEmpty() ? head.htmlTitle() : head.pageTitle();
        if (!title.isEmpty()) {
            items.add(head.meta("twitter:title", title));
        }
        if (!head.description().isEmpty()) {
            items.add(head.meta("twitter:description", head.description()));
        }
        SeoImage image = head.image();
        if (image.present()) {
            items.add(head.meta("twitter:image", image.url()));
            if (head.has("image_alt")) {
                items.add(head.meta("twitter:image:alt", head.get("image_alt")));
            }
        }
        return items;
    }
}
