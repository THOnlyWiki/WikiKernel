package cc.thonly.wikikernel.plugin.categorytree.hook;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析器函数 {@code {{#categorytree:分类名|mode=all|depth=2|hideroot|…}}}
 * （对应原版 {@code Hooks::parserFunction}）。
 * <p>
 * 参数规则与原版一致：第一个参数是分类名；其余参数要么是 {@code 键=值}，要么是裸标记（该键 = true）；
 * {@code 键="值"} 两端成对的双引号会被剥掉。
 * <p>
 * 注意：core 的解析器函数返回值会继续按 wikitext 处理（&lt;div&gt; 里的 {@code data-ct-*} /
 * {@code aria-*} 属性会被 HTML 白名单过滤），所以这里输出的是等价的 {@code <categorytree>} 标签文本，
 * 交给 {@link CategoryTreeNestedTag} 渲染成可信 HTML。
 */
public final class CategoryTreeFunction implements ParserFunction {

    /** 函数名。 */
    public static final String FUNCTION_NAME = "categorytree";

    /** {@code 键=值}（键非空白开头；值允许为空）。 */
    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*(\\S.*?)\\s*=\\s*(.*?)\\s*$", Pattern.DOTALL);

    /** 值两端成对的双引号。 */
    private static final Pattern QUOTED_VALUE = Pattern.compile("^\"\\s*(.*?)\\s*\"$", Pattern.DOTALL);

    private final CategoryTreeRenderer renderer;

    public CategoryTreeFunction(CategoryTreeRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return FUNCTION_NAME;
    }

    @Override
    public String invoke(ParserFunctionCall call) {
        List<String> args = call.args();
        String category = args.isEmpty() ? "" : call.expand(args.get(0));
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 1; i < args.size(); i++) {
            String part = call.expand(args.get(i));
            Matcher matcher = KEY_VALUE.matcher(part == null ? "" : part);
            if (matcher.matches()) {
                String key = matcher.group(1);
                String value = matcher.group(2);
                Matcher quoted = QUOTED_VALUE.matcher(value);
                attributes.put(key, quoted.matches() ? quoted.group(1) : value);
            } else {
                String key = part == null ? "" : part.trim();
                if (!key.isEmpty()) {
                    // 裸标记 = 该键 true
                    attributes.put(key, "true");
                }
            }
        }
        return this.renderer.tagWikitext(attributes, category);
    }
}
