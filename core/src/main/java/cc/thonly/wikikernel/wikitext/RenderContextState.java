package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.common.extension.BehaviorSwitch;
import cc.thonly.wikikernel.common.extension.FileEmbedRenderer;
import cc.thonly.wikikernel.common.extension.MagicWord;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link WikiRenderContext} 的 core 实现：一次渲染内的上下文（页面属性 / 渲染变量 / 延迟占位）
 * 与插件扩展点索引（解析器函数 / 魔术字 / 行为开关 / 标签）。
 */
public final class RenderContextState implements WikiRenderContext {

    private final String pageTitle;
    private final Map<String, Object> store = new LinkedHashMap<>();
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final List<Supplier<String>> deferred = new ArrayList<>();
    /** 『整页 HTML 收尾阶段』再替换的占位（{@code deferHtml}，见脚注 / Cite 扩展）。 */
    private final List<Supplier<String>> deferredHtml = new ArrayList<>();
    private final Map<String, ParserFunction> functions = new LinkedHashMap<>();
    private final Map<String, MagicWord> magicWords = new LinkedHashMap<>();
    private final Map<String, BehaviorSwitch> switches = new LinkedHashMap<>();
    private final Map<String, ParserTag> tags = new LinkedHashMap<>();
    private final List<ParserTag> rawTags = new ArrayList<>();
    private final List<ParserTag> expandableTags = new ArrayList<>();
    private final List<FileEmbedRenderer> fileEmbeds;

    public RenderContextState(String pageTitle, List<ParserFunction> parserFunctions,
                              List<MagicWord> magicWords, List<BehaviorSwitch> behaviorSwitches,
                              List<ParserTag> parserTags) {
        this(pageTitle, parserFunctions, magicWords, behaviorSwitches, parserTags, List.of());
    }

    public RenderContextState(String pageTitle, List<ParserFunction> parserFunctions,
                              List<MagicWord> magicWords, List<BehaviorSwitch> behaviorSwitches,
                              List<ParserTag> parserTags, List<FileEmbedRenderer> fileEmbeds) {
        this.pageTitle = pageTitle == null ? "" : pageTitle;
        this.fileEmbeds = fileEmbeds == null ? List.of() : List.copyOf(fileEmbeds);
        if (parserFunctions != null) {
            for (ParserFunction function : parserFunctions) {
                putAll(functions, function.name(), function, function.aliases());
            }
        }
        if (magicWords != null) {
            for (MagicWord word : magicWords) {
                putAll(this.magicWords, word.name(), word, word.aliases());
            }
        }
        if (behaviorSwitches != null) {
            for (BehaviorSwitch behavior : behaviorSwitches) {
                putAll(switches, behavior.name(), behavior, behavior.aliases());
            }
        }
        if (parserTags != null) {
            for (ParserTag tag : parserTags) {
                for (String name : tag.names()) {
                    if (name != null && !name.isBlank()) {
                        tags.putIfAbsent(name.toLowerCase(Locale.ROOT), tag);
                    }
                }
                if (!tag.expandBody()) {
                    rawTags.add(tag);
                } else {
                    expandableTags.add(tag);
                }
            }
        }
    }

    private static <T> void putAll(Map<String, T> target, String name, T value, List<String> aliases) {
        if (name != null && !name.isBlank()) {
            target.putIfAbsent(name.toUpperCase(Locale.ROOT), value);
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    target.putIfAbsent(alias.toUpperCase(Locale.ROOT), value);
                }
            }
        }
    }

    @Override
    public String pageTitle() {
        return pageTitle;
    }

    @Override
    public Map<String, Object> store() {
        return store;
    }

    @Override
    public void setProperty(String name, String value) {
        if (name != null && !name.isBlank()) {
            properties.put(name.trim(), value == null ? "" : value);
        }
    }

    @Override
    public String property(String name, String defaultValue) {
        String value = name == null ? null : properties.get(name.trim());
        return value == null ? defaultValue : value;
    }

    @Override
    public Map<String, String> properties() {
        return Map.copyOf(properties);
    }

    @Override
    public String deferWikitext(Supplier<String> resolver) {
        deferred.add(resolver);
        return "\uE008" + (deferred.size() - 1) + "\uE008";
    }

    @Override
    public String deferHtml(Supplier<String> htmlSupplier) {
        deferredHtml.add(htmlSupplier);
        return "\uE009" + (deferredHtml.size() - 1) + "\uE009";
    }

    // ==================== core 内部使用 ====================

    /** 延迟占位数量（解析收尾时逐个替换）。 */
    int deferredCount() {
        return deferred.size();
    }

    Supplier<String> deferredAt(int index) {
        return index >= 0 && index < deferred.size() ? deferred.get(index) : null;
    }

    /** {@code deferHtml} 占位数量 / 单个取用（见 {@code WikitextRenderer} 收尾阶段）。 */
    int deferredHtmlCount() {
        return deferredHtml.size();
    }

    Supplier<String> deferredHtmlAt(int index) {
        return index >= 0 && index < deferredHtml.size() ? deferredHtml.get(index) : null;
    }

    /** 插件解析器函数（名字大写匹配；未注册返回 null）。 */
    ParserFunction parserFunction(String name) {
        return name == null ? null : functions.get(name.toUpperCase(Locale.ROOT));
    }

    /** 插件魔术字（名字大写匹配；未注册返回 null）。 */
    MagicWord magicWord(String name) {
        return name == null ? null : magicWords.get(name.toUpperCase(Locale.ROOT));
    }

    /** 插件行为开关（名字大写匹配；未注册返回 null）。 */
    BehaviorSwitch behaviorSwitch(String name) {
        return name == null ? null : switches.get(name.toUpperCase(Locale.ROOT));
    }

    /** 插件标签（名字小写匹配；未注册返回 null）。 */
    ParserTag parserTag(String name) {
        return name == null ? null : tags.get(name.toLowerCase(Locale.ROOT));
    }

    /** 先保护、后渲染（不参与正文展开）的标签：{@code <templatedata>} 这类。 */
    List<ParserTag> rawTags() {
        return rawTags;
    }

    /** 参与正文展开、展开后再提取的标签：{@code <seo>} 这类。 */
    List<ParserTag> expandableTags() {
        return expandableTags;
    }

    /** 插件提供的媒体文件嵌入渲染器（多媒体预览等；按注册顺序）。 */
    List<FileEmbedRenderer> fileEmbedRenderers() {
        return fileEmbeds;
    }
}
