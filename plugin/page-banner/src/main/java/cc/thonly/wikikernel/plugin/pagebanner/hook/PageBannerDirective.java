package cc.thonly.wikikernel.plugin.pagebanner.hook;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.plugin.pagebanner.service.PageBannerRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code {{PAGEBANNER:文件名|caption=说明}}}（仿 MediaWiki WikidataPageBanner 的解析器函数）。
 * <p>
 * 与 MediaWiki 原版一致，指令**不带 {@code #}**：core 的 {@code WikitextRenderer} 会把
 * {@code {{名字:参数}}} 这种写法按注册的插件解析器函数派发（未注册的名字仍按模板处理）。
 * <p>
 * 指令本身只把文件名与说明写进**页面属性**（{@code pagebanner-file} / {@code pagebanner-caption}），
 * 输出空串从正文剥离；是否显示 / 显示哪几张由 {@link PageBannerRenderer} 在页面显示阶段决定
 * （白名单、逐页配置与轮换机制都在那里）。
 * <p>
 * 支持的位置 / 命名参数（其余参数如 {@code icon=} / {@code toc=} 忽略）：
 * <pre>
 * {{PAGEBANNER:山上的日出.jpg}}                    文件名
 * {{PAGEBANNER:甲.jpg|乙.jpg}}                     多张图（本页轮播；顺序即参数顺序）
 * {{PAGEBANNER:山上的日出.jpg|caption=说明文字}}   说明（可带引号，也可含空格）
 * {{PAGEBANNER:image=山上的日出.jpg|caption=说明}} 命名参数写法
 * </pre>
 * 位置参数中的 {@code |} 是 core 的参数分隔符，这里用 {@code |} 重新拼回页面属性
 * （{@link PageBannerRenderer} 按同一分隔符拆开）。参数按原文使用（不做模板展开），
 * 与迁移前 core 抽取 {@code {{PAGEBANNER:…}}}} 的行为一致。
 */
public class PageBannerDirective implements ParserFunction {

    /** 文件名的命名参数（仿 MediaWiki 的 {@code 1=} / {@code image=}）。 */
    private static final Set<String> FILE_KEYS = Set.of("1", "image", "file", "filename");
    /** 认识但不处理的命名参数（写在页面里不报错，也不当成文件名）。 */
    private static final Set<String> IGNORED_KEYS = Set.of("icon", "toc");

    @Override
    public String name() {
        return "PAGEBANNER";
    }

    @Override
    public String invoke(ParserFunctionCall call) {
        List<String> args = call.args();
        List<String> files = new ArrayList<>();
        String caption = "";
        for (String arg : args) {
            String text = arg == null ? "" : arg.trim();
            if (text.isEmpty()) {
                continue;
            }
            String key = namedKey(text);
            if (key == null) {
                files.add(text);
                continue;
            }
            String value = unquote(text.substring(text.indexOf('=') + 1).trim());
            if ("caption".equals(key)) {
                caption = value;
            } else if (FILE_KEYS.contains(key)) {
                if (!value.isEmpty()) {
                    files.add(value);
                }
            } else if (!IGNORED_KEYS.contains(key)) {
                // 不认识的命名参数：按位置参数处理（保持「第一个参数 = 文件名」的老行为）
                files.add(text);
            }
        }
        call.context().setProperty(PageBannerRenderer.FILE_PROPERTY, String.join("|", files));
        call.context().setProperty(PageBannerRenderer.CAPTION_PROPERTY, caption);
        return "";
    }

    /** 参数形如 {@code key=值}（键为标识符）时返回小写键名，否则返回 {@code null}。 */
    private static String namedKey(String text) {
        int equals = text.indexOf('=');
        if (equals <= 0) {
            return null;
        }
        String key = text.substring(0, equals).trim();
        if (key.isEmpty() || !key.matches("[A-Za-z0-9_]+")) {
            return null;
        }
        return key.toLowerCase(Locale.ROOT);
    }

    /** 去掉包围整个值的成对引号（{@code caption="说明"} / {@code caption='说明'}）。 */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }
}
