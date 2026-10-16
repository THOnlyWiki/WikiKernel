package cc.thonly.wikikernel.plugin.vuecomponents;

import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.plugin.vuecomponents.hook.VueComponentTag;
import cc.thonly.wikikernel.plugin.vuecomponents.hook.VueHeadProvider;
import cc.thonly.wikikernel.plugin.vuecomponents.service.ComponentRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;

/**
 * Vue 3 组件支持（插件 {@code vue-components}）：
 * <ul>
 *     <li>组件 = {@code *.vue} 页面（推荐放在 {@code 组件:} 命名空间；任何命名空间都认），
 *         写标准 SFC（{@code <template>} / {@code <script>} / {@code <style>}）；</li>
 *     <li>同名解析标签：{@code <test-component />} 或 {@code <test-component>…</test-component>}，
 *         属性 → props（{@code :count="3"} 走 JSON），标签内容 = 默认插槽，可嵌套；</li>
 *     <li>浏览器端用本地打包的 Vue 3（{@code vue.esm-browser.prod.js}）编译挂载，
 *         只有用到组件的页面才注入资源（页面属性 {@code vue-components-used}）；</li>
 *     <li>权限沿用「界面页」规则：{@code *.vue} 与 {@code .js} / {@code .css} 一样，
 *         默认只有**界面管理员**能编辑 / 创建 / 移动，可在 特殊:权限配置 按页面或通配动态调整；</li>
 *     <li>开关 {@code features.vue-components}（默认开）：在 {@code /init} 向导与站点设置里都能关。</li>
 * </ul>
 */
public class VueComponentsPlugin implements WikiPlugin {

    /** 功能开关键（与 META-INF/wiki-plugin.properties 的 plugin.featureKey 一致）。 */
    public static final String ENABLED_KEY = "features.vue-components";

    /** 静态实例：解析标签渲染错误文案时随取随用（插件生命周期内单例）。 */
    private static volatile VueComponentsPlugin instance;

    @Autowired
    private ComponentRegistry registry;

    @Autowired
    private MessageSource messageSource;

    @Override
    public void onStart(PluginContext context) {
        instance = this;
        // 组件标签（一个实例承载所有 *.vue 组件；名字动态来自注册表）
        context.register(ParserTag.class, new VueComponentTag(registry));
        // 用到组件的页面才注入 Vue 与加载器（页面级 provider：能读到页面属性）
        context.register(HtmlHeadProvider.class, new VueHeadProvider());
        // 保存 *.vue 页面后立刻刷新组件注册表（新建组件马上可用，不用等缓存过期）
        context.register(EditGuard.class, new VueEditGuard(registry));
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("vue-components", "Vue Components", "1.0.0",
                "Vue 3 single-file components as wiki pages (*.vue / 组件:*.vue) rendered with <component-name /> tags",
                List.of(), "", List.of(), ENABLED_KEY, true);
    }

    /** 本地化文案（插件 i18n；缺失时返回键名本身）。 */
    public String message(String key) {
        if (messageSource == null) {
            return key;
        }
        try {
            String value = messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
            return value == null || value.isBlank() ? key : value;
        } catch (RuntimeException exception) {
            return key;
        }
    }

    /** 插件实例（标签渲染时取消息源用）。 */
    public static VueComponentsPlugin instance() {
        return instance;
    }

    /** 保存 / 删除 {@code *.vue} 页面后让组件注册表缓存失效。 */
    private static final class VueEditGuard implements EditGuard {

        private final ComponentRegistry registry;

        VueEditGuard(ComponentRegistry registry) {
            this.registry = registry;
        }

        @Override
        public int order() {
            return 900;
        }

        @Override
        public Outcome beforeSave(EditContext context) {
            if (context != null && context.title() != null
                    && context.title().toLowerCase(Locale.ROOT).endsWith(".vue")) {
                registry.invalidate();
            }
            return Outcome.allow();
        }
    }
}
