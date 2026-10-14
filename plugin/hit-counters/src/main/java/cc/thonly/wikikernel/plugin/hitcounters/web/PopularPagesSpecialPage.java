package cc.thonly.wikikernel.plugin.hitcounters.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.common.service.PagePropertyValue;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersConfig;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersPlugin;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersSupport;
import org.springframework.context.MessageSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特殊:最多浏览（对应 MediaWiki HitCounters 的 {@code Special:PopularPages}）：
 * 按浏览量列出内容命名空间的页面（不含重定向 / 隐藏页）；可选显示字节数与页面 ID。
 * <p>
 * {@link #name()} 是 URL 里用的稳定名字（中文，改了就换链接），展示名走消息键
 * {@code hitcounters.special.title}（见 {@link #displayName()}）；模板里的文案也都是 {@code #{…}} 键。
 */
public class PopularPagesSpecialPage implements SpecialPage {

    /** 最多列出多少条（对应 QueryPage 的默认条数）。 */
    private static final int LIMIT = 100;

    /** 候选扫描上限（从计数属性里取前 N 个再过滤）。 */
    private static final int SCAN_LIMIT = 500;

    private final HitCountersConfig config;
    private final PagePropertyStore store;
    private final WikiPageStore pages;
    private final MessageSource messageSource;

    public PopularPagesSpecialPage(HitCountersConfig config, PagePropertyStore store, WikiPageStore pages,
                                   MessageSource messageSource) {
        this.config = config;
        this.store = store;
        this.pages = pages;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "最多浏览";
    }

    /** 展示名（可本地化）：{@code hitcounters.special.title}；缺失时显示键名。 */
    @Override
    public String displayName() {
        return HitCountersSupport.text(messageSource, "hitcounters.special.title");
    }

    @Override
    public List<String> aliases() {
        return List.of("PopularPages", "MostViewedPages");
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String template() {
        return "special/popular-pages";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        model.put("showLength", config.addTextLength());
        model.put("showPageId", config.addPageId());
        model.put("disabled", config.disabled());

        List<Map<String, Object>> rows = new ArrayList<>();
        long total = config.disabled() ? 0 : store.sumInt(HitCountersPlugin.VIEWS_PROPERTY);
        model.put("totalViewsText", HitCountersSupport.format(total));
        if (config.disabled()) {
            model.put("rows", rows);
            return;
        }
        for (PagePropertyValue value : store.listByIntDesc(HitCountersPlugin.VIEWS_PROPERTY, SCAN_LIMIT)) {
            PageSnapshot page = pages.byId(value.pageId()).orElse(null);
            if (page == null || !page.contentPage() || page.redirect()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", page.title());
            row.put("views", value.value());
            row.put("viewsText", HitCountersSupport.format(value.value()));
            row.put("length", page.length());
            row.put("pageId", page.id());
            rows.add(row);
            if (rows.size() >= LIMIT) {
                break;
            }
        }
        model.put("rows", rows);
    }
}
