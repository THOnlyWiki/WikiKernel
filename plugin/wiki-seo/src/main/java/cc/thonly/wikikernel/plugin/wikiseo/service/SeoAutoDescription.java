package cc.thonly.wikikernel.plugin.wikiseo.service;

import java.util.Locale;

/**
 * 自动描述（本机化实现，用来替代 MediaWiki WikiSEO 依赖的 TextExtracts 扩展）。
 * <p>
 * 原版的描述在「页面保存」时通过 TextExtracts API 生成并写入 {@code description} page prop；
 * WikiKernel 没有 TextExtracts，这里改为在 Head 阶段直接读页面正文，抽出纯文本后截断：
 * <ol>
 *     <li>去掉引用 / HTML 标签 / 模板 / 参数 / 内链外链标记与行首标记，压平空白；</li>
 *     <li>取前 {@value #LIMIT} 个字符，并在最后一个句末标点处收尾（避免半句话）；</li>
 *     <li>{@code try-clean-auto-description=true} 时再截到最后一个句号（句号 / 中文句号）。</li>
 * </ol>
 */
public final class SeoAutoDescription {

    /** 自动描述字符上限（原版 TextExtracts 请求 {@code exchars=160}，这里取 150）。 */
    public static final int LIMIT = 150;

    /** 中文句号（也当作句末标点）。 */
    private static final String SENTENCE_ENDS = "。！？.!?";

    /** 「句号」：只有中英文句号（用于 clean 模式）。 */
    private static final String PERIODS = "。.";

    private SeoAutoDescription() {
    }

    /** {@code description=auto} / {@code description=textextracts} 表示「自动生成」。 */
    public static boolean isAutoSource(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("auto") || normalized.equals("textextracts");
    }

    /**
     * 从页面正文生成描述。
     *
     * @param wikitext 页面最新正文（可能为 null）
     * @param clean    是否截到最后一个句号
     * @return 描述（正文为空时返回空串）
     */
    public static String from(String wikitext, boolean clean) {
        String text = plainText(wikitext);
        if (text.isEmpty()) {
            return "";
        }
        return truncate(text, clean);
    }

    /** 把 wikitext 抽成纯文本（去模板 / 链接 / HTML 标签与多余符号，压平空白）。 */
    static String plainText(String wikitext) {
        if (wikitext == null || wikitext.isBlank()) {
            return "";
        }
        String text = wikitext;
        text = text.replaceAll("(?si)<ref[^>]*/>", "");           // 空引用 <ref name=x />
        text = text.replaceAll("(?si)<ref[^>]*>.*?</ref>", "");   // 引用
        text = text.replaceAll("(?si)<[^>]*>", "");               // 其它 HTML 标签
        text = stripTemplates(text);                              // {{模板}} / {{{参数}}}
        text = text.replaceAll("(?s)\\[\\[[^\\[\\]]*\\|([^\\[\\]|]*)\\]\\]", "$1"); // [[目标|显示]]
        text = text.replaceAll("(?s)\\[\\[[^\\[\\]]*\\]\\]", " ");  // [[内链]]
        text = text.replaceAll("(?s)\\[[a-zA-Z][^\\s\\]]*\\s+([^\\]]*)\\]", "$1");  // [URL 显示]
        text = text.replaceAll("(?s)\\[[a-zA-Z][^\\]]*\\]", " ");   // [URL]
        text = text.replaceAll("'''?", "");                         // 粗体 / 斜体
        text = text.replaceAll("(?m)^[\\s>*#:;=|{_-]+", " ");       // 行首列表 / 表格 / 缩进 / 标题标记
        text = text.replaceAll("\\{|\\}|\\[|\\]", " ");             // 残留括号
        text = text.replaceAll("[\\p{Cntrl}]", " ");                // 控制字符
        text = text.replaceAll("\\s+", " ");                        // 压平空白
        return text.trim();
    }

    /** 反复去掉最内层模板（{@code {{a|{{b}}}}} 这类嵌套需要多轮）。 */
    private static String stripTemplates(String text) {
        String previous;
        int guard = 0;
        do {
            previous = text;
            text = text.replaceAll("(?s)\\{\\{\\{[^{}]*\\}\\}\\}", " ");
            text = text.replaceAll("(?s)\\{\\{[^{}]*\\}\\}", " ");
        } while (!text.equals(previous) && ++guard < 10);
        return text;
    }

    /** 截断：先按 {@value #LIMIT} 字符 + 最后一个句末标点，再按需截到最后一个句号。 */
    static String truncate(String text, boolean clean) {
        String result = text;
        if (result.length() > LIMIT) {
            String prefix = result.substring(0, LIMIT);
            int cut = lastIndexOfAny(prefix, SENTENCE_ENDS);
            result = cut >= 0 ? prefix.substring(0, cut + 1) : prefix;
        }
        if (clean) {
            int cut = lastIndexOfAny(result, PERIODS);
            if (cut >= 0) {
                result = result.substring(0, cut + 1);
            }
        }
        return result.trim();
    }

    private static int lastIndexOfAny(String text, String chars) {
        for (int index = text.length() - 1; index >= 0; index--) {
            if (chars.indexOf(text.charAt(index)) >= 0) {
                return index;
            }
        }
        return -1;
    }
}
