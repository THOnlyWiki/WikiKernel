package cc.thonly.wikikernel.sidebar;

import cc.thonly.wikikernel.common.web.WikiUrls;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 MediaWiki:Sidebar 风格的侧边栏定义：
 *
 * <pre>
 * * 导航
 * ** 首页
 * ** 特殊:全部页面|全部页面
 * * 工具
 * ** https://example.com|外部链接
 * </pre>
 *
 * {@code *} 开头是分组标题，{@code **} 开头是条目；条目写法 {@code 目标|显示文字}，
 * 不写显示文字时直接用目标；目标以 http(s):// 开头视为外链。
 */
public final class SidebarParser {

    private SidebarParser() {
    }

    public static List<SidebarSection> parse(String content) {
        List<SidebarSection> sections = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return sections;
        }
        String title = null;
        List<SidebarItem> items = new ArrayList<>();
        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("**")) {
                String body = line.substring(2).strip();
                if (body.isEmpty()) {
                    continue;
                }
                if (title == null) {
                    title = "";
                }
                items.add(toItem(body));
            } else if (line.startsWith("*")) {
                if (title != null) {
                    sections.add(new SidebarSection(title, List.copyOf(items)));
                    items = new ArrayList<>();
                }
                title = line.substring(1).strip();
            }
        }
        if (title != null) {
            sections.add(new SidebarSection(title, List.copyOf(items)));
        }
        return sections;
    }

    private static SidebarItem toItem(String body) {
        int pipe = body.indexOf('|');
        String target = (pipe < 0 ? body : body.substring(0, pipe)).strip();
        String label = (pipe < 0 ? body : body.substring(pipe + 1)).strip();
        if (label.isEmpty()) {
            label = target;
        }
        boolean external = target.matches("(?i)^https?://.*");
        String href = external ? target : WikiUrls.pagePrefix() + WikiUrls.normalizeSpecialTitle(target).replace(' ', '_');
        return new SidebarItem(label, href, external);
    }
}
