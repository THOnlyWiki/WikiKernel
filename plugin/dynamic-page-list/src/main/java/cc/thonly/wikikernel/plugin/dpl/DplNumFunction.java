package cc.thonly.wikikernel.plugin.dpl;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{#dplnum:text}}}：取文本里第一个数字（DPL 的 {@code #dplnum}）。
 * 没有数字时返回空串。
 */
public final class DplNumFunction implements ParserFunction {

    public static final String NAME = "dplnum";

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

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
        String text = call.expand(args.get(0));
        Matcher matcher = NUMBER.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group() : "";
    }
}
