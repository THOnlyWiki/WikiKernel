package cc.thonly.wikikernel.plugin.gadgets.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.gadgets.model.GadgetDefinition;
import cc.thonly.wikikernel.plugin.gadgets.service.GadgetService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gadgets 的前端注入（仿 MediaWiki 的 ResourceLoader 加载 Gadget 模块，站点级）：
 * <ul>
 *     <li>已启用 Gadget 的 CSS 资源（{@code ?action=raw&ctype=text/css}）与 JS 资源
 *     （{@code ?action=raw&ctype=text/javascript}，{@code .ts} 由 core 编译后返回）；</li>
 *     <li>声明的模块依赖写进 {@code window.WIKI_CONFIG.gadgetDependencies}，
 *     由 {@code /js/js-compat.js} 对照「未内置模块」表给出明确告警。</li>
 * </ul>
 * {@link #siteWide()} = true：所有页面（含特殊页 / 编辑页）都要加载。
 */
public class GadgetsHeadProvider implements HtmlHeadProvider {

    private final GadgetService gadgets;

    public GadgetsHeadProvider(GadgetService gadgets) {
        this.gadgets = gadgets;
    }

    @Override
    public int order() {
        return 700;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!gadgets.enabled()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String title : gadgets.styleTitles()) {
            items.add("<link rel=\"stylesheet\" href=\"" + rawUrl(title, "text/css") + "\">");
        }
        List<GadgetDefinition> definitions = gadgets.enabledDefinitions();
        List<Map<String, Object>> dependencies = new ArrayList<>();
        for (GadgetDefinition definition : definitions) {
            if (definition.dependencies().isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", definition.name());
            row.put("dependencies", definition.dependencies());
            dependencies.add(row);
        }
        if (!dependencies.isEmpty()) {
            items.add("<script>window.WIKI_CONFIG = window.WIKI_CONFIG || {};"
                    + "window.WIKI_CONFIG.gadgetDependencies = " + json(dependencies) + ";</script>");
        }
        for (String title : gadgets.scriptTitles()) {
            items.add("<script defer src=\"" + rawUrl(title, "text/javascript") + "\"></script>");
        }
        return items;
    }

    /** 资源页标题 → {@code ?action=raw} 地址（标题逐段编码，保留子页面斜杠与命名空间冒号）。 */
    private static String rawUrl(String title, String contentType) {
        StringBuilder path = new StringBuilder("/wiki/");
        String[] segments = title.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                path.append('/');
            }
            path.append(encodeSegment(segments[i]));
        }
        return path.append("?action=raw&ctype=").append(contentType).toString();
    }

    /** 单段标题编码：空格 → 下划线，保留 {@code :}（与 core 的 WikiUrls 一致）。 */
    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment.replace(' ', '_'), StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%3A", ":");
    }

    /** 极简 JSON（只有字符串与字符串数组，够 gadgetDependencies 用）。 */
    private static String json(List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            Map<String, Object> row = rows.get(i);
            out.append("{\"name\":\"").append(escape(String.valueOf(row.get("name")))).append("\",\"dependencies\":[");
            @SuppressWarnings("unchecked")
            List<String> deps = (List<String>) row.get("dependencies");
            for (int j = 0; j < deps.size(); j++) {
                if (j > 0) {
                    out.append(',');
                }
                out.append('"').append(escape(deps.get(j))).append('"');
            }
            out.append("]}");
        }
        return out.append(']').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e");
    }
}
