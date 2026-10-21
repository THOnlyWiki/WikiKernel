package cc.thonly.wikikernel.plugin.comments.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code <comment />} / {@code <comments />}（仿 MediaWiki Comments 扩展）：
 * 标签本身从正文剥离，只在页面属性里留下标记，评论区由 {@link CommentSectionHook}
 * 在正文下方渲染。
 * <ul>
 *     <li>{@code <comment />}：手动开启本页评论区（自动显示关闭时也生效）；</li>
 *     <li>{@code <comment disabled />}：关闭本页自动评论区（优先于普通标签）；</li>
 *     <li>{@code disabled="false"} / {@code enabled="false"} 等值按布尔值处理。</li>
 * </ul>
 * 关闭「评论」功能时：标签仍然剥离（页面不显示评论区）。
 */
public class CommentTag implements ParserTag {

    /** 页面属性键：本页含评论标签（手动开启）。 */
    public static final String PROPERTY = "comments";

    /** 页面属性键：本页显式关闭自动评论区。 */
    public static final String DISABLED_PROPERTY = "comments-disabled";

    @Override
    public String name() {
        return "comment";
    }

    /** 标签名兼容写法（{@code <comments />}）。 */
    @Override
    public List<String> names() {
        return List.of("comment", "comments");
    }

    /** 自闭合标签，没有内容（{@code expandBody=false} 保证不被模板展开影响）。 */
    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        if (disabled(call.attributes())) {
            call.context().setProperty(DISABLED_PROPERTY, "1");
        } else {
            call.context().setProperty(PROPERTY, "1");
        }
        return "";
    }

    private static boolean disabled(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return false;
        }
        if (attributes.containsKey("disabled")) {
            return flag(attributes.get("disabled"));
        }
        if (attributes.containsKey("off")) {
            return flag(attributes.get("off"));
        }
        if (attributes.containsKey("enabled")) {
            return !flag(attributes.get("enabled"));
        }
        return false;
    }

    /** 空值（裸属性）视为 true；明确写 false / 0 / off / no 才是 false。 */
    private static boolean flag(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !(normalized.equals("false") || normalized.equals("0")
                || normalized.equals("off") || normalized.equals("no"));
    }
}
