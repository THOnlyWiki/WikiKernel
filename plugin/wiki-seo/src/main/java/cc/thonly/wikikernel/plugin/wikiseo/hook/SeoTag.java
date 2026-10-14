package cc.thonly.wikikernel.plugin.wikiseo.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoMetadata;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code <seo>…</seo>} 标签（对应 WikiSEO 的 {@code WikiSEO::fromTag()} + {@code TagParser::parseText()}）。
 * <p>
 * 标签内容按 {@code |} 拆成 {@code key=value} 行；标签 HTML 属性（如 {@code <seo title="x">}）优先级高于内容
 * （与 {@code array_merge($parsedInput, $args)} 一致）。WikiKernel 的 core 会先把标签内容随正文展开
 * （{@code expandBody=true}），这里再展开一次以覆盖属性值这种场景。
 * 标签本身不输出内容（返回空串）。
 */
public final class SeoTag implements ParserTag {

    /** 标签名。 */
    public static final String NAME = "seo";

    private final SeoValidator validator;

    public SeoTag(SeoValidator validator) {
        this.validator = validator;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String render(ParserTagCall call) {
        Map<String, String> params = new LinkedHashMap<>();

        String body = call.body();
        if (body != null && !body.isBlank()) {
            params.putAll(SeoMetadata.parsePairs(List.of(body.split("\\|", -1)), call::expand).values());
        }

        List<String> attributes = new ArrayList<>();
        Map<String, String> provided = call.attributes();
        if (provided != null) {
            for (Map.Entry<String, String> entry : provided.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    attributes.add(entry.getKey() + "=" + entry.getValue());
                }
            }
        }
        params.putAll(SeoMetadata.parsePairs(attributes, call::expand).values());

        SeoMetadata.raw(params).writeTo(call.context(), validator);
        return "";
    }
}
