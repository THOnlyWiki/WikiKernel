package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.PageExistenceService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link PageExistenceService} 实现：查 {@code wiki_page}（已删除 / 已隐藏不算存在）。
 */
@Service
public class PageExistenceServiceImpl implements PageExistenceService {

    /** 特殊页命名空间前缀：没有页面行，但始终「存在」（否则整站特殊页链接全变红链）。 */
    private static final List<String> SPECIAL_PREFIXES = List.of("特殊:", "Special:");

    private final WikiPageService wikiPageService;

    public PageExistenceServiceImpl(WikiPageService wikiPageService) {
        this.wikiPageService = wikiPageService;
    }

    @Override
    public boolean exists(String title) {
        String normalized = Strings.trimToEmpty(title);
        if (normalized.isEmpty()) {
            return true; // 空目标不标红（[[|文字]] 这类异常写法）
        }
        for (String prefix : SPECIAL_PREFIXES) {
            if (normalized.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return wikiPageService.getVisibleByTitle(normalized).isPresent();
    }
}
