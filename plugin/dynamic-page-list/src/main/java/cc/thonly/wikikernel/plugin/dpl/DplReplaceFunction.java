package cc.thonly.wikikernel.plugin.dpl;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code {{#dplreplace:text|pattern|replacement}}}：正则替换（对应 DPL 的 {@code #dplreplace}）。
 * <p>
 * 兼容 DPL 常见的 {@code /pattern/} 写法；正则非法时原样返回文本，避免整页渲染失败。
 */
public final class DplReplaceFunction implements ParserFunction {

    public static final String NAME = "dplreplace";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String invoke(ParserFunctionCall call) {
        List<String> args = call.args();
        if (args.size() < 2) {
            return args.isEmpty() ? "" : call.expand(args.get(0));
        }
        String text = call.expand(args.get(0));
        String pattern = call.expand(args.get(1));
        String replacement = args.size() > 2 ? call.expand(args.get(2)) : "";
        pattern = stripDelimiters(pattern);
        try {
            return Pattern.compile(pattern).matcher(text).replaceAll(replacement);
        } catch (PatternSyntaxException exception) {
            return text;
        }
    }

    /** DPL 允许写 {@code /pattern/i}：去掉首尾的斜杠与末尾 flags（Java 侧统一忽略 flags）。 */
    private static String stripDelimiters(String pattern) {
        String value = pattern == null ? "" : pattern.trim();
        if (value.length() >= 2 && value.charAt(0) == '/') {
            int last = value.lastIndexOf('/');
            if (last > 0) {
                return value.substring(1, last);
            }
        }
        return value;
    }
}
