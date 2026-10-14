package cc.thonly.wikikernel.plugin.wikiseo.hook;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoMetadata;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoValidator;
import java.util.List;

/**
 * {@code {{#seo:…}}} 解析器函数（对应 WikiSEO 的 {@code WikiSEO::fromParserFunction()}）。
 * <p>
 * 参数是 core 按顶层 {@code |} 拆好的原文，这里按 {@code key=value} 解析（第一个 {@code =} 拆分）、
 * 展开、清洗，然后写入渲染上下文（会被持久化为页面属性）。
 * 与 MediaWiki 一致：函数本身不输出可见内容，只返回 {@code <!-- WikiSEO -->} 注释。
 */
public final class SeoParserFunction implements ParserFunction {

    /** 函数名（{@code {{#seo:…}}}）。 */
    public static final String NAME = "seo";

    private final SeoValidator validator;

    public SeoParserFunction(SeoValidator validator) {
        this.validator = validator;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> aliases() {
        return List.of();
    }

    @Override
    public String invoke(ParserFunctionCall call) {
        SeoMetadata.parsePairs(call.args(), call::expand).writeTo(call.context(), validator);
        return "<!-- WikiSEO -->";
    }
}
