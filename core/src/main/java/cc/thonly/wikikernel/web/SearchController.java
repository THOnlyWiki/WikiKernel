package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.extension.SearchHit;
import cc.thonly.wikikernel.search.SearchService;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 聚合搜索 API：core 自带搜索 + 插件 SearchProvider；结果按访问规则过滤。 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;
    private final AccessControlService accessControlService;

    public SearchController(SearchService searchService, AccessControlService accessControlService) {
        this.searchService = searchService;
        this.accessControlService = accessControlService;
    }

    @GetMapping
    public List<SearchHit> search(@RequestParam("q") String keyword,
                                  @RequestParam(name = "limit", defaultValue = "10") int limit,
                                  HttpServletRequest request) {
        // 限制结果数：避免 limit=2147483647 触发超大 LIKE 扫描 / 分配。
        int size = limit <= 0 ? 10 : Math.min(limit, 50);
        List<SearchHit> hits = searchService.search(keyword, size);
        return accessControlService.filterReadable(hits, SearchHit::title, UserSession.current(request));
    }
}
