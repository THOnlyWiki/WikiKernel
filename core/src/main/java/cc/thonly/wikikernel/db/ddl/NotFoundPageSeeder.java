package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.controller.WikiErrorController;
import cc.thonly.wikikernel.service.WikiPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 默认 404 页面：{@code WikiKernel:404} 不存在时在启动时创建（**已有数据库也会补上**，已存在的内容不覆盖）。
 * 404 响应会把该页面渲染后的内容插进错误页，见 {@link WikiErrorController}。
 * <p>
 * 页面内容支持占位符（渲染前替换，由错误页控制器处理）：
 * <ul>
 *     <li>{@code $TITLE$}：被访问的标题；</li>
 *     <li>{@code $SEARCH_URL$} / {@code $LOGS_URL$} / {@code $CREATE_URL$}：搜索本页标题 / 搜索相关日志 / 创建本页面的链接。</li>
 * </ul>
 * 想改 404 样式 / 文案：直接编辑这个页面即可；删掉它后重启会重新创建一份默认内容。
 */
@Component
@Order(6)
public class NotFoundPageSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NotFoundPageSeeder.class);

    /** 默认内容（wikitext + 白名单 HTML；{@code <ref>} 用 nowiki 包住，按文字展示而不是真的生成脚注）。 */
    private static final String CONTENT = """
            此页面目前没有内容。您可以在其他页面中<a href="$SEARCH_URL$">搜索本页标题</a>、<a href="$LOGS_URL$">搜索相关日志</a>或<a href="$CREATE_URL$">创建本页面</a>

            注意：如果您要从其他网站大段引用文字到本站，请在页面内注明内容出处！建议您使用<nowiki><ref>出处网址</ref></nowiki>来标记所引用的内容的出处。
            """;

    /** 旧版默认内容（三个短语还没有链接）：检测到就自动升级成新版。 */
    private static final String LEGACY_CONTENT = """
            此页面目前没有内容。您可以在其他页面中搜索本页标题、搜索相关日志或创建本页面。

            注意：如果您要从其他网站大段引用文字到本站，请在页面内注明内容出处！建议您使用<nowiki><ref>出处网址</ref></nowiki>来标记所引用的内容的出处。
            """;

    private final WikiPageService wikiPageService;

    public NotFoundPageSeeder(WikiPageService wikiPageService) {
        this.wikiPageService = wikiPageService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String title = WikiErrorController.NOT_FOUND_PAGE;
        Optional<WikiPage> existing = wikiPageService.getByTitle(title);
        if (existing.isEmpty()) {
            wikiPageService.editPage(null, title, CONTENT, "初始化默认 404 页面");
            log.info("已创建默认 404 页面 {}", title);
            return;
        }
        // 旧版默认内容自动升级为带链接的版本；用户自己改过的内容不动
        String current = wikiPageService.getLatestContent(existing.get().getId()).orElse("");
        if (current.strip().equals(LEGACY_CONTENT.strip())) {
            wikiPageService.editPage(null, title, CONTENT, "更新默认 404 页面");
            log.info("已把默认 404 页面升级为带链接的版本");
        }
    }
}
