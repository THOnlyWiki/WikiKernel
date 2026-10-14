package cc.thonly.wikikernel.plugin.variables;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;

/**
 * Variables 扩展移植（对应 MediaWiki 的 {@code Variables} 扩展）：
 * 页面级变量的解析器函数 {@code #vardefine} / {@code #vardefineecho} / {@code #var} /
 * {@code #varexists} / {@code #var_final}。
 * <p>
 * 变量存在渲染上下文（{@code WikiRenderContext.store()}）里：页面级作用域、跨模板共享、
 * 渲染结束后丢弃——与 MediaWiki 的 Parser 级变量生命周期一致。
 * {@code #var_final} 通过 core 的延迟占位在解析结束后取最终值（值按 wikitext 展开）。
 * <p>
 * 本地化文案（插件名 / 描述）在插件自己的消息包里（{@link VariablesMessages}，
 * 见 {@code i18n/messages*.properties}）；解析器函数名是页面语法，不随界面语言变化。
 */
public class VariablesPlugin implements WikiPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("variables", "Variables", "2.6.0",
                "Page-scoped parser functions #vardefine / #vardefineecho / #var / #varexists / #var_final");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new VariablesMessages());
        register(context, new VariablesFunctions.VarDefine());
        register(context, new VariablesFunctions.VarDefineEcho());
        register(context, new VariablesFunctions.Var());
        register(context, new VariablesFunctions.VarExists());
        register(context, new VariablesFunctions.VarFinal());
        context.logger().log(System.Logger.Level.INFO,
                "已注册解析器函数 #vardefine / #vardefineecho / #var / #varexists / #var_final");
    }

    private static void register(PluginContext context, ParserFunction function) {
        context.register(ParserFunction.class, function);
    }
}
