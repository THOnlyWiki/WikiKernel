package cc.thonly.wikikernel.plugin.thonlyteam.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;

import java.time.Year;
import java.util.Map;

/**
 * {@code <team year="2026" event="199" />}：在页面里嵌一块组队区。
 *
 * <ul>
 *     <li>{@code year} 省略时取当前年；</li>
 *     <li>{@code event} 省略或为 0 时列整年的队伍（总览页，只能加入不能发起）；</li>
 *     <li>带上 {@code event} 才是某场活动的组队区，可以发起。</li>
 * </ul>
 *
 * <p>标签只输出一个带 data 属性的容器，内容由 {@code team.js} 拉接口渲染。
 */
public class TeamTag implements ParserTag {

    @Override
    public String name() {
        return "team";
    }

    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        Map<String, String> attributes = call.attributes();
        String year = attribute(attributes, "year");
        if (year.isEmpty()) {
            year = String.valueOf(Year.now().getValue());
        }
        String event = attribute(attributes, "event");
        if (event.isEmpty()) {
            event = "0";
        }
        String eventName = attribute(attributes, "name");

        return "<div class=\"wiki-team\""
                + " data-year=\"" + escape(year) + "\""
                + " data-event=\"" + escape(event) + "\""
                + " data-event-name=\"" + escape(eventName) + "\""
                + " data-api=\"/api/thonly-team\"></div>";
    }

    private static String attribute(Map<String, String> attributes, String key) {
        if (attributes == null) {
            return "";
        }
        String value = attributes.get(key);
        return value == null ? "" : value.trim();
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
