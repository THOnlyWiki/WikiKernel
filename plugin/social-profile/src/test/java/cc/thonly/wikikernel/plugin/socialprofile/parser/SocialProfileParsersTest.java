package cc.thonly.wikikernel.plugin.socialprofile.parser;

import cc.thonly.wikikernel.common.extension.*;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileFakes;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class SocialProfileParsersTest {
    private final SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers()
            .add(1, "甲", "u1.png").add(2, "乙", "u2.png").defaultAvatar("/img/site-avatar.png");
    private final Context context = new Context();

    @Test void avatarSizesAndFallbackMatchUpstream() {
        var parsers = parsers(true);
        assertTrue(invoke(parsers, "avatar", "甲", "l").contains("width=\"75\""));
        assertTrue(invoke(parsers, "avatar", "甲", "ml").contains("width=\"50\""));
        assertTrue(invoke(parsers, "avatar", "甲", "32px").contains("width=\"32\""));
        assertTrue(invoke(parsers, "avatar", "不存在").contains("/img/site-avatar.png"));
        assertEquals("0", context.properties.get(WikiRenderContext.CACHE_EXPIRY_PROPERTY));
    }

    @Test void htmlAndPixelArgumentsCannotInjectMarkup() {
        users.add(3, "<script>坏</script>", "u3.png");
        String html = invoke(parsers(true), "avatar", "<script>坏</script>", "1px\" onerror=alert(1)");
        assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("<script>"));
        assertFalse(html.contains("onerror=")); assertTrue(html.contains("width=\"30\""));
    }

    @Test void listFunctionsBoundCountAndTagsUseSameRenderer() {
        var parsers = parsers(true);
        assertTrue(invoke(parsers, "randomuserswithavatars", "count=1").contains("random-users-avatars"));
        String html = parsers.tag("newusers").render(new ParserTagCall() {
            @Override public WikiRenderContext context() { return context; }
            @Override public String tag() { return "newusers"; }
            @Override public Map<String,String> attributes() { return Map.of("count", "1", "row", "0"); }
            @Override public String body() { return ""; }
            @Override public String expand(String text) { return text; }
        });
        assertEquals(1, html.split("<img", -1).length - 1);
        assertTrue(html.contains("repeat(1,"));
        assertEquals("", invoke(parsers(false), "avatar", "甲"));
    }

    private SocialProfileParsers parsers(boolean enabled) {
        return new SocialProfileParsers(users, SocialProfileFakes.access(null), SocialProfileFakes.support(enabled));
    }
    private String invoke(SocialProfileParsers parsers, String name, String... args) {
        return parsers.function(name).invoke(new ParserFunctionCall() {
            @Override public WikiRenderContext context() { return context; }
            @Override public List<String> args() { return List.of(args); }
            @Override public String expand(String text) { return text; }
            @Override public String trustedHtml(String html) { return html; }
        });
    }
    static class Context implements WikiRenderContext {
        final Map<String,String> properties = new HashMap<>();
        @Override public String pageTitle() { return "测试"; }
        @Override public Map<String,Object> store() { return new HashMap<>(); }
        @Override public void setProperty(String k,String v) { properties.put(k,v); }
        @Override public String property(String k,String fallback) { return properties.getOrDefault(k,fallback); }
        @Override public Map<String,String> properties() { return properties; }
        @Override public String deferWikitext(Supplier<String> resolver) { return resolver.get(); }
    }
}
