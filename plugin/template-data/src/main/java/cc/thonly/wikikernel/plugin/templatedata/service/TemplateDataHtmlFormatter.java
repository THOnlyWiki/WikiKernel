package cc.thonly.wikikernel.plugin.templatedata.service;

import cc.thonly.wikikernel.plugin.templatedata.TemplateDataMessages;
import cc.thonly.wikikernel.plugin.templatedata.model.TemplateDataStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模板文档表格（移植 {@code TemplateDataHtmlFormatter}，REL1_43）。
 * <p>
 * 输出结构与 CSS 类名尽量与原版一致（{@code mw-templatedata-doc-*}、{@code mw-templatedata-*}），
 * 便于沿用 MediaWiki 的样式 / 用户脚本。与原版的差异：
 * <ul>
 *     <li>不输出 OOUI 图标（{@code IconWidget}）与编辑入口 {@code <mw:edittemplatedata>}（没有编辑 GUI）；</li>
 *     <li>原版靠 ResourceLoader 加载 {@code ext.templateData} / {@code ext.templateData.images} 与
 *     {@code jquery.tablesorter}，这里用一小段内联 {@code <style>} 代替（见 {@link #styles()}），
 *     参数超过 1 个时保留 {@code sortable} 类名但没有排序脚本。</li>
 * </ul>
 */
public final class TemplateDataHtmlFormatter {

    /** 内联样式（等价于原版 {@code ext.templateData} 里的几条规则）。 */
    private static final String STYLE =
            ".mw-templatedata-doc-params caption{text-align:left}"
                    + ".mw-templatedata-doc-params .mw-templatedata-caption{font-size:1.2em}"
                    + ".mw-templatedata-doc-params tbody>tr>th{text-align:left;font-weight:normal}"
                    + ".mw-templatedata-doc-param-alias{color:#555}"
                    + ".mw-templatedata-doc-muted{font-style:italic;color:#555}"
                    + ".mw-templatedata-doc-param-status-required{font-weight:bold}"
                    + ".mw-templatedata-format{font-weight:normal;font-size:.85em;margin-left:.25em}";

    private final TemplateDataMessages messages;
    private final String languageCode;

    public TemplateDataHtmlFormatter(TemplateDataMessages messages, String languageCode) {
        this.messages = messages;
        this.languageCode = languageCode;
    }

    /** 渲染错误框（校验失败时替代文档表格，不写页面属性）。 */
    public static String errorBox(TemplateDataStatus status, TemplateDataMessages messages) {
        return Html.errorBox(status.isOk() ? "" : status.text(messages));
    }

    /** 文档表格所需的样式（每次渲染只输出一次，由标签实现控制）。 */
    public static String styles() {
        return "<style>" + STYLE + "</style>";
    }

    /** 按 {@link #languageCode} 解析接口文本后渲染文档表格。 */
    public String getHtml(TemplateDataBlob blob) {
        return getHtml(blob.getDataInLanguage(languageCode));
    }

    /** 渲染文档表格（{@code data} 里的接口文本应已解析成字符串）。 */
    public String getHtml(Map<String, Object> data) {
        String format = data.get("format") instanceof String text ? text : null;
        String formatMessage = null;
        if (format != null) {
            formatMessage = TemplateDataValidator.PREDEFINED_FORMATS.containsKey(format)
                    ? messages.text("templatedata-doc-format-" + format)
                    : messages.text("templatedata-doc-format-custom");
        }

        Map<String, Object> params = Values.asMap(data.get("params"));
        if (params == null) {
            params = Values.newMap();
        }
        String sorting = params.size() > 1 ? " sortable" : "";

        String descriptionClass = data.get("description") == null
                ? "mw-templatedata-doc-desc mw-templatedata-doc-muted"
                : "mw-templatedata-doc-desc";
        String description = data.get("description") == null
                ? messages.text("templatedata-doc-desc-empty")
                : String.valueOf(data.get("description"));

        StringBuilder caption = new StringBuilder(Html.rawElement("p",
                Html.attrs("class", "mw-templatedata-caption"),
                Html.escape(messages.text("templatedata-doc-params"))));
        if (formatMessage != null) {
            // 原版这里还有一个 OOUI 图标（template-format-inline / block / settings）
            caption.append(Html.rawElement("p", null,
                    Html.element("span", Html.attrs("class", "mw-templatedata-format"), formatMessage)));
        }

        StringBuilder html = new StringBuilder();
        html.append("<header>")
                .append(Html.element("p", Html.attrs("class", descriptionClass), description))
                .append("</header>")
                .append("<table class=\"wikitable mw-templatedata-doc-params").append(sorting).append("\">")
                .append(Html.rawElement("caption", null, caption.toString()))
                .append("<thead><tr>")
                .append(Html.element("th", Html.attrs("colspan", 2), messages.text("templatedata-doc-param-name")))
                .append(Html.element("th", null, messages.text("templatedata-doc-param-desc")))
                .append(Html.element("th", null, messages.text("templatedata-doc-param-type")))
                .append(Html.element("th", null, messages.text("templatedata-doc-param-status")))
                .append("</tr></thead>")
                .append("<tbody>");

        List<Object> paramNames = Values.asList(data.get("paramOrder"));
        List<String> names = new ArrayList<>();
        if (paramNames != null) {
            for (Object name : paramNames) {
                String text = Values.phpString(name);
                if (text != null) {
                    names.add(text);
                }
            }
        } else {
            names.addAll(params.keySet());
        }

        if (names.isEmpty()) {
            // 没有参数的提示
            html.append("<tr>")
                    .append(Html.element("td",
                            Html.attrs("class", "mw-templatedata-doc-muted", "colspan", 7),
                            messages.text("templatedata-doc-no-params-set")))
                    .append("</tr>");
        }

        for (String paramName : names) {
            Map<String, Object> param = Values.asMap(params.get(paramName));
            if (param == null) {
                continue;
            }
            html.append(formatParameterTableRow(paramName, param));
        }
        html.append("</tbody></table>");

        return Html.rawElement("section", Html.attrs("class", "mw-templatedata-doc-wrap"), html.toString());
    }

    private String formatParameterTableRow(String paramName, Map<String, Object> param) {
        List<String> allParamNames = new ArrayList<>();
        allParamNames.add(Html.element("code", null, paramName));
        List<Object> aliases = Values.asList(param.get("aliases"));
        if (aliases != null) {
            for (Object alias : aliases) {
                String text = Values.phpString(alias);
                if (text != null) {
                    allParamNames.add(Html.element("code",
                            Html.attrs("class", "mw-templatedata-doc-param-alias"), text));
                }
            }
        }

        List<String> suggestedValues = new ArrayList<>();
        List<Object> values = Values.asList(param.get("suggestedvalues"));
        if (values != null) {
            for (Object value : values) {
                String text = Values.phpString(value);
                if (text != null) {
                    suggestedValues.add(Html.element("code", null, text));
                }
            }
        }

        String status;
        if (Values.truthy(param.get("deprecated"))) {
            status = "deprecated";
        } else if (Values.truthy(param.get("required"))) {
            status = "required";
        } else if (Values.truthy(param.get("suggested"))) {
            status = "suggested";
        } else {
            status = "optional";
        }
        int sortValue = switch (status) {
            case "deprecated" -> -1;
            case "suggested" -> 1;
            case "required" -> 2;
            default -> 0;
        };

        String label = param.get("label") == null ? paramName : String.valueOf(param.get("label"));
        String description = param.get("description") instanceof String text
                ? text : messages.text("templatedata-doc-param-desc-empty");
        String descriptionClass = Values.truthy(param.get("description")) ? null : "mw-templatedata-doc-muted";
        String type = param.get("type") instanceof String text ? text : "unknown";

        StringBuilder details = new StringBuilder();
        if (!suggestedValues.isEmpty()) {
            details.append(Html.element("dt", null, messages.text("templatedata-doc-param-suggestedvalues")))
                    .append(Html.rawElement("dd", null, String.join(" ", suggestedValues)));
        }
        if (param.get("default") != null) {
            details.append(Html.element("dt", null, messages.text("templatedata-doc-param-default")))
                    .append(Html.element("dd", null, String.valueOf(param.get("default"))));
        }
        if (param.get("example") != null) {
            details.append(Html.element("dt", null, messages.text("templatedata-doc-param-example")))
                    .append(Html.element("dd", null, String.valueOf(param.get("example"))));
        }
        if (param.get("autovalue") != null) {
            details.append(Html.element("dt", null, messages.text("templatedata-doc-param-autovalue")))
                    .append(Html.rawElement("dd", null,
                            Html.element("code", null, String.valueOf(param.get("autovalue")))));
        }

        return "<tr>"
                // 标签
                .concat(Html.element("th", null, label))
                // 参数名与别名
                .concat(Html.rawElement("td", Html.attrs("class", "mw-templatedata-doc-param-name"),
                        String.join(" ", allParamNames)))
                // 描述（含推荐值 / 默认值 / 示例值 / 自动值）
                .concat(Html.rawElement("td", null,
                        Html.element("p", Html.attrs("class", descriptionClass), description)
                                + Html.rawElement("dl", null, details.toString())))
                // 类型
                .concat(Html.element("td",
                        Html.attrs("class", "mw-templatedata-doc-param-type"
                                + ("unknown".equals(type) ? " mw-templatedata-doc-muted" : "")),
                        messages.text("templatedata-doc-param-type-" + type)))
                // 状态
                .concat(Html.element("td",
                        Html.attrs("class", "mw-templatedata-doc-param-status-" + status,
                                "data-sort-value", sortValue),
                        messages.text("templatedata-doc-param-status-" + status)))
                .concat("</tr>");
    }
}
