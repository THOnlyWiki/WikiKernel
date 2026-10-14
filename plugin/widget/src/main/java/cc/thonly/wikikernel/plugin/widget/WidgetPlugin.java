package cc.thonly.wikikernel.plugin.widget;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * Widgets 扩展移植（对应 MediaWiki 的 {@code Widgets} 扩展）：
 * <ul>
 *     <li>微件 = 「微件:」/ {@code Widget:} 命名空间下的页面，内容是 HTML + 迷你 Smarty（{@link SmartyTemplate}）；</li>
 *     <li>在任意页面用 {@code {{#widget:名称|参数=值}}} 调用（{@link WidgetFunction}），输出**原样插入**（可信块，不过白名单）；</li>
 *     <li>参数支持 {@code a.b.c} 点号嵌套、重复键成列表、{@code false} 转布尔。</li>
 * </ul>
 * 开关沿用 core 的功能开关 {@code features.widgets}（默认开启）；命名空间前缀可用插件配置覆盖：
 *
 * <pre>
 * wikikernel:
 *   plugins:
 *     settings:
 *       widget:
 *         prefixes: 微件:,Widget:
 * </pre>
 *
 * 微件页的**代码页展示 / 编辑限制**（小部件编辑者）仍由 core 的命名空间与权限规则负责，
 * 与 MediaWiki 的 {@code editwidgets} 一致。
 */
public class WidgetPlugin implements WikiPlugin {

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）。 */
    public static final String ENABLED_KEY = "features.widgets";

    /** 默认命名空间前缀（与 core 的命名空间表一致）。 */
    public static final String DEFAULT_PREFIXES = "微件:,Widget:";

    @Autowired
    private WikiPageStore wikiPageStore;

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private MessageSource messageSource;

    /** 旧配置兼容（{@code wikikernel.widgets.enabled}，迁移前 core 的 WidgetProperties）。 */
    @Autowired
    private WidgetYamlSettings yamlSettings;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("widget", "Widgets", "1.0.0",
                "Widgets: {{#widget:name|param=value}} renders pages from the Widget namespace (HTML + mini Smarty)");
    }

    @Override
    public void onStart(PluginContext context) {
        String configured = context.config().get("prefixes", DEFAULT_PREFIXES);
        WidgetRenderer renderer = new WidgetRenderer(wikiPageStore, siteConfig, messageSource,
                WidgetSupport.parsePrefixes(configured));
        context.register(MessageBundle.class, new WidgetMessages());
        context.register(ParserFunction.class, new WidgetFunction(renderer));
        // 旧配置 wikikernel.widgets.enabled=false → 站点设置 features.widgets=false（与迁移前 core 的导入一致）
        if (yamlSettings != null && Boolean.FALSE.equals(yamlSettings.getEnabled())) {
            siteConfig.set(ENABLED_KEY, Boolean.FALSE.toString());
        }
        context.logger().log(System.Logger.Level.INFO, siteConfig.getBoolean(ENABLED_KEY, true)
                ? "已启用微件：{{#widget:名称|参数=值}}（命名空间前缀 " + String.join(" / ", renderer.prefixes()) + "）"
                : "微件已关闭（features.widgets=false）：{{#widget:…}} 显示「未启用」提示");
    }

    /**
     * 插件消息：微件的渲染错误提示。
     * <p>
     * 文案在插件自己的资源文件里（不再写成 Java 里的 {@code Map} 字面量）：
     * <pre>
     * src/main/resources/i18n/messages.properties     中文（默认语言）
     * src/main/resources/i18n/messages_en.properties  英文
     * </pre>
     * 键与迁移前 core 的 {@code widget.error.*} 一致；漏翻的键由 core 的消息源回退成翻译键本身（不会显示中文）。
     */
    static final class WidgetMessages extends ResourceMessageBundle {
    }
}
