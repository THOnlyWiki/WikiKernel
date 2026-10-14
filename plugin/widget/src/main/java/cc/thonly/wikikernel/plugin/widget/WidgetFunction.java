package cc.thonly.wikikernel.plugin.widget;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code {{#widget:名称|参数=值}}}（仿 MediaWiki Widgets 扩展的 {@code #widget} 解析器函数）：
 * <ul>
 *     <li>第一个参数是微件名（可写完整前缀 {@code 微件:示例}，也可省略）；</li>
 *     <li>其余参数 {@code key=value} 支持 {@code a.b.c} 点号嵌套、重复键成列表；</li>
 *     <li>没有 {@code =} 的参数视为布尔真（值为 {@code false} 时转布尔假）；</li>
 *     <li>输出登记为**可信块**（{@link ParserFunctionCall#trustedHtml}），原样插入正文。</li>
 * </ul>
 */
public class WidgetFunction implements ParserFunction {

    private final WidgetRenderer renderer;

    public WidgetFunction(WidgetRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "widget";
    }

    @Override
    public String invoke(ParserFunctionCall call) {
        List<String> args = call.args();
        if (args.isEmpty() || args.get(0).trim().isEmpty()) {
            return call.trustedHtml(renderer.render("", Map.of()));
        }
        String name = call.expand(args.get(0).trim()).trim();
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 1; i < args.size(); i++) {
            String part = args.get(i);
            int equals = topLevelEquals(part);
            if (equals > 0) {
                String key = call.expand(part.substring(0, equals).trim()).trim();
                WidgetSupport.put(params, key, WidgetSupport.value(call.expand(part.substring(equals + 1).trim())));
            } else {
                String key = call.expand(part.trim()).trim();
                if (!key.isEmpty()) {
                    WidgetSupport.put(params, key, Boolean.TRUE);
                }
            }
        }
        return call.trustedHtml(renderer.render(name, params));
    }

    /** 顶层（不在嵌套结构里）的 {@code =} 位置。 */
    private static int topLevelEquals(String text) {
        if (text == null) {
            return -1;
        }
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '[' || character == '{' || character == '(') {
                depth++;
            } else if (character == ']' || character == '}' || character == ')') {
                depth--;
            } else if (character == '=' && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
