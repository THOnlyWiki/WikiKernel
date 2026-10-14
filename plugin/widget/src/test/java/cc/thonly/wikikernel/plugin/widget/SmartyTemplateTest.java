package cc.thonly.wikikernel.plugin.widget;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迷你 Smarty 模板引擎（微件）：变量 / 点号路径 / 下标、修饰符、if / elseif / else、
 * 布尔与比较条件、foreach、注释与错误处理。
 */
class SmartyTemplateTest {

    private String render(String source, Map<String, Object> params) {
        return SmartyTemplate.compile(source).render(params);
    }

    @Test
    void substitutesVariablesWithModifiers() {
        String html = render(
                "<iframe src=\"//host/embed/<!--{$id|escape:'url'}-->\" width=\"<!--{$width|default:'420'}-->\""
                        + " title=\"<!--{$title|escape:'html'}-->\">框：<!--{$missing|default:'无标题'}--></iframe>",
                Map.of("id", "a b/c", "title", "<b>Hi</b>"));
        assertTrue(html.contains("//host/embed/a%20b%2Fc"), html);
        assertTrue(html.contains("width=\"420\""), html);
        assertTrue(html.contains("title=\"&lt;b&gt;Hi&lt;/b&gt;\""), html);
        assertTrue(html.contains("框：无标题"), html);
    }

    @Test
    void resolvesNestedPathsIndexesAndChineseNames() {
        String html = render("<!--{$a.b}-->|<!--{$a.c.d}-->|<!--{$list[0]}-->|<!--{$list.1}-->|<!--{$宽度}-->",
                Map.of("a", Map.of("b", "1", "c", Map.of("d", "2")),
                        "list", List.of("x", "y"),
                        "宽度", "600"));
        assertEquals("1|2|x|y|600", html);
    }

    @Test
    void rendersIfElseifElseAndConditions() {
        String chain = "<!--{if $show}-->A<!--{elseif $fallback}-->B<!--{else}-->C<!--{/if}-->";
        assertEquals("A", render(chain, Map.of("show", "1")));
        assertEquals("B", render(chain, Map.of("fallback", "1")));
        assertEquals("C", render(chain, Map.of()));

        String equals = "<!--{if $type == 'video'}-->V<!--{else}-->O<!--{/if}-->";
        assertEquals("V", render(equals, Map.of("type", "video")));
        assertEquals("O", render(equals, Map.of("type", "audio")));
        assertEquals("O", render(equals, Map.of()));

        String notEquals = "<!--{if $type != 'audio'}-->V<!--{/if}-->";
        assertEquals("V", render(notEquals, Map.of("type", "video")));
        assertEquals("", render(notEquals, Map.of("type", "audio")));

        String negated = "<!--{if !$mute}-->sound<!--{/if}-->";
        assertEquals("sound", render(negated, Map.of()));
        assertEquals("", render(negated, Map.of("mute", "1")));

        assertEquals("yes", render("<!--{if $a && $b}-->yes<!--{/if}-->", Map.of("a", "1", "b", "x")));
        assertEquals("", render("<!--{if $a && $b}-->yes<!--{/if}-->", Map.of("a", "1")));
        assertEquals("yes", render("<!--{if $a || $b}-->yes<!--{/if}-->", Map.of("b", "x")));
    }

    @Test
    void rendersBooleansFromWidgetParameters() {
        String source = "<!--{if $autoplay}-->autoplay<!--{/if}-->";
        assertEquals("autoplay", render(source, Map.of("autoplay", Boolean.TRUE)));
        assertEquals("", render(source, Map.of("autoplay", Boolean.FALSE)));
        assertEquals("", render(source, Map.of()));
    }

    @Test
    void rendersForeachAndComments() {
        String html = render(
                "<!--{* 注释不输出 *}--><ul><!--{foreach from=$items item=x}-->"
                        + "<li><!--{$x|escape:'html'}--></li><!--{/foreach}--></ul>",
                Map.of("items", List.of("a", "<b>")));
        assertEquals("<ul><li>a</li><li>&lt;b&gt;</li></ul>", html);
    }

    @Test
    void keepsTextAndUnknownModifiersUntouched() {
        assertEquals("hello\nworld", render("hello\nworld", Map.of()));
        assertEquals("x", render("<!--{$v|unknown}-->", Map.of("v", "x")));
        assertFalse(render("<!--{$v}-->", Map.of()).contains("$v"));
    }

    @Test
    void malformedTemplatesThrow() {
        assertThrows(IllegalArgumentException.class, () -> SmartyTemplate.compile("<!--{if $x}-->no end"));
        assertThrows(IllegalArgumentException.class, () -> SmartyTemplate.compile("<!--{/if}-->"));
        assertThrows(IllegalArgumentException.class,
                () -> SmartyTemplate.compile("<!--{foreach from=$x}-->a<!--{/foreach}-->"));
    }
}
