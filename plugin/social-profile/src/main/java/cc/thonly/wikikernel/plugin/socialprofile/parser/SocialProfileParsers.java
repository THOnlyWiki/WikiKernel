package cc.thonly.wikikernel.plugin.socialprofile.parser;

import cc.thonly.wikikernel.common.extension.*;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 独立实现 SocialProfile 的头像函数与用户列表标签；不复制上游 PHP。 */
@Component
public final class SocialProfileParsers {
    private final UserStore users;
    private final AccessCheck access;
    private final SocialProfileSupport support;

    public SocialProfileParsers(UserStore users, AccessCheck access, SocialProfileSupport support) {
        this.users = users;
        this.access = access;
        this.support = support;
    }

    public ParserFunction function(String name) {
        return new ParserFunction() {
            @Override public String name() { return name; }
            @Override public String invoke(ParserFunctionCall call) {
                if (!support.enabled()) { return ""; }
                call.context().setProperty(WikiRenderContext.CACHE_EXPIRY_PROPERTY, "0");
                List<String> args = call.args().stream().map(call::expand).toList();
                String html;
                if ("avatar".equals(name)) {
                    String username = args.isEmpty() ? "" : args.get(0).trim();
                    String size = args.size() < 2 ? "m" : args.get(1);
                    var user = users.byName(username).filter(SocialProfileParsers.this::readable).orElse(null);
                    html = image(user, size);
                } else {
                    Map<String, String> options = new LinkedHashMap<>();
                    for (String arg : args) {
                        int equals = arg.indexOf('=');
                        if (equals > 0) { options.put(arg.substring(0, equals).trim().toLowerCase(Locale.ROOT), arg.substring(equals + 1).trim()); }
                        else if (!arg.isBlank()) { options.putIfAbsent("count", arg.trim()); }
                    }
                    html = list(name, options);
                }
                return call.trustedHtml(html);
            }
        };
    }

    public ParserTag tag(String name) {
        return new ParserTag() {
            @Override public String name() { return name; }
            @Override public String render(ParserTagCall call) {
                if (!support.enabled()) { return ""; }
                call.context().setProperty(WikiRenderContext.CACHE_EXPIRY_PROPERTY, "0");
                return list(name, call.attributes());
            }
        };
    }

    private String list(String name, Map<String, String> options) {
        int count = bounded(options.get("count"), 10, 48);
        int columns = bounded(options.get("row"), "newusers".equals(name) ? 5 : 4, 12);
        String size = options.getOrDefault("size", "ml");
        List<UserStore.UserRef> entries = count == 0 ? List.of()
                : "newusers".equals(name) ? users.newest(count) : users.randomWithAvatar(count);
        String css = "newusers".equals(name) ? "new-users" : "random-users-avatars";
        StringBuilder html = new StringBuilder("<div class=\"sp-parser-users ").append(css)
                .append("\" style=\"display:grid;grid-template-columns:repeat(").append(Math.max(1, columns))
                .append(",minmax(0,1fr));gap:8px\">");
        entries.stream().filter(this::readable).limit(count).forEach(user -> html
                .append("<a rel=\"nofollow\" href=\"").append(escape(WikiUrls.pagePath("用户:" + user.username())))
                .append("\" title=\"").append(escape(user.username())).append("\">")
                .append(image(user, size)).append("</a>"));
        return html.append("</div>").toString();
    }

    private boolean readable(UserStore.UserRef user) {
        return access.canRead("用户:" + user.username(), Viewer.ANONYMOUS);
    }

    private String image(UserStore.UserRef user, String size) {
        int pixels = size(size);
        String url = user == null ? users.defaultAvatarUrl() : user.avatarUrl(users.defaultAvatarUrl());
        return "<img class=\"sp-parser-avatar\" src=\"" + escape(url) + "\" alt=\""
                + escape(user == null ? "" : user.username()) + "\" width=\"" + pixels + "\" height=\"" + pixels
                + "\" loading=\"lazy\" style=\"max-width:100%;object-fit:contain\"/>";
    }

    private static int size(String value) {
        String text = value == null ? "m" : value.trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "s" -> 16;
            case "ml" -> 50;
            case "l" -> 75;
            default -> text.matches("[1-9][0-9]{0,3}px") ? bounded(text.substring(0, text.length() - 2), 30, 512) : 30;
        };
    }

    private static int bounded(String value, int fallback, int maximum) {
        try { return Math.max(0, Math.min(Integer.parseInt(value), maximum)); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String escape(String value) { return SocialProfileSupport.escape(value); }
}
