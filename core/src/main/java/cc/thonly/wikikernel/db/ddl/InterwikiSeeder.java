package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.service.InterwikiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 跨 Wiki 前缀种子（仿 MediaWiki 自带的 interwiki map）：只在**整表为空**时写入，
 * 之后在 {@code 特殊:跨Wiki} 里增删改都不会被启动流程覆盖。
 * <p>
 * 语言前缀（{@code en} / {@code zh} / …）默认指向对应语言的维基百科，
 * 让「语言链接」开箱可用；自建多语言 wiki 时请在 {@code 特殊:跨Wiki} 里改成自己的地址。
 * <p>
 * 通过 {@link InterwikiService#save} 写入（而不是直接操作 mapper）：服务内部有前缀缓存，
 * 启动更早的种子（如 Gadgets 定义页渲染）可能已经把「空前缀表」缓存起来，走服务写入会让缓存失效重建。
 */
@Component
@Order(9)
public class InterwikiSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterwikiSeeder.class);

    /** 前缀 / URL 模板 / 语言码（空 = 普通跨 wiki）/ 本地 / 可嵌入。 */
    private record Seed(String prefix, String url, String language) {
    }

    private static final List<Seed> DEFAULTS = List.of(
            // 维基媒体家族的常用项目
            new Seed("wikipedia", "https://zh.wikipedia.org/wiki/$1", ""),
            new Seed("w", "https://zh.wikipedia.org/wiki/$1", ""),
            new Seed("wiktionary", "https://zh.wiktionary.org/wiki/$1", ""),
            new Seed("wikt", "https://zh.wiktionary.org/wiki/$1", ""),
            new Seed("wikibooks", "https://zh.wikibooks.org/wiki/$1", ""),
            new Seed("wikinews", "https://zh.wikinews.org/wiki/$1", ""),
            new Seed("wikiquote", "https://zh.wikiquote.org/wiki/$1", ""),
            new Seed("wikisource", "https://zh.wikisource.org/wiki/$1", ""),
            new Seed("wikiversity", "https://zh.wikiversity.org/wiki/$1", ""),
            new Seed("wikispecies", "https://species.wikimedia.org/wiki/$1", ""),
            new Seed("commons", "https://commons.wikimedia.org/wiki/$1", ""),
            new Seed("meta", "https://meta.wikimedia.org/wiki/$1", ""),
            new Seed("mw", "https://www.mediawiki.org/wiki/$1", ""),
            new Seed("wikidata", "https://www.wikidata.org/wiki/$1", ""),
            new Seed("d", "https://www.wikidata.org/wiki/$1", ""),
            // 语言链接（language 非空：正文里不显示，收集到侧栏「语言」）
            new Seed("zh", "https://zh.wikipedia.org/wiki/$1", "zh"),
            new Seed("en", "https://en.wikipedia.org/wiki/$1", "en"),
            new Seed("ja", "https://ja.wikipedia.org/wiki/$1", "ja"),
            new Seed("ko", "https://ko.wikipedia.org/wiki/$1", "ko"),
            new Seed("de", "https://de.wikipedia.org/wiki/$1", "de"),
            new Seed("fr", "https://fr.wikipedia.org/wiki/$1", "fr"),
            new Seed("es", "https://es.wikipedia.org/wiki/$1", "es"),
            new Seed("ru", "https://ru.wikipedia.org/wiki/$1", "ru"),
            new Seed("it", "https://it.wikipedia.org/wiki/$1", "it"),
            new Seed("pt", "https://pt.wikipedia.org/wiki/$1", "pt"),
            new Seed("ar", "https://ar.wikipedia.org/wiki/$1", "ar"),
            new Seed("vi", "https://vi.wikipedia.org/wiki/$1", "vi"));

    private final InterwikiService interwikiService;

    public InterwikiSeeder(InterwikiService interwikiService) {
        this.interwikiService = interwikiService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!interwikiService.all().isEmpty()) {
            return;
        }
        for (Seed seed : DEFAULTS) {
            interwikiService.save(null, new InterwikiService.Form(
                    seed.prefix(), seed.url(), seed.language(), false, false), null);
        }
        log.info("已写入 {} 条默认跨 Wiki 前缀（可在 特殊:跨Wiki 里修改）", DEFAULTS.size());
    }
}
