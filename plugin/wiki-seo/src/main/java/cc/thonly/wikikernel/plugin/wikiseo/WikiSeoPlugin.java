package cc.thonly.wikikernel.plugin.wikiseo;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.wikiseo.hook.SeoHeadProvider;
import cc.thonly.wikikernel.plugin.wikiseo.hook.SeoParserFunction;
import cc.thonly.wikikernel.plugin.wikiseo.hook.SeoTag;
import cc.thonly.wikikernel.plugin.wikiseo.service.CitationGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.DublinCoreGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.MetaTagGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.OpenGraphGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.SchemaOrgGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoValidator;
import cc.thonly.wikikernel.plugin.wikiseo.service.TwitterGenerator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * WikiSEO 扩展移植（对应 MediaWiki 的 {@code WikiSEO} 2.7.0 / REL1_43）：
 * <ul>
 *     <li>双入口：{@code {{#seo:title=…|description=…}}} 解析器函数与 {@code <seo>…</seo>} 标签；</li>
 *     <li>参数写入页面属性（core 会持久化到页面属性表，相当于 MediaWiki 的 page_props）；</li>
 *     <li>词条阅读页由 {@link SeoHeadProvider} 输出 meta / link / JSON-LD 片段并覆盖 {@code <title>}；</li>
 *     <li>生成器：MetaTag（总是启用）、OpenGraph、Twitter、SchemaOrg（默认）、Citation、DublinCore；</li>
 *     <li>自动描述：没有手工 description 时用页面正文现场生成（本机化实现，替代 TextExtracts）。</li>
 * </ul>
 * 未移植的部分（社交分享图片生成、Scribunto {@code mw.ext.seo}、REST 图片接口、PageImages 相关项、
 * InfoAction 等）见插件 {@code README.md}。
 */
public class WikiSeoPlugin implements WikiPlugin {

    /** 注入页面访问桥（自动描述与页面更新时间用）。 */
    @Autowired
    private WikiPageStore wikiPageStore;

    /** 本地化文案（core 的 MessageSource 已合并插件消息）。 */
    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("wiki-seo", "WikiSEO", "2.7.0",
                "Page-level SEO metadata ({{#seo}} / <seo> tags, meta / OpenGraph / Twitter / Schema.org / "
                        + "Citation / Dublin Core, title rewriting), ported from MediaWiki WikiSEO 2.7.0");
    }

    @Override
    public void onStart(PluginContext context) {
        WikiSeoConfig config = WikiSeoConfig.from(context.config());
        List<SeoGenerator> generators = createGenerators(config, context);
        SeoValidator validator = SeoValidator.forGenerators(generators);

        // 消息包（i18n/messages*.properties）注册给 core：plugin.wiki-seo.* / setup.wiki-seo.* 都能查
        context.register(MessageBundle.class, new WikiSeoMessages());
        context.register(HtmlHeadProvider.class,
                new SeoHeadProvider(config, validator, generators, wikiPageStore));
        context.register(ParserFunction.class, new SeoParserFunction(validator));
        context.register(ParserTag.class, new SeoTag(validator));
        context.register(SetupStep.class, new WikiSeoSetup(config, messageSource));

        context.logger().log(System.Logger.Level.INFO, "已启用 WikiSEO：生成器 {0}{1}",
                new Object[]{generatorNames(generators), config.enableAutoDescription() ? "，自动描述已开启" : ""});
    }

    /** 按配置构造生成器（MetaTag 总是启用，未知名字忽略并记日志）。 */
    private static List<SeoGenerator> createGenerators(WikiSeoConfig config, PluginContext context) {
        List<SeoGenerator> generators = new ArrayList<>();
        generators.add(new MetaTagGenerator());

        Set<String> seen = new LinkedHashSet<>();
        for (String name : config.metadataGenerators()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("metatag") || !seen.add(normalized)) {
                continue;
            }
            SeoGenerator generator = switch (normalized) {
                case "opengraph" -> new OpenGraphGenerator();
                case "twitter" -> new TwitterGenerator();
                case "schemaorg" -> new SchemaOrgGenerator();
                case "citation" -> new CitationGenerator();
                case "dublincore" -> new DublinCoreGenerator();
                default -> null;
            };
            if (generator == null) {
                context.logger().log(System.Logger.Level.WARNING, "忽略未知的元数据生成器：{0}", name);
            } else {
                generators.add(generator);
            }
        }
        return List.copyOf(generators);
    }

    private static String generatorNames(List<SeoGenerator> generators) {
        return String.join(", ", generators.stream().map(SeoGenerator::name).toList());
    }
}
