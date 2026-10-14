package cc.thonly.wikikernel.plugin.templatedata;

import cc.thonly.wikikernel.common.extension.ActionApiModule;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.plugin.templatedata.hook.ApiTemplateData;
import cc.thonly.wikikernel.plugin.templatedata.hook.TemplateDataTag;

/**
 * TemplateData 扩展移植（对应 MediaWiki 的 {@code TemplateData} 扩展 0.1.2，REL1_43）：
 * <ul>
 *     <li>{@code <templatedata>} 解析标签：校验并规范化 JSON，把结果写进页面属性
 *     {@code templatedata}（等价于 MediaWiki 的 {@code page_props}），并渲染模板文档表格；</li>
 *     <li>{@code action=templatedata} API：按标题返回模板数据，{@code includeMissingTitles}
 *     时还会从 wikitext 里提取参数名。</li>
 * </ul>
 * 不移植的部分（编辑页 GUI、保存拦截、EventLogging、ResourceLoader 等）见 README。
 */
public class TemplateDataPlugin implements WikiPlugin {

    public static final String PLUGIN_ID = "template-data";

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(PLUGIN_ID, "TemplateData", "0.1.2",
                "TemplateData: the <templatedata> tag and the action=templatedata API");
    }

    @Override
    public void onStart(PluginContext context) {
        TemplateDataConfig config = TemplateDataConfig.from(context.config());
        // 消息包（i18n/messages*.properties）注册给 core：plugin.template-data.* / templatedata-* 都能查
        context.register(MessageBundle.class, new TemplateDataMessages());
        context.register(ParserTag.class, new TemplateDataTag(config));
        context.register(ActionApiModule.class, new ApiTemplateData(config));
        context.logger().log(System.Logger.Level.INFO,
                "已注册 <templatedata> 标签与 action=templatedata（内容语言：" + config.contentLanguage()
                        + "，补 paramOrder：" + config.generateParamOrder() + "）");
    }
}
