package cc.thonly.wikikernel.plugin.easytimeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EasyTimeline（{@code <timeline>}）的常用子集渲染器（仿 MediaWiki EasyTimeline 扩展）：
 * 把 EasyTimeline 的文本定义解析成**内联 SVG**（不依赖 Ploticus / Perl，也不需要外部渲染服务）。
 * <p>
 * 支持的命令：
 * <ul>
 *     <li>{@code ImageSize = width:N height:N|auto barincrement:N}；</li>
 *     <li>{@code PlotArea = left:N right:N top:N bottom:N}；</li>
 *     <li>{@code AlignBars = justify|early|late}（late = 贴底堆叠）；</li>
 *     <li>{@code DateFormat = yyyy|mm/dd/yyyy|dd/mm/yyyy}；</li>
 *     <li>{@code Period = from:X till:Y}（时间范围，X / Y 可为 {@code $} 表示范围端点）；</li>
 *     <li>{@code TimeAxis = orientation:horizontal|vertical}（内部统一按横轴布局，纵轴退化为横轴）；</li>
 *     <li>{@code ScaleMajor} / {@code ScaleMinor = unit:year increment:N start:X gridcolor:颜色}；</li>
 *     <li>{@code Colors = id:名字 value:颜色 legend:图例文字}；</li>
 *     <li>{@code BackgroundColors = canvas:颜色 bars:颜色}；</li>
 *     <li>{@code PlotData}：{@code bar:名称 from:X till:Y at:X text:"文字" color:id shift:(dx,dy) width:N fontsize:S|M|L}、
 *         {@code barset:名称} 把后续 bar 放在同一行、{@code align:}/{@code width:}/{@code textcolor:} 等行内默认值；</li>
 *     <li>{@code TextData = pos:(x,y) text:"文字" color:颜色 fontsize:S|M|L}（绝对像素坐标）。</li>
 * </ul>
 * 未实现（按 MediaWiki 文档说明忽略）：{@code LineData} 折线、{@code Legend} 的精细定位（图例统一画在右上角）、
 * {@code BackgroundColors} 渐变、{@code mark:} 自定义标记、纵轴刻度。
 * <p>
 * 所有文字都做 HTML 转义、颜色都做白名单校验，输出直接作为可信块插入页面（不会引入脚本）。
 */
public final class TimelineRenderer {

    /** 顶层命令：{@code 关键字 = 值}。 */
    private static final Pattern COMMAND = Pattern.compile("^([A-Za-z]+)\\s*=\\s*(.*)$");

    /** 行内 {@code 键:值}（值可为 {@code "引号串"} / {@code (坐标)} / 无空格 token）。 */
    private static final Pattern KEY_VALUE = Pattern.compile("([A-Za-z]+)\\s*:\\s*(\"([^\"]*)\"|\\(([^)]*)\\)|[^\\s]+)");

    /** 顶层命令白名单。 */
    private static final List<String> COMMANDS = List.of(
            "ImageSize", "PlotArea", "AlignBars", "DateFormat", "Period", "TimeAxis",
            "ScaleMajor", "ScaleMinor", "Colors", "BackgroundColors", "Legend",
            "PlotData", "TextData", "LineData");

    /** 颜色白名单（十六进制、常用函数与命名色；不含引号 / 尖括号，无法注入）。 */
    private static final Pattern COLOR = Pattern.compile("[A-Za-z#0-9(),.% ]+");

    /** EasyTimeline 的 {@code gray(0.9)} 灰阶函数。 */
    private static final Pattern GRAY = Pattern.compile("gray\\(\\s*([0-9.]+)\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** EasyTimeline 的 {@code rgb(0.5,0.5,0.5)}（0..1 分量）。 */
    private static final Pattern RGB = Pattern.compile(
            "rgb\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\)", Pattern.CASE_INSENSITIVE);

    private TimelineRenderer() {
    }

    /** 渲染 EasyTimeline 定义为 SVG；定义无法解析时返回 {@code null}（调用方显示错误提示）。 */
    public static String render(String markup) {
        return render(markup, "Timeline");
    }

    /**
     * 渲染 EasyTimeline 定义为 SVG；{@code ariaLabel} 是无障碍标签（调用方传本地化文案，见消息键
     * {@code timeline.label}），定义无法解析时返回 {@code null}（调用方显示错误提示）。
     */
    public static String render(String markup, String ariaLabel) {
        if (markup == null || markup.isBlank()) {
            return null;
        }
        try {
            Spec spec = parse(markup);
            if (spec.till <= spec.from) {
                return null;
            }
            return draw(spec, ariaLabel);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // ==================== 数据模型 ====================

    private static final class Spec {
        int width = 400;
        int height = 200;
        boolean heightAuto = false;
        int barIncrement = 20;

        int left = 50;
        int right = 20;
        int top = 20;
        int bottom = 50;

        String alignBars = "justify";
        String dateFormat = "yyyy";
        boolean hasPeriod = false;
        double from = 0;
        double till = 1;

        int plotBarWidth = -1;
        String defaultTextColor = "#202122";
        int defaultFontSize = 10;

        Scale major;
        Scale minor;

        final Map<String, String> colors = new LinkedHashMap<>();
        final Map<String, String> legends = new LinkedHashMap<>();
        final List<String> legendOrder = new ArrayList<>();

        String canvas = "#ffffff";
        String barsBackground = null;

        final List<Row> rows = new ArrayList<>();
        final List<TextItem> texts = new ArrayList<>();
    }

    private record Scale(String unit, double increment, double start, String gridColor) {
    }

    private static final class Row {
        String barset;
        final List<Bar> bars = new ArrayList<>();
    }

    private static final class Bar {
        String name;
        String fromRaw;
        String tillRaw;
        String atRaw;
        String text;
        String colorId;
        int fontSize = 0;
        double shiftX;
        double shiftY;
    }

    private record TextItem(double x, double y, String text, String color, int fontSize) {
    }

    // ==================== 解析 ====================

    private enum Section {
        NONE, COLORS, PLOT_DATA, TEXT_DATA, LEGEND, LINE_DATA
    }

    private static Spec parse(String markup) {
        Spec spec = new Spec();
        Section section = Section.NONE;
        boolean inBarset = false;
        Row lastRow = null;

        String[] lines = (markup == null ? "" : markup).split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher command = COMMAND.matcher(line);
            if (command.matches() && COMMANDS.contains(command.group(1))) {
                String keyword = command.group(1);
                String arguments = command.group(2) == null ? "" : command.group(2);
                switch (keyword) {
                    case "ImageSize" -> applyImageSize(spec, arguments);
                    case "PlotArea" -> applyPlotArea(spec, arguments);
                    case "AlignBars" -> {
                        Map<String, String> values = keyValues(arguments);
                        if (values.containsKey("bars")) {
                            spec.alignBars = values.get("bars").toLowerCase(Locale.ROOT);
                        }
                        // 形如 AlignBars = late
                        else if (!arguments.isBlank() && arguments.indexOf(':') < 0) {
                            spec.alignBars = arguments.trim().toLowerCase(Locale.ROOT);
                        }
                    }
                    case "DateFormat" -> {
                        String value = arguments.trim();
                        if (!value.isEmpty() && value.indexOf(':') < 0) {
                            spec.dateFormat = value.toLowerCase(Locale.ROOT);
                        }
                    }
                    case "Period" -> applyPeriod(spec, arguments);
                    case "TimeAxis" -> {
                        // orientation 目前统一按横轴布局；保留解析以兼容定义
                    }
                    case "ScaleMajor" -> spec.major = scale(arguments, "#c8ccd1");
                    case "ScaleMinor" -> spec.minor = scale(arguments, "#eaecf0");
                    case "BackgroundColors" -> {
                        Map<String, String> values = keyValues(arguments);
                        if (values.containsKey("canvas")) {
                            spec.canvas = colorValue(values.get("canvas"), spec, values.get("canvas"));
                        }
                        if (values.containsKey("bars")) {
                            spec.barsBackground = colorValue(values.get("bars"), spec, values.get("bars"));
                        }
                    }
                    case "Colors" -> {
                        section = Section.COLORS;
                        parseColor(spec, arguments);
                    }
                    case "PlotData" -> {
                        section = Section.PLOT_DATA;
                        applyPlotDefaults(spec, arguments);
                    }
                    case "TextData" -> section = Section.TEXT_DATA;
                    case "Legend" -> section = Section.LEGEND;
                    case "LineData" -> section = Section.LINE_DATA;
                    default -> section = Section.NONE;
                }
                continue;
            }
            switch (section) {
                case COLORS -> parseColor(spec, line);
                case PLOT_DATA -> {
                    Map<String, String> values = keyValues(line);
                    if (values.containsKey("barset")) {
                        Row row = new Row();
                        row.barset = values.get("barset");
                        spec.rows.add(row);
                        lastRow = row;
                        inBarset = true;
                        continue;
                    }
                    if (values.containsKey("bar") || values.containsKey("at") || values.containsKey("from")) {
                        Bar bar = parseBar(values);
                        if (!inBarset || lastRow == null) {
                            Row row = new Row();
                            spec.rows.add(row);
                            lastRow = row;
                            inBarset = false;
                        }
                        lastRow.bars.add(bar);
                        continue;
                    }
                    // align: / width: / textcolor: / fontsize: 等行内默认值
                    applyPlotDefaults(spec, line);
                }
                case TEXT_DATA -> parseText(spec, line);
                case LEGEND -> parseLegend(spec, line);
                case LINE_DATA -> {
                    // LineData（折线）暂未实现：忽略
                }
                default -> {
                    // 无归属的属性行忽略
                }
            }
        }
        return spec;
    }

    private static void applyImageSize(Spec spec, String arguments) {
        Map<String, String> values = keyValues(arguments);
        if (values.containsKey("width")) {
            spec.width = clamp(number(values.get("width"), spec.width), 120, 4000);
        }
        String height = values.get("height");
        if (height != null) {
            if (height.equalsIgnoreCase("auto")) {
                spec.heightAuto = true;
            } else {
                spec.height = clamp(number(height, spec.height), 80, 4000);
            }
        }
        if (values.containsKey("barincrement")) {
            spec.barIncrement = clamp(number(values.get("barincrement"), spec.barIncrement), 6, 200);
        }
    }

    private static void applyPlotArea(Spec spec, String arguments) {
        Map<String, String> values = keyValues(arguments);
        spec.left = clamp(number(values.get("left"), spec.left), 0, 500);
        spec.right = clamp(number(values.get("right"), spec.right), 0, 500);
        spec.top = clamp(number(values.get("top"), spec.top), 0, 500);
        spec.bottom = clamp(number(values.get("bottom"), spec.bottom), 0, 500);
        if (values.containsKey("width")) {
            spec.width = clamp(number(values.get("width"), spec.width) + spec.left + spec.right, 120, 4000);
        }
        if (values.containsKey("height") && !values.get("height").equalsIgnoreCase("auto")) {
            spec.height = clamp(number(values.get("height"), spec.height) + spec.top + spec.bottom, 80, 4000);
        }
    }

    private static void applyPeriod(Spec spec, String arguments) {
        Map<String, String> values = keyValues(arguments);
        if (values.containsKey("from")) {
            spec.from = parseTime(values.get("from"), spec);
            spec.hasPeriod = true;
        }
        if (values.containsKey("till")) {
            spec.till = parseTime(values.get("till"), spec);
            spec.hasPeriod = true;
        }
    }

    private static Scale scale(String arguments, String defaultColor) {
        Map<String, String> values = keyValues(arguments);
        double increment = number(values.get("increment"), 1);
        if (increment <= 0) {
            increment = 1;
        }
        return new Scale(
                values.getOrDefault("unit", "year"),
                increment,
                number(values.get("start"), Double.NaN),
                colorValue(values.getOrDefault("gridcolor", defaultColor), null, defaultColor));
    }

    private static void applyPlotDefaults(Spec spec, String arguments) {
        Map<String, String> values = keyValues(arguments);
        if (values.containsKey("width")) {
            spec.plotBarWidth = clamp(number(values.get("width"), 20), 1, 200);
        }
        if (values.containsKey("textcolor")) {
            spec.defaultTextColor = colorValue(values.get("textcolor"), spec, "#202122");
        }
        if (values.containsKey("fontsize")) {
            spec.defaultFontSize = fontSize(values.get("fontsize"));
        }
    }

    private static Bar parseBar(Map<String, String> values) {
        Bar bar = new Bar();
        bar.name = values.get("bar");
        bar.fromRaw = values.get("from");
        bar.tillRaw = values.get("till");
        bar.atRaw = values.get("at");
        bar.colorId = values.get("color");
        bar.text = values.containsKey("text") ? values.get("text") : bar.name;
        if (values.containsKey("fontsize")) {
            bar.fontSize = fontSize(values.get("fontsize"));
        }
        String shift = values.get("shift");
        if (shift != null) {
            String[] parts = shift.split(",");
            if (parts.length == 2) {
                bar.shiftX = number(parts[0], 0);
                bar.shiftY = number(parts[1], 0);
            }
        }
        return bar;
    }

    private static void parseColor(Spec spec, String line) {
        Map<String, String> values = keyValues(line);
        String id = values.get("id");
        if (id == null || id.isBlank()) {
            return;
        }
        if (values.containsKey("value")) {
            spec.colors.put(id, colorValue(values.get("value"), null, "#36c"));
        }
        if (values.containsKey("legend")) {
            spec.legends.put(id, values.get("legend"));
            spec.legendOrder.add(id);
        }
    }

    private static void parseLegend(Spec spec, String line) {
        Map<String, String> values = keyValues(line);
        String id = values.get("id");
        if (id != null && values.containsKey("text")) {
            spec.legends.put(id, values.get("text"));
            spec.legendOrder.add(id);
            if (values.containsKey("color") && !spec.colors.containsKey(id)) {
                spec.colors.put(id, colorValue(values.get("color"), null, "#36c"));
            }
        }
    }

    private static void parseText(Spec spec, String line) {
        Map<String, String> values = keyValues(line);
        String text = values.get("text");
        String position = values.get("pos");
        if (text == null || position == null) {
            return;
        }
        String[] parts = position.split(",");
        if (parts.length < 2) {
            return;
        }
        String color = values.containsKey("color")
                ? colorValue(values.get("color"), spec, "#202122") : "#202122";
        int fontSize = values.containsKey("fontsize") ? fontSize(values.get("fontsize")) : 10;
        spec.texts.add(new TextItem(number(parts[0], 0), number(parts[1], 0), text, color, fontSize));
    }

    /** 解析 {@code 键:值} 序列。 */
    private static Map<String, String> keyValues(String arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = KEY_VALUE.matcher(arguments == null ? "" : arguments);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(3) != null ? matcher.group(3)
                    : matcher.group(4) != null ? matcher.group(4)
                    : matcher.group(2);
            values.put(key, value.trim());
        }
        return values;
    }

    // ==================== 绘制 ====================

    private static String draw(Spec spec, String ariaLabel) {
        int rowHeight = spec.barIncrement;
        int plotHeight = spec.height - spec.top - spec.bottom;
        if (spec.heightAuto) {
            plotHeight = Math.max(rowHeight, spec.rows.size() * rowHeight);
            spec.height = spec.top + spec.bottom + plotHeight;
        } else if (!spec.rows.isEmpty() && spec.rows.size() * rowHeight > plotHeight) {
            rowHeight = Math.max(6, plotHeight / spec.rows.size());
        }

        int plotX0 = spec.left;
        int plotX1 = spec.width - spec.right;
        int plotY0 = spec.top;
        int plotY1 = plotY0 + plotHeight;
        if (plotX1 - plotX0 < 40 || plotY1 - plotY0 < 20) {
            return null;
        }

        int barWidth = spec.plotBarWidth > 0 ? Math.min(spec.plotBarWidth, rowHeight - 2) : Math.max(4, rowHeight - 6);
        if (barWidth < 2) {
            barWidth = 2;
        }

        StringBuilder svg = new StringBuilder();
        svg.append("<div class=\"mw-timeline\">")
                .append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"mw-timeline-svg\" ")
                .append("width=\"").append(spec.width).append("\" height=\"").append(spec.height)
                .append("\" viewBox=\"0 0 ").append(spec.width).append(' ').append(spec.height)
                .append("\" role=\"img\" aria-label=\"").append(escapeAttribute(ariaLabel)).append("\">");

        // 背景
        svg.append("<rect x=\"0\" y=\"0\" width=\"").append(spec.width).append("\" height=\"")
                .append(spec.height).append("\" fill=\"").append(spec.canvas).append("\"/>");
        if (spec.barsBackground != null) {
            svg.append("<rect x=\"").append(plotX0).append("\" y=\"").append(plotY0)
                    .append("\" width=\"").append(plotX1 - plotX0).append("\" height=\"").append(plotHeight)
                    .append("\" fill=\"").append(spec.barsBackground).append("\"/>");
        }

        // 刻度线（先画，压在柱条下）
        drawScale(svg, spec, spec.minor, plotX0, plotX1, plotY0, plotY1, false);
        drawScale(svg, spec, spec.major, plotX0, plotX1, plotY0, plotY1, true);

        // 柱条
        svg.append("<g class=\"mw-timeline-bars\">");
        int alignLate = "late".equals(spec.alignBars) ? 1 : 0;
        for (int index = 0; index < spec.rows.size(); index++) {
            Row row = spec.rows.get(index);
            int rowTop = alignLate == 1
                    ? plotY1 - rowHeight * (index + 1)
                    : plotY0 + rowHeight * index;
            int barTop = rowTop + (rowHeight - barWidth) / 2;
            if (row.barset != null && !row.barset.isBlank()) {
                svg.append("<text class=\"mw-timeline-barset\" x=\"").append(plotX0 - 6).append("\" y=\"")
                        .append(barTop + barWidth).append("\" text-anchor=\"end\" font-size=\"10\" fill=\"#54595d\">")
                        .append(escape(row.barset)).append("</text>");
            }
            for (Bar bar : row.bars) {
                drawBar(svg, spec, bar, plotX0, plotX1, barTop, barWidth);
            }
        }
        svg.append("</g>");

        // 坐标轴
        svg.append("<g class=\"mw-timeline-axis\">")
                .append("<line x1=\"").append(plotX0).append("\" y1=\"").append(plotY1)
                .append("\" x2=\"").append(plotX1).append("\" y2=\"").append(plotY1)
                .append("\" stroke=\"#a2a9b1\"/>")
                .append("<line x1=\"").append(plotX0).append("\" y1=\"").append(plotY0)
                .append("\" x2=\"").append(plotX0).append("\" y2=\"").append(plotY1)
                .append("\" stroke=\"#a2a9b1\"/>")
                .append("</g>");

        // TextData（绝对像素坐标）
        for (TextItem item : spec.texts) {
            svg.append("<text x=\"").append(item.x).append("\" y=\"").append(item.y)
                    .append("\" font-size=\"").append(item.fontSize).append("\" fill=\"").append(item.color).append("\">")
                    .append(escape(item.text)).append("</text>");
        }

        // 图例（右上角）
        drawLegend(svg, spec, plotX0, plotX1, plotY0);

        svg.append("</svg></div>");
        return svg.toString();
    }

    private static void drawScale(StringBuilder svg, Spec spec, Scale scale,
                                  int plotX0, int plotX1, int plotY0, int plotY1, boolean major) {
        double increment;
        double start;
        if (scale == null) {
            if (!major) {
                return;
            }
            increment = niceIncrement(spec.till - spec.from);
            start = Math.ceil(spec.from / increment) * increment;
        } else {
            increment = scale.increment();
            start = Double.isNaN(scale.start()) ? spec.from : scale.start();
        }
        String color = scale == null ? "#c8ccd1" : scale.gridColor();
        String unit = scale == null ? "year" : scale.unit();
        if (unit.equalsIgnoreCase("month")) {
            increment = increment / 12.0;
        } else if (unit.equalsIgnoreCase("day")) {
            increment = increment / 365.25;
        }

        svg.append("<g class=\"mw-timeline-grid").append(major ? " mw-timeline-grid-major" : " mw-timeline-grid-minor\">");
        int guard = 0;
        for (double value = start; value <= spec.till + 1e-9 && guard < 400; value += increment) {
            guard++;
            if (value < spec.from - 1e-9) {
                continue;
            }
            double x = timeToX(spec, value, plotX0, plotX1);
            svg.append("<line x1=\"").append(round(x)).append("\" y1=\"").append(plotY0)
                    .append("\" x2=\"").append(round(x)).append("\" y2=\"").append(plotY1)
                    .append("\" stroke=\"").append(color).append("\"")
                    .append(major ? "" : " stroke-dasharray=\"2 2\"")
                    .append("/>");
            if (major) {
                svg.append("<text x=\"").append(round(x)).append("\" y=\"").append(plotY1 + 14)
                        .append("\" text-anchor=\"middle\" font-size=\"9\" fill=\"#54595d\">")
                        .append(escape(formatTick(value, increment))).append("</text>");
            }
        }
        svg.append("</g>");
    }

    private static void drawBar(StringBuilder svg, Spec spec, Bar bar,
                                int plotX0, int plotX1, int barTop, int barWidth) {
        int fontSize = bar.fontSize > 0 ? bar.fontSize : spec.defaultFontSize;
        String color = colorValue(bar.colorId, spec, "#36c");
        Double from = resolve(bar.fromRaw, true, spec);
        Double till = resolve(bar.tillRaw, false, spec);
        if (from != null && till != null && till > from) {
            double x = timeToX(spec, from, plotX0, plotX1) + bar.shiftX;
            double width = timeToX(spec, till, plotX0, plotX1) - timeToX(spec, from, plotX0, plotX1);
            svg.append("<rect x=\"").append(round(x)).append("\" y=\"").append(round(barTop + bar.shiftY))
                    .append("\" width=\"").append(round(Math.max(1, width))).append("\" height=\"").append(barWidth)
                    .append("\" fill=\"").append(color).append("\" rx=\"1\"/>");
            if (bar.text != null && !bar.text.isBlank()) {
                drawBarText(svg, bar.text, x, width, barTop + bar.shiftY, barWidth, fontSize);
            }
        } else if (from != null) {
            double x = timeToX(spec, from, plotX0, plotX1) + bar.shiftX;
            double y = barTop + bar.shiftY + barWidth / 2.0;
            svg.append("<circle cx=\"").append(round(x)).append("\" cy=\"").append(round(y))
                    .append("\" r=\"3\" fill=\"").append(color).append("\"/>");
            if (bar.text != null && !bar.text.isBlank()) {
                svg.append("<text x=\"").append(round(x + 5)).append("\" y=\"").append(round(y + fontSize / 3.0))
                        .append("\" font-size=\"").append(fontSize).append("\" fill=\"").append(spec.defaultTextColor)
                        .append("\">").append(escape(bar.text)).append("</text>");
            }
        } else if (bar.atRaw != null) {
            Double at = resolve(bar.atRaw, true, spec);
            if (at != null) {
                double x = timeToX(spec, at, plotX0, plotX1) + bar.shiftX;
                double y = barTop + bar.shiftY + barWidth / 2.0;
                svg.append("<circle cx=\"").append(round(x)).append("\" cy=\"").append(round(y))
                        .append("\" r=\"3\" fill=\"").append(color).append("\"/>");
                if (bar.text != null && !bar.text.isBlank()) {
                    svg.append("<text x=\"").append(round(x + 5)).append("\" y=\"").append(round(y + fontSize / 3.0))
                            .append("\" font-size=\"").append(fontSize).append("\" fill=\"").append(spec.defaultTextColor)
                            .append("\">").append(escape(bar.text)).append("</text>");
                }
            }
        }
    }

    /** 柱条文字：能放进柱条就居中放里面，否则放到柱条起点外侧（右对齐）以免压色难读。 */
    private static void drawBarText(StringBuilder svg, String text, double x, double width,
                                    double top, int barWidth, int fontSize) {
        double textWidth = estimateWidth(text, fontSize);
        double centerY = top + barWidth / 2.0 + fontSize / 3.0;
        if (width > textWidth + 8) {
            svg.append("<text x=\"").append(round(x + width / 2.0)).append("\" y=\"").append(round(centerY))
                    .append("\" text-anchor=\"middle\" font-size=\"").append(fontSize)
                    .append("\" fill=\"#ffffff\">").append(escape(text)).append("</text>");
        } else {
            svg.append("<text x=\"").append(round(x - 4)).append("\" y=\"").append(round(centerY))
                    .append("\" text-anchor=\"end\" font-size=\"").append(fontSize)
                    .append("\" fill=\"#202122\">").append(escape(text)).append("</text>");
        }
    }

    private static void drawLegend(StringBuilder svg, Spec spec, int plotX0, int plotX1, int plotY0) {
        List<String> entries = new ArrayList<>();
        for (String id : spec.legendOrder) {
            if (spec.legends.containsKey(id)) {
                entries.add(id);
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        int lineHeight = 16;
        double x = plotX1 - 4;
        double y = plotY0 + 4;
        svg.append("<g class=\"mw-timeline-legend\">");
        for (String id : entries) {
            String text = spec.legends.get(id);
            String color = spec.colors.getOrDefault(id, "#36c");
            svg.append("<rect x=\"").append(round(x - 12 - estimateWidth(text, 10))).append("\" y=\"")
                    .append(round(y)).append("\" width=\"10\" height=\"10\" fill=\"").append(color).append("\"/>");
            svg.append("<text x=\"").append(round(x - estimateWidth(text, 10))).append("\" y=\"")
                    .append(round(y + 9)).append("\" font-size=\"10\" fill=\"#202122\">")
                    .append(escape(text)).append("</text>");
            y += lineHeight;
        }
        svg.append("</g>");
    }

    // ==================== 时间/坐标工具 ====================

    private static double timeToX(Spec spec, double value, int plotX0, int plotX1) {
        return plotX0 + (value - spec.from) / (spec.till - spec.from) * (plotX1 - plotX0);
    }

    private static Double resolve(String raw, boolean isFrom, Spec spec) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.equals("$")) {
            return isFrom ? spec.from : spec.till;
        }
        if (value.equalsIgnoreCase("now")) {
            return spec.till;
        }
        return parseTime(value, spec);
    }

    private static double parseTime(String raw, Spec spec) {
        String value = raw.trim();
        if (value.contains("/")) {
            String[] parts = value.split("/");
            if (parts.length == 3) {
                try {
                    int year;
                    int month = 1;
                    int day = 1;
                    switch (spec.dateFormat) {
                        case "mm/dd/yyyy" -> {
                            month = Integer.parseInt(parts[0]);
                            day = Integer.parseInt(parts[1]);
                            year = Integer.parseInt(parts[2]);
                        }
                        case "dd/mm/yyyy" -> {
                            day = Integer.parseInt(parts[0]);
                            month = Integer.parseInt(parts[1]);
                            year = Integer.parseInt(parts[2]);
                        }
                        default -> {
                            year = Integer.parseInt(parts[2]);
                            month = Integer.parseInt(parts[0]);
                            day = Integer.parseInt(parts[1]);
                        }
                    }
                    return year + (month - 1) / 12.0 + (day - 1) / 365.25;
                } catch (NumberFormatException ignored) {
                    // 落到下面的数字分支
                }
            }
        }
        return number(value, spec.from);
    }

    private static String formatTick(double value, double increment) {
        if (increment >= 1 && Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static double niceIncrement(double range) {
        if (range <= 0) {
            return 1;
        }
        double raw = range / 8.0;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double normalized = raw / magnitude;
        double step;
        if (normalized <= 1) {
            step = 1;
        } else if (normalized <= 2) {
            step = 2;
        } else if (normalized <= 5) {
            step = 5;
        } else {
            step = 10;
        }
        return step * magnitude;
    }

    private static double estimateWidth(String text, int fontSize) {
        if (text == null) {
            return 0;
        }
        double em = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            em += ch < 0x2E80 ? 0.55 : 1.0;
        }
        return em * fontSize;
    }

    private static String colorValue(String raw, Spec spec, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        if (spec != null && spec.colors.containsKey(value)) {
            return spec.colors.get(value);
        }
        if (COLOR.matcher(value).matches() && value.length() <= 64) {
            return normalizeColor(value);
        }
        return fallback;
    }

    /** 把 EasyTimeline 的 {@code gray(x)} / 0..1 分量的 {@code rgb(r,g,b)} 转成浏览器通用的十六进制色。 */
    private static String normalizeColor(String value) {
        Matcher gray = GRAY.matcher(value);
        if (gray.matches()) {
            double level = number(gray.group(1), 0.5);
            if (level <= 1.0) {
                level *= 255;
            }
            int channel = (int) Math.round(Math.max(0, Math.min(255, level)));
            return String.format(Locale.ROOT, "#%02x%02x%02x", channel, channel, channel);
        }
        Matcher rgb = RGB.matcher(value);
        if (rgb.matches()) {
            double red = number(rgb.group(1), 0);
            double green = number(rgb.group(2), 0);
            double blue = number(rgb.group(3), 0);
            if (red <= 1.0 && green <= 1.0 && blue <= 1.0) {
                return String.format(Locale.ROOT, "#%02x%02x%02x",
                        (int) Math.round(red * 255), (int) Math.round(green * 255), (int) Math.round(blue * 255));
            }
        }
        return value;
    }

    private static int fontSize(String raw) {
        if (raw == null) {
            return 10;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "XS" -> 6;
            case "S" -> 8;
            case "M" -> 10;
            case "L" -> 12;
            case "XL" -> 14;
            default -> clamp(number(raw, 10), 6, 48);
        };
    }

    private static double number(String raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int number(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return (int) Math.round(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String round(double value) {
        double rounded = Math.rint(value * 10) / 10;
        if (rounded == Math.rint(rounded)) {
            return String.valueOf((long) rounded);
        }
        return String.format(Locale.ROOT, "%.1f", rounded);
    }

    /** XML 文本转义（与 core 的 HtmlSanitizer.escapeText 一致）。 */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** XML 属性值转义（与 core 的 HtmlSanitizer.escapeAttribute 一致）。 */
    private static String escapeAttribute(String value) {
        String text = value == null ? "" : value;
        return text.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
