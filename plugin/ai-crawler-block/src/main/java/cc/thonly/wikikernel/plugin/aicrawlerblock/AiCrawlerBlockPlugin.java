package cc.thonly.wikikernel.plugin.aicrawlerblock;

import cc.thonly.wikikernel.common.extension.CrawlerPolicy;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

import java.util.List;

/**
 * 防 AI 爬虫扩展：默认拦截已知 AI 训练 / 检索爬虫的 User-Agent，
 * 同时明确放行 Googlebot、Bingbot 等搜索引擎。
 * <p>
 * 抓取拦截由 core 的 {@code CrawlerPolicyFilter} 执行；同时把 AI 爬虫规则追加到
 * {@code /robots.txt}。开关和自定义 UA 在「特殊:权限配置 → 站点设置」的插件分区里改，
 * 默认开启（enabled=true）。
 */
public class AiCrawlerBlockPlugin implements WikiPlugin {

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("ai-crawler-block", "AI Crawler Block", "1.0.0",
                "Rejects known AI crawler user agents while allowing search engines; configurable in site settings");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new AiCrawlerBlockMessages());
        context.register(SiteSettingsSection.class, new AiCrawlerBlockSettings(messageSource));
        context.register(CrawlerPolicy.class, new AiCrawlerBlockPolicy(siteConfig, messageSource));
        context.logger().log(System.Logger.Level.INFO,
                "已启用 AI 爬虫拦截：默认放行搜索引擎，拦截已知 AI 抓取 UA（可在站点设置关闭）");
    }

    /** 站点设置分区：开关 + 自定义 UA 黑名单。 */
    private static final class AiCrawlerBlockSettings implements SiteSettingsSection {
        private final MessageSource messages;

        private AiCrawlerBlockSettings(MessageSource messages) {
            this.messages = messages;
        }

        @Override
        public String id() {
            return "ai-crawler-block";
        }

        @Override
        public String title() {
            return AiCrawlerBlockSupport.text(messages, "aiCrawlerBlock.section.title", "防 AI 爬虫");
        }

        @Override
        public String description() {
            return AiCrawlerBlockSupport.text(messages, "aiCrawlerBlock.section.description",
                    "拦截 AI 训练 / 抓取爬虫；搜索引擎（Googlebot、Bingbot 等）始终放行。");
        }

        @Override
        public int order() {
            return 130;
        }

        @Override
        public List<SettingField> fields() {
            return List.of(
                    SettingField.checkbox(AiCrawlerBlockPolicy.ENABLED_KEY,
                            AiCrawlerBlockSupport.text(messages, "aiCrawlerBlock.enabled",
                                    "启用 AI 爬虫拦截"), true),
                    SettingField.textarea(AiCrawlerBlockPolicy.EXTRA_KEY,
                            AiCrawlerBlockSupport.text(messages, "aiCrawlerBlock.extra",
                                    "额外拦截的 User-Agent 关键字（每行一个）"), ""));
        }
    }
}
