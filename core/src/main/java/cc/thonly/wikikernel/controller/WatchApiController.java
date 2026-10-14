package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.WatchService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.WikiTitles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监视列表 API（@RestController）：
 * {@code POST /api/watch}（title + action=watch|unwatch）、
 * {@code POST /api/watch/raw}（watchlist=每行一个标题，整体替换）。
 */
@RestController
@RequestMapping("/api/watch")
public class WatchApiController {

    private final WatchService watchService;
    private final MessageSource messageSource;

    public WatchApiController(WatchService watchService, MessageSource messageSource) {
        this.watchService = watchService;
        this.messageSource = messageSource;
    }

    /** 加入 / 移除监视。 */
    @PostMapping
    public AjaxResult<Map<String, Object>> toggle(
            @RequestParam("title") String title,
            @RequestParam(value = "action", required = false, defaultValue = "watch") String action,
            HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("watch.loginRequired"));
        }
        String value = WikiTitles.normalize(title);
        if (value.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("error.titleRequired"));
        }
        boolean watched;
        if ("unwatch".equalsIgnoreCase(action) || "0".equals(action)) {
            watchService.unwatch(user.getId(), value);
            watched = false;
        } else {
            watchService.watch(user.getId(), value);
            watched = true;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", value);
        data.put("watched", watched);
        return AjaxResult.success(message(watched ? "watch.watched" : "watch.unwatched"), data);
    }

    /** 「编辑监视列表」：整体保存（每行一个标题）。 */
    @PostMapping("/raw")
    public AjaxResult<Map<String, Object>> saveRaw(
            @RequestParam(value = "watchlist", required = false) String watchlist,
            HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("watch.loginRequired"));
        }
        List<String> titles = Arrays.stream(Strings.trimToEmpty(watchlist).split("\\r?\\n", -1))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        int count = watchService.replaceAll(user.getId(), titles);
        return AjaxResult.success(message("watch.saved", count), Map.of("count", count));
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
