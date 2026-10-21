package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.impl.SiteSettingsServiceImpl;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 浏览器默认请求的 {@code /favicon.ico}：跳转到站点设置里的图标（未配置时用内置 logo）。 */
@Controller
public class FaviconController {

    private final SiteSettingsService siteSettingsService;

    public FaviconController(SiteSettingsService siteSettingsService) {
        this.siteSettingsService = siteSettingsService;
    }

    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        String configured = SiteSettingsServiceImpl.normalizeFavicon(
                siteSettingsService.get(SiteSettingsService.FAVICON_KEY, ""));
        String target = configured.isEmpty() ? "/img/wiki-logo.svg" : configured;
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                .cacheControl(CacheControl.noCache())
                .build();
    }
}
