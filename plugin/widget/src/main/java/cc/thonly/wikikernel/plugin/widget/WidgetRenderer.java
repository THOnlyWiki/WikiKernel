package cc.thonly.wikikernel.plugin.widget;

import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.util.HtmlUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 微件渲染（仿 MediaWiki Widgets 扩展，原 core 实现迁入插件）：
 * 微件就是「微件:」命名空间下的页面，内容是 HTML + {@link SmartyTemplate 迷你 Smarty}；
 * 在其它页面用 {@code {{#widget:名称|参数=值}}} 调用，输出**原样**插入页面（不经过 HTML 白名单）。
 * <p>
 * 编译结果按内容缓存（内容即 key，编辑后自动失效）。
 */
public class WidgetRenderer {

    private static final Logger log = LoggerFactory.getLogger(WidgetRenderer.class);

    /** 编译缓存：模板内容 → 编译结果（LRU，最多 256 条）。 */
    private final Map<String, SmartyTemplate> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SmartyTemplate> eldest) {
                    return size() > 256;
                }
            });

    private final WikiPageStore pages;
    private final SiteConfig siteConfig;
    private final MessageSource messageSource;
    private final List<String> prefixes;

    public WidgetRenderer(WikiPageStore pages, SiteConfig siteConfig, MessageSource messageSource,
                          List<String> prefixes) {
        this.pages = pages;
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
        this.prefixes = prefixes == null || prefixes.isEmpty() ? List.of("微件:", "Widget:") : List.copyOf(prefixes);
    }

    /** 当前使用的命名空间前缀（日志 / 测试用）。 */
    public List<String> prefixes() {
        return prefixes;
    }

    /** 微件功能是否启用（站点设置 {@code features.widgets}，默认开启）。 */
    public boolean enabled() {
        return siteConfig == null || siteConfig.getBoolean(WidgetPlugin.ENABLED_KEY, true);
    }

    /** 渲染微件；找不到或出错时返回错误提示 HTML（不抛出）。 */
    public String render(String name, Map<String, Object> params) {
        String widgetName = name == null ? "" : name.trim();
        if (widgetName.isEmpty()) {
            return error("widget.error.noname");
        }
        if (!enabled()) {
            return error("widget.error.disabled");
        }
        Optional<PageSnapshot> page = resolve(widgetName);
        if (page.isEmpty()) {
            return error("widget.error.notfound", widgetName);
        }
        String content = pages.content(page.get().id()).orElse("");
        try {
            SmartyTemplate template = cache.computeIfAbsent(content, SmartyTemplate::compile);
            return template.render(params);
        } catch (RuntimeException exception) {
            log.warn("微件「{}」渲染失败：{}", widgetName, exception.getMessage());
            return error("widget.error.render", widgetName);
        }
    }

    /** 名称 → 页面：已带命名空间前缀按原样查，否则依次尝试配置的前缀（微件: / Widget:）。 */
    private Optional<PageSnapshot> resolve(String name) {
        if (name.indexOf(':') >= 0) {
            Optional<PageSnapshot> direct = pages.visibleByTitle(name);
            if (direct.isPresent()) {
                return direct;
            }
        }
        for (String prefix : prefixes) {
            Optional<PageSnapshot> page = pages.visibleByTitle(prefix + name);
            if (page.isPresent()) {
                return page;
            }
        }
        return Optional.empty();
    }

    /** 缓存里的模板数量（测试用）。 */
    int cachedTemplates() {
        return cache.size();
    }

    /**
     * 错误提示 HTML：文案按翻译键从插件自己的消息包（{@code i18n/messages*.properties}）取，
     * 取不到时（没有 MessageSource）显示键名本身——与 core 的「漏翻显示键名」策略一致。
     */
    private String error(String key, Object... args) {
        String message = this.messageSource == null
                ? key
                : this.messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
        return "<div class=\"error\">" + HtmlUtils.htmlEscape(message) + "</div>";
    }
}
