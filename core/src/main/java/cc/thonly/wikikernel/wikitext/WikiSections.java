package cc.thonly.wikikernel.wikitext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 段落（章节）工具（仿 MediaWiki 的 {@code action=edit&section=N}）：
 * <ul>
 *     <li>按标题行把页面源码切成段：第 1、2、3… 段对应第 1、2、3… 个标题（含标题行本身），
 *     段 0 = 第一个标题之前的导语（没有标题时就是整页）；</li>
 *     <li>提取时子标题包含在上级段内，直到下一个同级或更高级标题；</li>
 *     <li>合并时只替换目标段的字符区间，其余部分**逐字保留**（含换行风格）；</li>
 *     <li>与渲染器一致：{@code <pre>} / {@code <nowiki>} / {@code <syntaxhighlight>} /
 *     {@code <markdown>} 里的标题行不算段。</li>
 * </ul>
 */
public final class WikiSections {

    /** 段：index（1 起，0 为导语）、标题级别（= 的个数）、标题文本、段文本（含标题行）、起止偏移。 */
    public record Section(int index, int level, String heading, String text, int start, int end) {
    }

    /** 与 WikitextRenderer.HEADING 一致。 */
    private static final Pattern HEADING = Pattern.compile("^\\s*(={1,6})\\s*(.+?)\\s*\\1\\s*$");

    /** 整块处理的标签（其内部的标题行不算段）。 */
    private static final String[] BLOCK_TAGS = {"pre", "nowiki", "syntaxhighlight", "source", "poem", "markdown", "inputbox", "math", "chem", "timeline", "graph"};

    private WikiSections() {
    }

    /** 全部段落（按标题出现顺序，1 起；导语不包含在内）。 */
    public static List<Section> parse(String content) {
        return split(content);
    }

    /** 提取第 {@code section} 段文本（0 = 导语，1.. = 标题段，含标题行）；找不到返回 null。 */
    public static String extract(String content, int section) {
        if (content == null) {
            return null;
        }
        if (section == 0) {
            List<Section> sections = split(content);
            int end = sections.isEmpty() ? content.length() : sections.get(0).start();
            return content.substring(0, end);
        }
        for (Section candidate : split(content)) {
            if (candidate.index() == section) {
                return candidate.text();
            }
        }
        return null;
    }

    /** 第 {@code section} 段的标题文本（0 / 找不到 → null）。 */
    public static String headingOf(String content, int section) {
        if (content == null || section <= 0) {
            return null;
        }
        for (Section candidate : split(content)) {
            if (candidate.index() == section) {
                return candidate.heading();
            }
        }
        return null;
    }

    /** 标题显示文本（去掉脚注标签与行内标记，用于编辑摘要 / 段落编辑提示）。 */
    public static String displayHeading(String heading) {
        if (heading == null) {
            return null;
        }
        String value = heading
                .replaceAll("(?is)<ref[^>]*>.*?</ref>", "")
                .replaceAll("(?is)<ref[^>]*/?>", "")
                .replaceAll("(?is)<references?[^>]*/?>", "");
        value = value.replace("'''''", "").replace("'''", "").replace("''", "");
        value = value.replaceAll("\\[\\[([^\\[\\]|]*)\\|([^\\[\\]]*)\\]\\]", "$2");
        value = value.replaceAll("\\[\\[([^\\[\\]]*)\\]\\]", "$1");
        value = value.replaceAll("<[^>]*>", "").trim();
        return value.isEmpty() ? null : value;
    }

    /** 用 {@code newText} 替换第 {@code section} 段（其余部分原样保留）；段落不存在返回 null。 */
    public static String replace(String content, int section, String newText) {
        if (content == null || newText == null) {
            return null;
        }
        List<Section> sections = split(content);
        if (section == 0) {
            int end = sections.isEmpty() ? content.length() : sections.get(0).start();
            return merge(content, 0, end, newText);
        }
        for (Section candidate : sections) {
            if (candidate.index() == section) {
                return merge(content, candidate.start(), candidate.end(), newText);
            }
        }
        return null;
    }

    /** 标题文本 → 锚点 id（与渲染器 uniqueAnchor 的基础规则一致；重名去重由渲染器处理）。 */
    public static String anchorOf(String heading) {
        String value = heading == null ? "" : heading;
        // 脚注标签从锚点里整体去掉（渲染侧的锚点同样不含脚注内容）
        value = value.replaceAll("(?is)<ref[^>]*>.*?</ref>", "")
                .replaceAll("(?is)<ref[^>]*/?>", "")
                .replaceAll("(?is)<references?[^>]*/?>", "");
        value = value.replace("'''''", "").replace("'''", "").replace("''", "");
        value = value.replaceAll("\\[\\[([^\\[\\]|]*)\\|([^\\[\\]]*)\\]\\]", "$2");
        value = value.replaceAll("\\[\\[([^\\[\\]]*)\\]\\]", "$1");
        value = value.replaceAll("<[^>]*>", "");
        value = value.replace(' ', '_').replaceAll("[\\[\\]{}|<>#\"'%\\\\]", "").trim();
        return value.isEmpty() ? "section" : value;
    }

    private static String merge(String content, int start, int end, String newText) {
        String text = newText;
        if (end < content.length() && !text.endsWith("\n")) {
            text += "\n";
        }
        return content.substring(0, start) + text + content.substring(end);
    }

    /** 扫描整页，按标题行切段（跳过 pre / nowiki 等整块内容）。 */
    private static List<Section> split(String content) {
        List<Section> sections = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return sections;
        }
        List<Integer> starts = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        String closing = null;
        int lineStart = 0;
        int length = content.length();
        while (lineStart <= length) {
            int lineEnd = content.indexOf('\n', lineStart);
            boolean last = lineEnd < 0;
            if (last) {
                lineEnd = length;
            }
            String line = content.substring(lineStart, lineEnd);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (closing != null) {
                if (containsIgnoreCase(line, "</" + closing)) {
                    closing = null;
                }
            } else {
                String opened = openedBlock(line);
                if (opened != null) {
                    closing = opened;
                } else {
                    Matcher matcher = HEADING.matcher(line);
                    if (matcher.matches()) {
                        starts.add(lineStart);
                        levels.add(matcher.group(1).length());
                        headings.add(matcher.group(2).trim());
                    }
                }
            }
            if (last) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        for (int i = 0; i < starts.size(); i++) {
            int level = levels.get(i);
            int end = length;
            for (int j = i + 1; j < starts.size(); j++) {
                if (levels.get(j) <= level) {
                    end = starts.get(j);
                    break;
                }
            }
            sections.add(new Section(i + 1, level, headings.get(i),
                    content.substring(starts.get(i), end), starts.get(i), end));
        }
        return sections;
    }

    /** 该行是否开启了未闭合的整块标签（同行内开+关则不算）。 */
    private static String openedBlock(String line) {
        for (String tag : BLOCK_TAGS) {
            int open = indexOfIgnoreCase(line, "<" + tag, 0);
            if (open < 0) {
                continue;
            }
            if (indexOfIgnoreCase(line, "</" + tag, open + 1) < 0) {
                return tag;
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(String text, String token) {
        return indexOfIgnoreCase(text, token, 0) >= 0;
    }

    private static int indexOfIgnoreCase(String text, String token, int fromIndex) {
        int max = text.length() - token.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (text.regionMatches(true, i, token, 0, token.length())) {
                return i;
            }
        }
        return -1;
    }
}
