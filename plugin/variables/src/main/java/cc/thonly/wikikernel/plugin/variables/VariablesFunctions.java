package cc.thonly.wikikernel.plugin.variables;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;

import java.util.List;

/**
 * Variables 扩展的解析器函数实现（语义对齐 MediaWiki {@code ExtVariables}，2.6.0）：
 * <ul>
 *     <li>{@code #vardefine: 名字 | 值}：定义变量，无输出；</li>
 *     <li>{@code #vardefineecho: 名字 | 值}：定义并输出值；</li>
 *     <li>{@code #var: 名字 | 默认值}：取变量值（不存在或为空串时用默认值；默认值按需展开）；</li>
 *     <li>{@code #varexists: 名字 | 有值 | 无值}：判断变量是否存在（只展开选中的分支；默认 1 / 空）；</li>
 *     <li>{@code #var_final: 名字 | 默认值}：解析结束后取最终值（值按 wikitext 展开）。</li>
 * </ul>
 * 名字与值都会 trim（与 MediaWiki 一致）。
 */
public final class VariablesFunctions {

    private VariablesFunctions() {
    }

    /** 取第 index 个参数（原文），并对参数按当前 frame 展开后 trim。 */
    private static String expanded(ParserFunctionCall call, int index) {
        List<String> args = call.args();
        if (index < 0 || index >= args.size()) {
            return "";
        }
        return call.expand(args.get(index)).trim();
    }

    private static String nameOf(ParserFunctionCall call) {
        return expanded(call, 0);
    }

    private static String valueOf(ParserFunctionCall call) {
        return expanded(call, 1);
    }

    private static void define(WikiRenderContext context, String name, String value) {
        context.store().put(name, value);
    }

    /** {@code #vardefine: 名字 | 值}：定义变量，无输出。 */
    public static final class VarDefine implements ParserFunction {

        @Override
        public String name() {
            return "vardefine";
        }

        @Override
        public String invoke(ParserFunctionCall call) {
            define(call.context(), nameOf(call), valueOf(call));
            return "";
        }
    }

    /** {@code #vardefineecho: 名字 | 值}：定义变量并输出值。 */
    public static final class VarDefineEcho implements ParserFunction {

        @Override
        public String name() {
            return "vardefineecho";
        }

        @Override
        public String invoke(ParserFunctionCall call) {
            String value = valueOf(call);
            define(call.context(), nameOf(call), value);
            return value;
        }
    }

    /** {@code #var: 名字 | 默认值}：取变量值；不存在或为空串时返回默认值（默认值按需展开）。 */
    public static final class Var implements ParserFunction {

        @Override
        public String name() {
            return "var";
        }

        @Override
        public String invoke(ParserFunctionCall call) {
            Object value = call.context().store().get(nameOf(call));
            if (value == null || String.valueOf(value).isEmpty()) {
                return call.args().size() > 1 ? expanded(call, 1) : "";
            }
            return String.valueOf(value);
        }
    }

    /** {@code #varexists: 名字 | 有值 | 无值}：变量是否存在（只展开选中的分支）。 */
    public static final class VarExists implements ParserFunction {

        @Override
        public String name() {
            return "varexists";
        }

        @Override
        public String invoke(ParserFunctionCall call) {
            boolean exists = call.context().store().containsKey(nameOf(call));
            if (exists) {
                return call.args().size() > 1 ? expanded(call, 1) : "1";
            }
            return call.args().size() > 2 ? expanded(call, 2) : "";
        }
    }

    /**
     * {@code #var_final: 名字 | 默认值}：注册延迟占位，解析结束后取变量的最终值
     * （值按 wikitext 展开；变量最终不存在时用默认值）。
     */
    public static final class VarFinal implements ParserFunction {

        @Override
        public String name() {
            return "var_final";
        }

        @Override
        public String invoke(ParserFunctionCall call) {
            String name = nameOf(call);
            // 与 MediaWiki 一致：默认值在调用时展开（即使最终用不到）
            String defaultValue = call.args().size() > 1 ? expanded(call, 1) : "";
            WikiRenderContext context = call.context();
            return context.deferWikitext(() -> {
                Object value = context.store().get(name);
                return value == null ? defaultValue : String.valueOf(value);
            });
        }
    }
}
