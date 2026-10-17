package cc.thonly.wikikernel.plugin.cite;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.plugin.cite.hook.CiteHeadProvider;
import cc.thonly.wikikernel.plugin.cite.hook.CiteMessages;
import cc.thonly.wikikernel.plugin.cite.hook.RefTag;
import cc.thonly.wikikernel.plugin.cite.hook.ReferencesTag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;

/**
 * Cite 扩展（插件 {@code cite}）：把 {@code <ref>…</ref>} / {@code <references/>} 脚注
 * **从 core 拆出来**，行为与迁移前一致：
 * <ul>
 *     <li>{@link RefTag {@code <ref>}}：编号按首次出现、同名合并（{@code <ref name="x" />} 复用）、
 *         先使用后定义、错误内联提示（空内容 / 重名内容不同 / 未定义 / 自闭合没写 name）；</li>
 *     <li>{@link ReferencesTag {@code <references/>}}（别名 {@code <reference/>}）：在该位置出
 *         {@code <ol class="references">} 列表，条目正文按 wikitext 渲染并带回跳箭头；只有第一个位置出列表；</li>
 *     <li>标记 / 列表用 core 的 {@code WikiRenderContext.deferHtml} 延迟到整页 HTML 收尾时生成
 *         （那时才收得齐所有引用），因此正文里的 {@code <ref>} 在目录 / 摘要里不会显示成 {@code [1]}；</li>
 *     <li>样式（{@code cite.css}）只在用到引用的页面注入（页面属性 {@code cite-used}）；
 *         开关 {@code features.cite}（默认开）在 {@code /init} 与「特殊:权限配置 → 插件设置」里都能关。</li>
 * </ul>
 */
public class CitePlugin implements WikiPlugin {

    public static final String ENABLED_KEY = "features.cite";

    @Autowired
    private RefTag refTag;

    @Autowired
    private ReferencesTag referencesTag;

    @Autowired
    private MessageSource messageSource;

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new CiteMessages());
        context.register(ParserTag.class, refTag);
        context.register(ParserTag.class, referencesTag);
        context.register(HtmlHeadProvider.class, new CiteHeadProvider());
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("cite", "Cite", "1.0.0",
                "Citations via <ref>…</ref> and <references />, extracted from core (MediaWiki Cite port)",
                List.of(), "", List.of(), ENABLED_KEY, true);
    }

    /** 本地化文案（插件 i18n；缺失时返回键名本身），{@code $1} 用参数替换。 */
    public String message(String key, String argument) {
        String text = key;
        if (messageSource != null) {
            try {
                String value = messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
                text = value == null || value.isBlank() ? key : value;
            } catch (RuntimeException ignored) {
                text = key;
            }
        }
        return argument == null ? text : text.replace("$1", argument);
    }

    /** 供标签内部使用的简写（无参数）。 */
    public List<String> messages() {
        return List.of();
    }
}
