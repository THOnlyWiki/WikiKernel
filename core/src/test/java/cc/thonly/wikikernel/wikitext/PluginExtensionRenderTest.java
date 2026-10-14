package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.common.extension.BehaviorSwitch;
import cc.thonly.wikikernel.common.extension.MagicWord;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件扩展点在渲染器里的接线：
 * 解析器函数（{@code {{#var:…}}}）、魔术字（{@code {{NUMBEROFVIEWS}}}）、行为开关（{@code __NOTITLE__}）、
 * 扩展标签（{@code <templatedata>} 原文保护 / {@code <seo>} 随正文展开）、延迟占位（{@code #var_final}）。
 */
class PluginExtensionRenderTest {

    private WikitextRenderer renderer() {
        return new WikitextRenderer(new WikitextProperties(), title -> Optional.empty());
    }

    private static RenderContextState context(List<ParserFunction> functions, List<MagicWord> magicWords,
                                              List<BehaviorSwitch> switches, List<ParserTag> tags) {
        return new RenderContextState("测试页", functions, magicWords, switches, tags);
    }

    @Test
    void parserFunctionReadsAndWritesRenderStore() {
        ParserFunction vardefine = new ParserFunction() {
            @Override
            public String name() {
                return "vardefine";
            }

            @Override
            public String invoke(ParserFunctionCall call) {
                String name = call.args().size() > 0 ? call.args().get(0).trim() : "";
                String value = call.args().size() > 1 ? call.expand(call.args().get(1)).trim() : "";
                call.context().store().put(name, value);
                return "";
            }
        };
        ParserFunction var = new ParserFunction() {
            @Override
            public String name() {
                return "var";
            }

            @Override
            public String invoke(ParserFunctionCall call) {
                String name = call.args().isEmpty() ? "" : call.args().get(0).trim();
                Object value = call.context().store().get(name);
                if (value == null || String.valueOf(value).isEmpty()) {
                    return call.args().size() > 1 ? call.expand(call.args().get(1)).trim() : "";
                }
                return String.valueOf(value);
            }
        };
        RenderContextState state = context(List.of(vardefine, var), List.of(), List.of(), List.of());
        WikitextRenderer.RenderResult result = renderer().renderWithMeta(
                "{{#vardefine: x | 你好 }}值：{{#var: x }}|默认：{{#var: y | 兜底 }}", "测试页", state);
        assertTrue(result.html().contains("值：你好"), result.html());
        assertTrue(result.html().contains("默认：兜底"), result.html());
    }

    @Test
    void magicWordAndBehaviorSwitchAndDeferredPlaceholder() {
        MagicWord number = new MagicWord() {
            @Override
            public String name() {
                return "NUMBEROFVIEWS";
            }

            @Override
            public String value(WikiRenderContext context) {
                return "42";
            }
        };
        BehaviorSwitch notitle = new BehaviorSwitch() {
            @Override
            public String name() {
                return "NOTITLE";
            }

            @Override
            public void apply(WikiRenderContext context) {
                context.setProperty("notitle", "1");
            }
        };
        ParserFunction vardefine = new ParserFunction() {
            @Override
            public String name() {
                return "vardefine";
            }

            @Override
            public String invoke(ParserFunctionCall call) {
                call.context().store().put(call.args().get(0).trim(), call.expand(call.args().get(1)).trim());
                return "";
            }
        };
        ParserFunction varFinal = new ParserFunction() {
            @Override
            public String name() {
                return "var_final";
            }

            @Override
            public String invoke(ParserFunctionCall call) {
                String name = call.args().isEmpty() ? "" : call.args().get(0).trim();
                WikiRenderContext context = call.context();
                String marker = context.deferWikitext(() -> String.valueOf(context.store().getOrDefault(name, "")));
                return marker;
            }
        };
        RenderContextState state = context(List.of(vardefine, varFinal), List.of(number), List.of(notitle), List.of());
        WikitextRenderer.RenderResult result = renderer().renderWithMeta(
                "__NOTITLE__浏览量：{{NUMBEROFVIEWS}}\n\n{{#var_final: 结尾 }}{{#vardefine: 结尾 | 最后的值 }}", "测试页", state);

        assertFalse(result.html().contains("__NOTITLE__"), "行为开关应被剥离");
        assertEquals("1", state.properties().get("notitle"));
        assertTrue(result.html().contains("浏览量：42"), result.html());
        assertTrue(result.html().contains("最后的值"), "延迟占位应替换为最终变量值：" + result.html());
    }

    @Test
    void rawTagIsProtectedFromTemplateExpansionAndRendered() {
        ParserTag templatedata = new ParserTag() {
            @Override
            public String name() {
                return "templatedata";
            }

            @Override
            public boolean expandBody() {
                return false;
            }

            @Override
            public String render(ParserTagCall call) {
                return "<table class=\"templatedata\"><tr><td>" + call.body().trim() + "</td></tr></table>";
            }
        };
        RenderContextState state = context(List.of(), List.of(), List.of(), List.of(templatedata));
        WikitextRenderer.RenderResult result = renderer().renderWithMeta(
                "前文\n<templatedata>{ \"params\": { \"名字\": {} } }</templatedata>\n后文", "模板:测试", state);
        assertTrue(result.html().contains("templatedata"), result.html());
        assertTrue(result.html().contains("\"名字\""), "JSON 原文应保留：" + result.html());
        assertFalse(result.html().contains("&lt;templatedata"), "标签不应被转义输出：" + result.html());
    }

    @Test
    void selfClosingPluginTagsAreSupported() {
        ParserTag flash = new ParserTag() {
            @Override
            public String name() {
                return "flash-player";
            }

            @Override
            public boolean expandBody() {
                return false;
            }

            @Override
            public String render(ParserTagCall call) {
                return "<div data-flash-player=\"1\" data-file=\"" + call.attributes().getOrDefault("file", "")
                        + "\"></div>";
            }
        };
        RenderContextState state = context(List.of(), List.of(), List.of(), List.of(flash));
        WikitextRenderer.RenderResult result = renderer().renderWithMeta(
                "<flash-player file=\"动画.swf\" width=\"320\" />", "测试页", state);
        assertTrue(result.html().contains("data-flash-player=\"1\""), result.html());
        assertTrue(result.html().contains("data-file=\"动画.swf\""), result.html());
        assertFalse(result.html().contains("&lt;flash-player"), result.html());
    }

    @Test
    void expandableTagSeesExpandedWikiTextAndWritesProperty() {
        ParserTag seo = new ParserTag() {
            @Override
            public String name() {
                return "seo";
            }

            @Override
            public String render(ParserTagCall call) {
                String body = call.body();
                if (body.contains("description=")) {
                    String value = body.substring(body.indexOf("description=") + "description=".length()).trim();
                    call.context().setProperty("description", value);
                }
                return "";
            }
        };
        RenderContextState state = context(List.of(), List.of(), List.of(), List.of(seo));
        renderer().renderWithMeta("<seo>description=这是模板里的值</seo>\n正文", "测试页", state);
        assertEquals("这是模板里的值", state.properties().get("description"));
    }
}
