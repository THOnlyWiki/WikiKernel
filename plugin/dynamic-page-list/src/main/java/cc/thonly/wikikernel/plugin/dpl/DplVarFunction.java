package cc.thonly.wikikernel.plugin.dpl;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;

import java.util.List;

/**
 * {@code {{#dplvar:set|name|value}}} / {@code {{#dplvar:name}}}：
 * 页面渲染内的 DPL 变量（存在 core 的渲染上下文，跨模板共享、渲染结束即丢弃）。
 */
public final class DplVarFunction implements ParserFunction {

    public static final String NAME = "dplvar";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String invoke(ParserFunctionCall call) {
        List<String> args = call.args();
        if (args.isEmpty()) {
            return "";
        }
        String action = call.expand(args.get(0)).trim();
        if ("set".equalsIgnoreCase(action)) {
            if (args.size() < 2) {
                return "";
            }
            String name = call.expand(args.get(1)).trim();
            String value = args.size() > 2 ? call.expand(args.get(2)) : "";
            call.context().store().put(name, value == null ? "" : value);
            return "";
        }
        if ("default".equalsIgnoreCase(action) && args.size() >= 3) {
            String name = call.expand(args.get(1)).trim();
            Object existing = call.context().store().get(name);
            if (existing != null) {
                return String.valueOf(existing);
            }
            String value = call.expand(args.get(2));
            call.context().store().put(name, value == null ? "" : value);
            return value == null ? "" : value;
        }
        Object value = call.context().store().get(action);
        return value == null ? "" : String.valueOf(value);
    }
}
