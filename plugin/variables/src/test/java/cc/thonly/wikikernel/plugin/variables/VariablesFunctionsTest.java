package cc.thonly.wikikernel.plugin.variables;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link VariablesFunctions}：五个解析器函数的语义（对照 MediaWiki Variables 的 parserTests）。
 */
class VariablesFunctionsTest {

    private final FakeContext context = new FakeContext();

    private String call(ParserFunction function, String... args) {
        return function.invoke(new FakeCall(context, List.of(args)));
    }

    @Test
    void defineAndVar() {
        assertEquals("", call(new VariablesFunctions.VarDefine(), " a ", " Test "));
        assertEquals("Test", call(new VariablesFunctions.Var(), " a "));
        // 默认值只在变量缺失或为空串时使用
        assertEquals("Test", call(new VariablesFunctions.Var(), " a ", " 默认 "));
        assertEquals("默认", call(new VariablesFunctions.Var(), " 不存在 ", " 默认 "));
        // 空串变量：按 MediaWiki 语义返回默认值
        call(new VariablesFunctions.VarDefine(), " 空 ", " ");
        assertEquals("兜底", call(new VariablesFunctions.Var(), " 空 ", " 兜底 "));
    }

    @Test
    void defineEchoReturnsValue() {
        assertEquals("Test 2", call(new VariablesFunctions.VarDefineEcho(), " a ", " Test 2 "));
        assertEquals("Test 2", call(new VariablesFunctions.Var(), " a "));
    }

    @Test
    void varExistsUsesOnlyTakenBranch() {
        // 变量未定义：走 else 分支（测试里 else = no）
        assertEquals("no", call(new VariablesFunctions.VarExists(), " a ", " yes ", " no "));
        call(new VariablesFunctions.VarDefine(), " a ", " 1 ");
        assertEquals("yes", call(new VariablesFunctions.VarExists(), " a ", " yes ", " no "));
        assertEquals("1", call(new VariablesFunctions.VarExists(), " a "));
        assertEquals("", call(new VariablesFunctions.VarExists(), " 不存在 "));
    }

    @Test
    void varFinalResolvesAtEnd() {
        // 先请求最终值，再修改变量：应返回「最终」值
        String marker = call(new VariablesFunctions.VarFinal(), " a ", " 默认 ");
        call(new VariablesFunctions.VarDefine(), " a ", " 第一次 ");
        String resolvedFirst = context.resolve(marker);
        assertEquals("第一次", resolvedFirst);
        call(new VariablesFunctions.VarDefine(), " a ", " 最终 ");
        assertEquals("最终", context.resolve(marker));
        // 变量从未定义：用默认值
        String other = call(new VariablesFunctions.VarFinal(), " 不存在 ", " 兜底 ");
        assertEquals("兜底", context.resolve(other));
    }

    // ==================== 测试替身 ====================

    /** 最小渲染上下文：store + 延迟占位（立即替换成真实语义的占位表）。 */
    private static final class FakeContext implements WikiRenderContext {

        private final Map<String, Object> store = new LinkedHashMap<>();
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final Map<String, Supplier<String>> deferred = new LinkedHashMap<>();

        @Override
        public String pageTitle() {
            return "测试页";
        }

        @Override
        public Map<String, Object> store() {
            return store;
        }

        @Override
        public void setProperty(String name, String value) {
            properties.put(name, value);
        }

        @Override
        public String property(String name, String defaultValue) {
            return properties.getOrDefault(name, defaultValue);
        }

        @Override
        public Map<String, String> properties() {
            return Map.copyOf(properties);
        }

        @Override
        public String deferWikitext(Supplier<String> resolver) {
            String marker = "\uE008" + deferred.size() + "\uE008";
            deferred.put(marker, resolver);
            return marker;
        }

        /** 测试用：求出占位当前对应的最终值。 */
        String resolve(String marker) {
            Supplier<String> supplier = deferred.get(marker);
            return supplier == null ? marker : supplier.get();
        }
    }

    /** 最小调用信息：expand 原样返回（本测试不涉及模板展开）。 */
    private record FakeCall(WikiRenderContext context, List<String> args) implements ParserFunctionCall {

        @Override
        public String expand(String wikitext) {
            return wikitext == null ? "" : wikitext;
        }
    }
}
