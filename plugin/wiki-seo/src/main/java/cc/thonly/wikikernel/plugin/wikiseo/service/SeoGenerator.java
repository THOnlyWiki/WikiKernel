package cc.thonly.wikikernel.plugin.wikiseo.service;

import java.util.List;

/**
 * SEO 元数据生成器（对应 MediaWiki WikiSEO 的 {@code GeneratorInterface} + {@code AbstractBaseGenerator}）。
 * <p>
 * 生成器名与 {@code $wgMetadataGenerators} 里的值一致（{@code OpenGraph} / {@code Twitter} /
 * {@code SchemaOrg} / {@code Citation} / {@code DublinCore}）。{@code MetaTag} 生成器总是启用
 * （与 WikiSEO 的 {@code instantiateMetadataPlugins()} 一致），不需要写进配置。
 */
public interface SeoGenerator {

    /** 生成器名（配置里使用的名字）。 */
    String name();

    /** 该生成器额外接受的参数名（会合并进 {@link SeoValidator}）；默认无额外参数。 */
    default List<String> allowedParameters() {
        return List.of();
    }

    /** 生成要插入 {@code <head>} 的 HTML 片段。 */
    List<String> build(SeoHead head);
}
