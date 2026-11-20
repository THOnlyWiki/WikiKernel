package cc.thonly.wikikernel.plugin.news;

import cc.thonly.wikikernel.common.service.*;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/news")
public class NewsApi {
    private final NewsService news;
    private final NewsJobs jobs;
    private final CurrentUser current;
    private final ActivityDirectory activities;
    private final AccessCheck access;
    private final PageSearchStore pages;

    public NewsApi(NewsService news, NewsJobs jobs, CurrentUser current, ActivityDirectory activities,
                   AccessCheck access, PageSearchStore pages) {
        this.news = news; this.jobs = jobs; this.current = current; this.activities = activities; this.access = access; this.pages = pages;
    }

    @ModelAttribute
    public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException exception) { return Map.of("message", exception.getMessage()); }

    @GetMapping
    public Object list(@RequestParam(value = "homepage", defaultValue = "false") boolean homepage,
                        @RequestParam(value = "q", defaultValue = "") String q, @RequestParam(value = "category", defaultValue = "") String category,
                        @RequestParam(value = "region", defaultValue = "") String region, @RequestParam(value = "offset", defaultValue = "0") int offset,
                        @RequestParam(value = "limit", required = false) Integer limit) {
        return news.published(current.viewer(), homepage, q, category, region, offset, limit);
    }

    @GetMapping("/state")
    public Object state() {
        return Map.of("loggedIn", current.loggedIn(), "reviewer", news.reviewer(current.viewer()), "editor", news.editor(current.viewer()),
                "canGenerate", news.ai().allowed("news", current.viewer()),
                "categories", NewsService.CATEGORIES, "manageUrl", WikiUrls.pagePath("特殊:新闻管理"), "newsUrl", WikiUrls.pagePath("新闻"));
    }

    @GetMapping("/article/{id}")
    public Object article(@PathVariable("id") long id) {
        try { return news.article(current.viewer(), id); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }

    @GetMapping("/drafts")
    public Object drafts() { return news.drafts(current.viewer()); }
    @GetMapping("/draft/{id}")
    public Object draft(@PathVariable("id") long id) { return news.draft(current.viewer(), id); }
    public record Edit(long id, long version, NewsContent content) { }
    @PostMapping("/save")
    public Object save(@RequestBody Edit edit) {
        return news.draft(current.viewer(), news.save(current.viewer(), edit.id(), edit.version(), edit.content()));
    }
    @PostMapping("/action")
    public Object action(@RequestParam(value = "id") long id, @RequestParam(value = "version") long version, @RequestParam(value = "action") String action) {
        news.transition(current.viewer(), id, version, action); return news.draft(current.viewer(), id);
    }
    @GetMapping("/history/{id}")
    public Object history(@PathVariable("id") long id) { return news.history(current.viewer(), id).stream().map(DbRow::values).toList(); }
    @PostMapping("/generate")
    public Object generate(@RequestParam(value = "input") String input, @RequestParam(value = "sourceUrl") String sourceUrl) {
        return Map.of("jobId", jobs.enqueue(current.viewer(), input, sourceUrl));
    }
    @GetMapping("/job/{id}")
    public Object job(@PathVariable("id") long id) { return jobs.status(current.viewer(), id); }
    @PostMapping("/retry")
    public Object retry(@RequestParam(value = "id") long id) { jobs.retry(current.viewer(), id); return Map.of("queued", true); }

    @GetMapping("/activities")
    public Object activities(@RequestParam(value = "year") int year) {
        if (!news.enabled() || year < 2000 || year > 2100 || !access.canRead("线下活动速查表", current.viewer())) return List.of();
        return activities.activities(year);
    }
    @GetMapping("/regions")
    public Object regions() { return activities.regions(); }

    @GetMapping("/latest")
    public Object latest() {
        if (!news.enabled()) return List.of();
        return pages.query(new PageSearchStore.PageQuery("main", List.of(), List.of(), "", "", "", ".*(?:/Common\\.(?:css|js)|\\.(?:css|js))$",
                        "firstedit", true, 0, 30, "exclude")).stream()
                .filter(row -> access.canRead(row.title(), current.viewer()))
                .filter(row -> !Set.of("首页", "新闻", "快速创建页面", "快速创建子页面").contains(row.title()))
                .limit(6).map(row -> Map.of("title", row.title(), "url", WikiUrls.pagePath(row.title()))).toList();
    }
}
