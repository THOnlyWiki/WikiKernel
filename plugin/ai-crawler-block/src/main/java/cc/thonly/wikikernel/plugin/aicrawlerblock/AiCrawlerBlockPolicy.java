package cc.thonly.wikikernel.plugin.aicrawlerblock;

import cc.thonly.wikikernel.common.extension.CrawlerPolicy;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;

/**
 * AI 爬虫策略：先识别并拦截 AI 抓取器，再放行搜索引擎；自定义 UA 关键字叠加在最后。
 * <p>
 * 识别顺序很重要：{@code Applebot-Extended} / {@code Google-Extended} 同时含有搜索引擎
 * 名字片段，所以必须先匹配 AI 名单，不能让搜索引擎白名单把它们放过去。
 */
final class AiCrawlerBlockPolicy implements CrawlerPolicy {

    static final String ENABLED_KEY = "ai-crawler-block.enabled";
    static final String EXTRA_KEY = "ai-crawler-block.extra-user-agents";

    /**
     * 明确拦截的 AI 训练 / 抓取 / 检索 UA（小写片段）。
     * 这不是穷举名单；管理员可以用额外 UA 关键字补充。
     */
    private static final List<String> AI_CRAWLERS = List.of(
            "gptbot", "chatgpt-user", "oai-searchbot", "openai",
            "claudebot", "anthropic-ai", "claude-web", "claude-searchbot",
            "perplexitybot", "google-extended", "applebot-extended",
            "ccbot", "bytespider", "amazonbot", "meta-externalagent",
            "cohere-ai", "diffbot", "imagesiftbot", "youbot", "timpibot",
            "omgili", "omgilibot", "ai2bot", "ai2bot-dolma", "scrapy",
            "facebookbot", "friendlycrawler", "img2dataset", "dataforseobot",
            "semrushbot-ocob", "ahrefsbot");

    /** 搜索引擎白名单（小写片段）；只用于放行，不会覆盖前面的 AI 名单。 */
    private static final List<String> SEARCH_ENGINES = List.of(
            "googlebot", "bingbot", "duckduckbot", "baiduspider", "yandexbot",
            "sogou", "360spider", "yahoo", "applebot", "petalbot", "qwantify",
            "seznambot", "naverbot", "ecosia", "bravebot");

    private final SiteConfig siteConfig;
    private final MessageSource messages;

    AiCrawlerBlockPolicy(SiteConfig siteConfig, MessageSource messages) {
        this.siteConfig = siteConfig;
        this.messages = messages;
    }

    @Override
    public boolean blocks(String userAgent) {
        if (!enabled() || userAgent == null || userAgent.isBlank()) {
            return false;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        // AI 先判断：Applebot-Extended / Google-Extended 不能被搜索引擎白名单放行。
        for (String signature : AI_CRAWLERS) {
            if (ua.contains(signature)) {
                return true;
            }
        }
        for (String signature : SEARCH_ENGINES) {
            if (ua.contains(signature)) {
                return false;
            }
        }
        for (String signature : extraSignatures()) {
            if (!signature.isBlank() && ua.contains(signature)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String robotsTxt() {
        if (!enabled()) {
            return "";
        }
        StringBuilder robots = new StringBuilder();
        robots.append("# AI crawler blocking (ai-crawler-block)\n");
        for (String signature : AI_CRAWLERS) {
            // Core 的 AI 名单是 UA 片段；在 robots 里给常见正式 UA 名加规则即可。
            String name = canonicalName(signature);
            if (name != null) {
                robots.append("User-agent: ").append(name).append('\n')
                        .append("Disallow: /\n\n");
            }
        }
        for (String signature : extraSignatures()) {
            if (!signature.isBlank()) {
                robots.append("User-agent: ").append(signature).append('\n')
                        .append("Disallow: /\n\n");
            }
        }
        return robots.toString().strip();
    }

    private boolean enabled() {
        return siteConfig == null || siteConfig.getBoolean(ENABLED_KEY, true);
    }

    private List<String> extraSignatures() {
        if (siteConfig == null) {
            return List.of();
        }
        String raw = siteConfig.get(EXTRA_KEY, "");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split("[\\r\\n,]+")).stream()
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** 把常见 UA 片段映射成 robots 里可读的产品名；未知片段返回 null（仍会由 HTTP 策略拦截）。 */
    private static String canonicalName(String signature) {
        return switch (signature) {
            case "gptbot" -> "GPTBot";
            case "chatgpt-user" -> "ChatGPT-User";
            case "oai-searchbot" -> "OAI-SearchBot";
            case "claudebot" -> "ClaudeBot";
            case "anthropic-ai" -> "anthropic-ai";
            case "claude-web" -> "Claude-Web";
            case "perplexitybot" -> "PerplexityBot";
            case "google-extended" -> "Google-Extended";
            case "applebot-extended" -> "Applebot-Extended";
            case "ccbot" -> "CCBot";
            case "bytespider" -> "Bytespider";
            case "amazonbot" -> "Amazonbot";
            case "meta-externalagent" -> "meta-externalagent";
            case "cohere-ai" -> "cohere-ai";
            case "diffbot" -> "Diffbot";
            case "imagesiftbot" -> "ImagesiftBot";
            case "youbot" -> "YouBot";
            case "timpibot" -> "Timpibot";
            case "omgili" -> "omgili";
            case "omgilibot" -> "omgilibot";
            case "ai2bot" -> "AI2Bot";
            case "ai2bot-dolma" -> "Ai2Bot-Dolma";
            case "scrapy" -> "Scrapy";
            case "facebookbot" -> "FacebookBot";
            case "friendlycrawler" -> "FriendlyCrawler";
            case "img2dataset" -> "img2dataset";
            case "dataforseobot" -> "DataForSeoBot";
            case "semrushbot-ocob" -> "SemrushBot-OCOB";
            case "ahrefsbot" -> "AhrefsBot";
            default -> null;
        };
    }
}
