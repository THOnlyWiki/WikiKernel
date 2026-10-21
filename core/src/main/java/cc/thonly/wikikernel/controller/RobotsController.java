package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.extension.CrawlerPolicy;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.impl.SiteSettingsServiceImpl;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 动态 {@code /robots.txt}：站点设置 {@code site.robots} 优先；留空使用内置默认规则。
 * <p>
 * 插件（例如 ai-crawler-block）可以通过 {@link CrawlerPolicy#robotsTxt()} 追加 AI 爬虫规则；
 * core 只负责合并文本，不认识具体厂商。响应不缓存，后台改完立即生效。
 */
@Controller
public class RobotsController {

    private static final MediaType TEXT_PLAIN_UTF8 =
            MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final SiteSettingsService siteSettingsService;
    private final PluginManager pluginManager;

    public RobotsController(SiteSettingsService siteSettingsService, PluginManager pluginManager) {
        this.siteSettingsService = siteSettingsService;
        this.pluginManager = pluginManager;
    }

    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots() {
        String configured = SiteSettingsServiceImpl.normalizeRobots(
                siteSettingsService.get(SiteSettingsService.ROBOTS_KEY, ""));
        StringBuilder body = new StringBuilder(configured.isEmpty()
                ? SiteSettingsService.DEFAULT_ROBOTS : configured);
        for (CrawlerPolicy policy : pluginManager.extensions(CrawlerPolicy.class)) {
            String extra;
            try {
                extra = policy.robotsTxt();
            } catch (RuntimeException exception) {
                // 单个插件的 robots 规则失败不能把 /robots.txt 变成 500。
                continue;
            }
            if (extra == null || extra.isBlank()) {
                continue;
            }
            if (body.length() > 0 && body.charAt(body.length() - 1) != '\n') {
                body.append('\n');
            }
            body.append('\n').append(extra.strip()).append('\n');
        }
        return ResponseEntity.ok()
                .contentType(TEXT_PLAIN_UTF8)
                .cacheControl(CacheControl.noCache())
                .body(body.toString());
    }
}
