package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可视化编辑器服务（仿 MediaWiki 可视化编辑器的工作方式，但更保守）：
 * <ol>
 *     <li><b>只从编辑框里的 Wikitext 出发</b>（不是已保存页面的渲染结果），先分词，
 *     把模板 / 引用 / 文件 / 分类 / 预格式等替换成占位符；</li>
 *     <li>渲染后<b>剥掉生成内容</b>（目录、分类栏）并做成可编辑 DOM：模板块（独占段落的 {@code {{…}}}）
 *     直接渲染成<b>预览</b>（点击打开模板对话框），行内模板与其它源码块显示为不可编辑的小块；</li>
 *     <li>回写时用 jsoup 遍历编辑后的 DOM 序列化回 Wikitext；遇到无法安全转换的结构
 *     <b>整体拒绝</b>（不产生半成品），继续用源代码编辑；</li>
 *     <li><b>模板对话框</b>（服务端数据）：
 *     {@link #openTemplate} 解析一次调用并<b>发现模板的全部参数</b>（扫描模板页源码里的
 *     {@code {{{参数}}}}}）、返回渲染预览；{@link #buildTemplate} 按参数表单重建调用源码并预览。</li>
 * </ol>
 */
@Service
public class VisualEditorService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\uE010(\\d+)\uE011");

    /** 模板源码里的参数使用：{@code {{{名字|默认值}}}}} 或 {@code {{{名字}}}}}。 */
    private static final Pattern PARAM_USE = Pattern.compile("\\{\\{\\{\\s*([^{}|]+?)\\s*(?:\\||\\}\\}\\})");

    private static final Pattern POSITIONAL = Pattern.compile("\\d+");

    private final WikitextRenderer renderer;
    private final TemplateResolver templateResolver;
    private final WikitextProperties properties;
    private final MessageSource messageSource;

    public VisualEditorService(WikitextRenderer renderer, TemplateResolver templateResolver,
                               WikitextProperties properties, MessageSource messageSource) {
        this.renderer = renderer;
        this.templateResolver = templateResolver;
        this.properties = properties;
        this.messageSource = messageSource;
    }

    /** 渲染结果：可编辑 HTML + 占位符对应的原始源码列表（原样回写）。 */
    public record Rendered(String html, List<String> tokens) {
    }

    /** 模板对话框里的一行参数。 */
    public record TemplateParam(String name, String value, boolean positional, boolean present, boolean known) {
    }

    /**
     * 模板对话框数据。
     *
     * @param name        调用里写的模板名（不含命名空间前缀，保留原样）
     * @param title       解析后的完整标题（找不到为 null）
     * @param exists      模板页是否存在
     * @param source      当前调用源码（规范化后）
     * @param params      合并后的参数列表（发现的参数 + 调用里多出来的）
     * @param previewHtml 按当前调用渲染的预览（已剥目录 / 分类栏）
     */
    public record TemplateInfo(String name, String title, boolean exists, String source,
                               List<TemplateParam> params, String previewHtml) {
    }

    /** 无法安全序列化（含明细，供界面提示「请用源代码编辑」）。 */
    public static class UnsupportedException extends RuntimeException {
        private final String detail;

        public UnsupportedException(String detail) {
            super(detail);
            this.detail = detail;
        }

        public String detail() {
            return detail;
        }
    }

    // ==================== 渲染：占位符 → 模板预览块 / 源码块 ====================

    /** 可视化编辑：分词 → 渲染 → 去目录等生成内容 → 模板渲染成预览块、其它变源码块。 */
    public Rendered render(String wikitext, String pageTitle) {
        VisualWikitextTokenizer.Tokenized tokenized = VisualWikitextTokenizer.tokenize(wikitext);
        String html = renderer.renderWithMeta(tokenized.text(), pageTitle).html();
        Document document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false); // 原样输出：避免美化缩进混进编辑内容
        // 目录与分类栏都是「后生成」的内容：绝不进入可视化编辑，也不会被回写
        document.select("#toc").remove();
        document.select(".catlinks-block").remove();
        wrapTokens(document.body(), tokenized.tokens());
        upgradeBlockTemplates(document.body(), tokenized.tokens(), pageTitle);
        return new Rendered(document.body().html(), tokenized.sources());
    }

    private void wrapTokens(Element root, List<VisualWikitextTokenizer.Token> tokens) {
        List<TextNode> textNodes = new ArrayList<>();
        collectTextNodes(root, textNodes);
        for (TextNode text : textNodes) {
            if (text.getWholeText().indexOf(VisualWikitextTokenizer.TOKEN_START) >= 0) {
                splitAndWrap(text, tokens);
            }
        }
    }

    private static void collectTextNodes(Node node, List<TextNode> out) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode text) {
                out.add(text);
            } else if (child instanceof Element element) {
                collectTextNodes(element, out);
            }
        }
    }

    private void splitAndWrap(TextNode text, List<VisualWikitextTokenizer.Token> tokens) {
        String value = text.getWholeText();
        Matcher matcher = TOKEN_PATTERN.matcher(value);
        List<Node> parts = new ArrayList<>();
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                parts.add(new TextNode(value.substring(last, matcher.start())));
            }
            int id = Integer.parseInt(matcher.group(1));
            VisualWikitextTokenizer.Token token = id >= 0 && id < tokens.size() ? tokens.get(id) : null;
            parts.add(buildChip(token, id));
            last = matcher.end();
        }
        if (last < value.length()) {
            parts.add(new TextNode(value.substring(last)));
        }
        Node parent = text.parentNode();
        if (!(parent instanceof Element element)) {
            throw new UnsupportedException("text root");
        }
        int cursor = text.siblingIndex();
        for (Node part : parts) {
            element.insertChildren(cursor++, List.of(part));
        }
        text.remove();
    }

    /** 行内占位块：模板显示「模板：名字」，其它显示源码摘要；完整预览在对话框里。 */
    private Element buildChip(VisualWikitextTokenizer.Token token, int id) {
        String source = token == null ? "" : token.source();
        Element chip = new Element("span");
        chip.addClass("ve-token");
        chip.attr("data-token", Integer.toString(id));
        chip.attr("contenteditable", "false");
        chip.attr("title", source.length() > 400 ? source.substring(0, 400) : source);
        if (token != null && VisualWikitextTokenizer.Token.KIND_TEMPLATE.equals(token.kind())) {
            chip.addClass("ve-template");
            chip.addClass("ve-template-inline");
            chip.text(templateLabel(source));
        } else {
            chip.text(summary(source));
        }
        return chip;
    }

    /**
     * 独占一个段落的模板 → 升级成块级预览：直接展示渲染结果（点击打开模板对话框）。
     * 行内模板保持小方块（预览在对话框里），避免破坏段落结构。
     */
    private void upgradeBlockTemplates(Element root, List<VisualWikitextTokenizer.Token> tokens,
                                       String pageTitle) {
        for (Element chip : new ArrayList<>(root.select("span.ve-template-inline[data-token]"))) {
            Element parent = chip.parent();
            if (parent == null) {
                continue;
            }
            boolean soleParagraph = "p".equals(parent.normalName()) && onlyMeaningfulChild(parent, chip);
            boolean soleBody = parent == root;
            if (!soleParagraph && !soleBody) {
                continue;
            }
            int id = parseTokenId(chip.attr("data-token"));
            VisualWikitextTokenizer.Token token = id >= 0 && id < tokens.size() ? tokens.get(id) : null;
            if (token == null) {
                continue;
            }
            String source = token.source();
            Element block = new Element("div");
            block.addClass("ve-token");
            block.addClass("ve-template");
            block.addClass("ve-template-block");
            block.attr("data-token", chip.attr("data-token"));
            block.attr("contenteditable", "false");
            block.attr("title", source.length() > 400 ? source.substring(0, 400) : source);

            Element badge = new Element("div");
            badge.addClass("ve-template-badge");
            badge.text(templateLabel(source));
            Element render = new Element("div");
            render.addClass("ve-template-render");
            render.html(preview(source, pageTitle));
            block.appendChild(badge);
            block.appendChild(render);

            if (soleParagraph) {
                parent.replaceWith(block);
            } else {
                chip.replaceWith(block);
            }
        }
    }

    private static boolean onlyMeaningfulChild(Element parent, Element chip) {
        for (Node child : parent.childNodes()) {
            if (child == chip) {
                continue;
            }
            if (child instanceof TextNode text) {
                if (!text.getWholeText().isBlank()) {
                    return false;
                }
                continue;
            }
            return false;
        }
        return true;
    }

    private static int parseTokenId(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /** 渲染一段 Wikitext（模板调用 / 页面全文）的预览 HTML：剥掉目录与分类栏。 */
    public String preview(String source, String pageTitle) {
        try {
            String html = renderer.renderWithMeta(source == null ? "" : source,
                    pageTitle == null ? "" : pageTitle).html();
            Document document = Jsoup.parseBodyFragment(html);
            document.outputSettings().prettyPrint(false);
            document.select("#toc").remove();
            document.select(".catlinks-block").remove();
            return document.body().html();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    /** 模板块上的标签：「模板：名字」；解析器函数直接用函数名。 */
    private String templateLabel(String source) {
        String name = nameOf(source);
        if (name.isEmpty()) {
            return summary(source);
        }
        if (name.startsWith("#")) {
            return name;
        }
        return messageSource.getMessage("visualEditor.templateLabel", new Object[]{name}, name,
                LocaleContextHolder.getLocale());
    }

    private static String nameOf(String source) {
        try {
            return parseInvocation(source).name();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String summary(String source) {
        String value = source.replaceAll("\\s+", " ").trim();
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }

    // ==================== 模板对话框（参数发现 / 重建 / 预览） ====================

    /** 打开对话框：解析当前调用、发现模板全部参数、渲染预览。 */
    public TemplateInfo openTemplate(String pageTitle, String source) {
        Invocation invocation = parseInvocation(source);
        return info(pageTitle, invocation.name(), invocation.parts());
    }

    /**
     * 按参数表单重建：定位参数（数字名）在前（按序号）、命名参数按表单顺序在后，
     * 值为空的参数不写入（除非标记为 {@code present}）。值里的 {@code |} 会转义成 {@code {{!}}}。
     */
    public TemplateInfo buildTemplate(String pageTitle, String name, List<TemplateParam> params) {
        String trimmed = Strings.trimToEmpty(name);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("template name");
        }
        List<TemplateParam> list = params == null ? List.of() : params;
        List<Part> parts = new ArrayList<>();
        list.stream()
                .filter(param -> param.positional() && included(param))
                .sorted(Comparator.comparingInt(param -> parseIntOrMax(param.name())))
                .forEach(param -> parts.add(new Part(null, Strings.trimToEmpty(param.value()))));
        list.stream()
                .filter(param -> !param.positional() && included(param)
                        && !Strings.trimToEmpty(param.name()).isEmpty())
                .forEach(param -> parts.add(new Part(Strings.trimToEmpty(param.name()),
                        Strings.trimToEmpty(param.value()))));
        return info(pageTitle, trimmed, parts);
    }

    private static boolean included(TemplateParam param) {
        return param.present() || !Strings.trimToEmpty(param.value()).isEmpty();
    }

    private static int parseIntOrMax(String value) {
        try {
            return Integer.parseInt(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private TemplateInfo info(String pageTitle, String name, List<Part> parts) {
        Optional<TemplatePage> template = findTemplate(name);
        List<String> discovered = template.map(page -> discoverParams(page.content())).orElse(List.of());
        String source = renderSource(name, parts);
        return new TemplateInfo(name, template.map(TemplatePage::title).orElse(null),
                template.isPresent(), source, merge(discovered, parts), preview(source, pageTitle));
    }

    /** 模板页查找：带命名空间的按完整标题；否则按配置的模板前缀依次尝试。 */
    private Optional<TemplatePage> findTemplate(String name) {
        String trimmed = Strings.trimToEmpty(name);
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return Optional.empty();
        }
        List<String> candidates = trimmed.indexOf(':') >= 0 ? List.of(trimmed)
                : properties.getTemplatePrefixes().stream().map(prefix -> prefix + trimmed).toList();
        for (String candidate : candidates) {
            Optional<String> content = templateResolver.resolve(candidate);
            if (content.isPresent()) {
                return Optional.of(new TemplatePage(candidate, content.get()));
            }
        }
        return Optional.empty();
    }

    private record TemplatePage(String title, String content) {
    }

    /** 扫描模板源码里的 {@code {{{…}}}}}：位置参数按序号排前面，命名参数按出现顺序。 */
    private static List<String> discoverParams(String templateContent) {
        LinkedHashSet<String> positional = new LinkedHashSet<>();
        LinkedHashSet<String> named = new LinkedHashSet<>();
        Matcher matcher = PARAM_USE.matcher(templateContent == null ? "" : templateContent);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (name.isEmpty()) {
                continue;
            }
            if (POSITIONAL.matcher(name).matches()) {
                positional.add(name);
            } else {
                named.add(name);
            }
        }
        List<String> ordered = new ArrayList<>(positional);
        ordered.sort(Comparator.comparingInt(Integer::parseInt));
        ordered.addAll(named);
        return ordered;
    }

    /** 合并「模板源代码发现的参数」与「调用里实际写的参数」。 */
    private static List<TemplateParam> merge(List<String> discovered, List<Part> parts) {
        Map<String, Part> presentPositional = new LinkedHashMap<>();
        Map<String, Part> presentNamed = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        int positionalIndex = 0;
        for (Part part : parts) {
            if (part.key() == null) {
                positionalIndex++;
                String key = Integer.toString(positionalIndex);
                presentPositional.put(key, part);
                if (!order.contains(key)) {
                    order.add(key);
                }
            } else if (POSITIONAL.matcher(part.key()).matches()) {
                // 显式编号参数（如 1=乙）等价于位置参数
                presentPositional.put(part.key(), part);
                if (!order.contains(part.key())) {
                    order.add(part.key());
                }
            } else {
                presentNamed.put(part.key(), part);
                if (!order.contains(part.key())) {
                    order.add(part.key());
                }
            }
        }
        List<TemplateParam> merged = new ArrayList<>();
        for (String name : discovered) {
            boolean positional = POSITIONAL.matcher(name).matches();
            Part part = positional ? presentPositional.get(name) : presentNamed.get(name);
            merged.add(new TemplateParam(name, part == null ? "" : part.value(), positional,
                    part != null, true));
        }
        for (String name : order) {
            if (discovered.contains(name)) {
                continue;
            }
            boolean positional = POSITIONAL.matcher(name).matches();
            Part part = positional ? presentPositional.get(name) : presentNamed.get(name);
            if (part != null) {
                merged.add(new TemplateParam(name, part.value(), positional, true, false));
            }
        }
        return merged;
    }

    // ==================== 调用源码解析 / 重建 ====================

    private record Invocation(String name, List<Part> parts) {
    }

    /** 一个参数：{@code key == null} 表示位置参数。 */
    private record Part(String key, String value) {
    }

    private static Invocation parseInvocation(String source) {
        String text = Strings.trimToEmpty(source);
        if (!text.startsWith("{{") || !text.endsWith("}}") || text.length() < 4) {
            throw new IllegalArgumentException("not a template invocation");
        }
        String inner = text.substring(2, text.length() - 2);
        List<String> rawParts = splitTopLevel(inner);
        String name = rawParts.isEmpty() ? "" : rawParts.get(0).trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("empty template name");
        }
        List<Part> parts = new ArrayList<>();
        for (int i = 1; i < rawParts.size(); i++) {
            parts.add(parsePart(rawParts.get(i)));
        }
        return new Invocation(name, parts);
    }

    private static Part parsePart(String raw) {
        int equals = findTopLevelEquals(raw);
        if (equals >= 0) {
            String key = raw.substring(0, equals).trim();
            if (!key.isEmpty() && key.indexOf('{') < 0 && key.indexOf('[') < 0 && key.indexOf('|') < 0) {
                return new Part(key, raw.substring(equals + 1).trim());
            }
        }
        return new Part(null, raw.trim());
    }

    /** 顶层 {@code =}（忽略嵌套 {@code {{…}}} / {@code [[…]]} 里的）。 */
    private static int findTopLevelEquals(String raw) {
        int braceDepth = 0;
        int bracketDepth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') {
                braceDepth++;
            } else if (c == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            } else if (c == '[') {
                bracketDepth++;
            } else if (c == ']') {
                bracketDepth = Math.max(0, bracketDepth - 1);
            } else if (c == '=' && braceDepth == 0 && bracketDepth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** 顶层拆 {@code |}（忽略嵌套模板 / 链接里的竖线）。 */
    private static List<String> splitTopLevel(String inner) {
        List<String> parts = new ArrayList<>();
        int braceDepth = 0;
        int bracketDepth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '{') {
                braceDepth++;
            } else if (c == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            } else if (c == '[') {
                bracketDepth++;
            } else if (c == ']') {
                bracketDepth = Math.max(0, bracketDepth - 1);
            } else if (c == '|' && braceDepth == 0 && bracketDepth == 0) {
                parts.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(inner.substring(start));
        return parts;
    }

    private static String renderSource(String name, List<Part> parts) {
        StringBuilder out = new StringBuilder("{{").append(name);
        for (Part part : parts) {
            out.append('|');
            String value = escapeValue(part.value());
            if (part.key() == null) {
                out.append(value);
            } else {
                out.append(part.key()).append('=').append(value);
            }
        }
        return out.append("}}").toString();
    }

    /** 值里的 {@code |} 会截断参数：统一转义成 {@code {{!}}}（渲染器支持）。 */
    private static String escapeValue(String value) {
        return value == null ? "" : value.replace("|", "{{!}}");
    }

    // ==================== 序列化：DOM → Wikitext ====================

    /** 把编辑后的 HTML 序列化回 Wikitext；任何不支持的节点都会整体失败。 */
    public String serialize(String html, List<String> tokens) {
        Document document = Jsoup.parseBodyFragment(html == null ? "" : html);
        List<String> sources = tokens == null ? List.of() : tokens;
        List<String> blocks = new ArrayList<>();
        for (Node node : document.body().childNodes()) {
            String block = block(node, sources);
            if (block != null && !block.isBlank()) {
                blocks.add(block.stripTrailing());
            }
        }
        if (blocks.isEmpty()) {
            return "";
        }
        return String.join("\n\n", blocks) + "\n";
    }

    private String block(Node node, List<String> sources) {
        if (node instanceof TextNode text) {
            String value = text.getWholeText();
            return value.isBlank() ? null : value.strip();
        }
        if (!(node instanceof Element element)) {
            throw new UnsupportedException(node.nodeName());
        }
        String tag = element.normalName();
        switch (tag) {
            case "h2", "h3", "h4", "h5", "h6": {
                int level = tag.charAt(1) - '0';
                String eq = "=".repeat(level);
                String text = inline(element, sources).strip();
                if (text.isEmpty()) {
                    return null;
                }
                return eq + " " + text + " " + eq;
            }
            case "hr":
                return "----";
            case "p", "div": {
                // 块级模板 / 源码块（div.ve-token）：整块按原始源码还原
                if (isToken(element)) {
                    return tokenSource(element, sources);
                }
                // 浏览器 contenteditable 回车可能插入 div：按段落处理
                String text = inline(element, sources).strip();
                return text.isEmpty() ? null : text;
            }
            case "ul", "ol", "dl":
                return list(element, 0, sources);
            case "table":
                return table(element, sources);
            case "span":
                if (isToken(element)) {
                    return tokenSource(element, sources);
                }
                throw new UnsupportedException("span");
            default:
                throw new UnsupportedException(tag);
        }
    }

    private String list(Element list, int depth, List<String> sources) {
        boolean ordered = list.normalName().equals("ol");
        List<String> lines = new ArrayList<>();
        for (Node child : list.childNodes()) {
            if (child instanceof TextNode text) {
                if (!text.getWholeText().isBlank()) {
                    throw new UnsupportedException("text in list");
                }
                continue;
            }
            if (!(child instanceof Element element)) {
                throw new UnsupportedException("list child");
            }
            String tag = element.normalName();
            if (tag.equals("li")) {
                String prefix = ordered ? "#".repeat(depth + 1) : "*".repeat(depth + 1);
                lines.add(prefix + " " + inlineSkippingLists(element, sources).strip());
                for (Node grand : element.childNodes()) {
                    if (grand instanceof Element nested && isList(nested)) {
                        lines.add(list(nested, depth + 1, sources));
                    }
                }
            } else if (tag.equals("dd") || tag.equals("dt")) {
                lines.add((tag.equals("dd") ? ": " : "; ") + inline(element, sources).strip());
            } else if (isList(element)) {
                // 渲染器的嵌套列表是兄弟节点而不是包在 li 里
                lines.add(list(element, depth + 1, sources));
            } else {
                throw new UnsupportedException(tag + " in list");
            }
        }
        return String.join("\n", lines);
    }

    private static boolean isList(Element element) {
        return switch (element.normalName()) {
            case "ul", "ol", "dl" -> true;
            default -> false;
        };
    }

    private String table(Element table, List<String> sources) {
        String className = table.className().trim();
        if (!className.isEmpty() && !className.equals("wikitable")) {
            throw new UnsupportedException("table.class=" + className);
        }
        if (table.hasAttr("style") || table.hasAttr("align") || table.hasAttr("border")) {
            throw new UnsupportedException("table[attr]");
        }
        StringBuilder out = new StringBuilder(className.isEmpty() ? "{|" : "{| class=\"wikitable\"");
        for (Element row : tableRows(table)) {
            out.append("\n|-\n");
            boolean header = false;
            List<String> cells = new ArrayList<>();
            List<Element> cellElements = row.children();
            if (cellElements.isEmpty()) {
                throw new UnsupportedException("table row empty");
            }
            header = cellElements.get(0).normalName().equals("th");
            for (Element cell : cellElements) {
                String cellTag = cell.normalName();
                if (!cellTag.equals("th") && !cellTag.equals("td")) {
                    throw new UnsupportedException("table cell " + cellTag);
                }
                if (cellTag.equals("th") != header) {
                    throw new UnsupportedException("table mixed cells");
                }
                if (cell.hasAttr("colspan") || cell.hasAttr("rowspan")
                        || cell.hasAttr("style") || cell.hasAttr("align")) {
                    throw new UnsupportedException("table cell attr");
                }
                cells.add(inline(cell, sources).strip());
            }
            String joined = String.join(header ? " !! " : " || ", cells);
            out.append(header ? "! " : "| ").append(joined);
        }
        out.append("\n|}");
        return out.toString();
    }

    /** 表格行：兼容浏览器 / jsoup 补出的 tbody / thead 包裹。 */
    private static List<Element> tableRows(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element child : table.children()) {
            String tag = child.normalName();
            if (tag.equals("tr")) {
                rows.add(child);
            } else if (tag.equals("tbody") || tag.equals("thead") || tag.equals("tfoot")) {
                for (Element row : child.children()) {
                    if (!row.normalName().equals("tr")) {
                        throw new UnsupportedException("table>" + tag + ">" + row.normalName());
                    }
                    rows.add(row);
                }
            } else {
                throw new UnsupportedException("table>" + tag);
            }
        }
        return rows;
    }

    private String inlineSkippingLists(Element element, List<String> sources) {
        StringBuilder out = new StringBuilder();
        for (Node child : element.childNodes()) {
            if (child instanceof Element nested && isList(nested)) {
                continue;
            }
            out.append(inlineNode(child, sources));
        }
        return out.toString();
    }

    private String inline(Node element, List<String> sources) {
        StringBuilder out = new StringBuilder();
        for (Node child : element.childNodes()) {
            out.append(inlineNode(child, sources));
        }
        return out.toString();
    }

    private String inlineNode(Node node, List<String> sources) {
        if (node instanceof TextNode text) {
            return text.getWholeText();
        }
        if (!(node instanceof Element element)) {
            throw new UnsupportedException(node.nodeName());
        }
        String tag = element.normalName();
        switch (tag) {
            case "b", "strong": {
                Element inner = singleChild(element, "i", "em");
                if (inner != null) {
                    return "'''''" + inline(inner, sources) + "'''''";
                }
                String value = inline(element, sources);
                return value.isEmpty() ? "" : "'''" + value + "'''";
            }
            case "i", "em": {
                Element inner = singleChild(element, "b", "strong");
                if (inner != null) {
                    return "'''''" + inline(inner, sources) + "'''''";
                }
                String value = inline(element, sources);
                return value.isEmpty() ? "" : "''" + value + "''";
            }
            case "a":
                return link(element, sources);
            case "br":
                return "<br>";
            case "span":
                if (isToken(element)) {
                    return tokenSource(element, sources);
                }
                throw new UnsupportedException("span");
            case "ul", "ol", "dl":
                throw new UnsupportedException("list inline");
            default:
                throw new UnsupportedException(tag);
        }
    }

    /** 只有一个该标签子元素时返回它（用于合并粗斜体）。 */
    private static Element singleChild(Element element, String... tags) {
        if (element.childNodeSize() != 1 || !(element.childNode(0) instanceof Element child)) {
            return null;
        }
        for (String tag : tags) {
            if (child.normalName().equals(tag)) {
                return child;
            }
        }
        return null;
    }

    private String link(Element anchor, List<String> sources) {
        String href = anchor.attr("href");
        if (href.isEmpty()) {
            throw new UnsupportedException("a[href]");
        }
        String text = inline(anchor, sources);
        String rest = WikiUrls.pagePathRest(href);
        if (rest != null) {
            String target = decode(rest);
            if (text.equals(target)) {
                return "[[" + target + "]]";
            }
            return "[[" + target + "|" + text + "]]";
        }
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("ftp://")) {
            if (text.equals(href) || text.isBlank()) {
                return "[" + href + "]";
            }
            return "[" + href + " " + text + "]";
        }
        throw new UnsupportedException("a[href=" + href + "]");
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private static boolean isToken(Element element) {
        return element.classNames().contains("ve-token") && element.hasAttr("data-token");
    }

    private String tokenSource(Element chip, List<String> sources) {
        int index;
        try {
            index = Integer.parseInt(chip.attr("data-token"));
        } catch (NumberFormatException exception) {
            throw new UnsupportedException("token id");
        }
        if (index < 0 || index >= sources.size()) {
            throw new UnsupportedException("token #" + index);
        }
        return sources.get(index);
    }
}
