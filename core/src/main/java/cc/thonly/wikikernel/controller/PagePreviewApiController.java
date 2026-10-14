package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.service.PagePreviewService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 页面预览 API（{@code @RestController}）：{@code GET /api/page-preview?title=标题}，
 * 供 {@code /js/popups.js}（悬停预览，仿 MediaWiki 的 Popups 扩展）使用。
 * <p>
 * 只读接口：页面不存在 / 不可读 / 特殊页返回 404（{@link AjaxCode#NOT_FOUND}）；
 * 前端脚本在功能开关（{@code features.popups}）打开时才会加载，接口本身始终保持可用。
 */
@RestController
@RequestMapping("/api/page-preview")
public class PagePreviewApiController {

    private final PagePreviewService pagePreviewService;
    private final MessageSource messageSource;

    public PagePreviewApiController(PagePreviewService pagePreviewService, MessageSource messageSource) {
        this.pagePreviewService = pagePreviewService;
        this.messageSource = messageSource;
    }

    @GetMapping
    public AjaxResult<Map<String, Object>> preview(@RequestParam("title") String title,
                                                   HttpServletRequest request) {
        WikiUser viewer = UserSession.current(request);
        return pagePreviewService.preview(title, viewer)
                .map(preview -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("title", preview.title());
                    data.put("url", preview.url());
                    data.put("extract", preview.extract());
                    data.put("thumbnail", preview.thumbnailUrl());
                    data.put("thumbnailAlt", preview.thumbnailAlt());
                    return AjaxResult.success(message("preview.ok"), data);
                })
                .orElseGet(() -> AjaxResult.error(AjaxCode.NOT_FOUND,
                        message("preview.unavailable")));
    }

    /** 本地化文案（只用键，不写中文兜底：缺键时消息源会显示键本身）。 */
    private String message(String key) {
        return messageSource.getMessage(key, null, null, LocaleContextHolder.getLocale());
    }
}
