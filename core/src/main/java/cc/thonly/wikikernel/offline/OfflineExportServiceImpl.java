package cc.thonly.wikikernel.offline;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.config.FileStorageProperties;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.ThumbnailService;
import cc.thonly.wikikernel.web.FileTitles;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 离线导出实现。
 *
 * <p>页面不是重新拼接模板，而是通过本机回环地址请求当前应用生成的真实页面，
 * 因而皮肤、插件 head、Wiki.css / Wiki.js、特殊页和响应式布局与线上完全一致。
 * 之后用 Jsoup 把站内链接、CSS/JS/图片改写为离线相对路径，最后打包成 ZIP。</p>
 */
@Service
public class OfflineExportServiceImpl implements OfflineExportService {

    private static final Logger log = LoggerFactory.getLogger(OfflineExportServiceImpl.class);
    private static final String USER_AGENT = "WikiKernel-OfflineExport/1.0";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);

    private final WikiPageMapper wikiPageMapper;
    private final AccessControlService accessControlService;
    private final FileService fileService;
    private final ThumbnailService thumbnailService;
    private final Path archiveRoot;
    private final HttpClient http;
    private final ExecutorService executor;
    private final Map<String, OfflineExportJob> jobs = new ConcurrentHashMap<>();

    public OfflineExportServiceImpl(WikiPageMapper wikiPageMapper,
                                    AccessControlService accessControlService,
                                    FileService fileService,
                                    ThumbnailService thumbnailService,
                                    FileStorageProperties storageProperties) {
        this.wikiPageMapper = wikiPageMapper;
        this.accessControlService = accessControlService;
        this.fileService = fileService;
        this.thumbnailService = thumbnailService;
        Path runPath = Path.of(storageProperties.getRunPath()).toAbsolutePath().normalize();
        Path parent = runPath.getParent();
        this.archiveRoot = (parent == null ? runPath : parent).resolve("offline-exports");
        try {
            Files.createDirectories(archiveRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("创建离线导出目录失败", exception);
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "offline-export");
            thread.setDaemon(true);
            return thread;
        });
        cleanupOldArchives();
    }

    @Override
    public OfflineExportJob start(String baseUrl, String skinId, WikiUser user, int limit, String cookie) {
        return start(baseUrl, skinId, user, limit, cookie, List.of());
    }

    @Override
    public OfflineExportJob start(String baseUrl, String skinId, WikiUser user, int limit,
                                  String cookie, List<String> selectedTitles) {
        cleanupOldArchives();
        String id = randomId();
        OfflineExportJob job = new OfflineExportJob(id, user == null || user.getId() == null ? -1 : user.getId());
        jobs.put(id, job);
        List<String> selected = selectedTitles == null ? List.of() : selectedTitles.stream()
                .filter(title -> title != null && !title.isBlank())
                .map(String::trim)
                .toList();
        executor.submit(() -> run(job, stripTrailingSlash(baseUrl), skinId, user, limit, cookie, selected));
        return job;
    }

    @Override
    public Optional<OfflineExportJob> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(jobs.get(id));
    }

    @Override
    public Optional<Path> archive(String id) {
        OfflineExportJob job = jobs.get(id);
        if (job == null || job.state() != OfflineExportJob.State.DONE || job.archive() == null) {
            return Optional.empty();
        }
        return Optional.of(job.archive());
    }

    @Override
    public int countExportablePages(WikiUser user) {
        return collectTitles(user).size();
    }

    private void run(OfflineExportJob job, String baseUrl, String skinId, WikiUser user,
                     int limit, String cookie, List<String> selectedTitles) {
        Path workDir = archiveRoot.resolve("work-" + job.id());
        Path siteRoot = workDir.resolve("site");
        Path archive = archiveRoot.resolve(job.id() + ".zip");
        try {
            job.state(OfflineExportJob.State.RUNNING);
            job.message("正在收集页面");
            deleteTree(workDir);
            Files.createDirectories(siteRoot.resolve("pages"));
            Files.createDirectories(siteRoot.resolve("assets"));

            String context = contextPath(baseUrl);
            boolean selectedMode = selectedTitles != null && !selectedTitles.isEmpty();
            List<String> seedTitles = selectedMode ? selectedTitles : collectTitles(user);

            // 第一遍：从数据库页面 + 文件页出发，跟着站内链接发现插件生成页、分类页、
            // 动态地区页等不在 wiki_page 表里的页面。原始 HTML 缓存起来供第二遍复用。
            job.message(selectedMode ? "正在渲染指定页面" : "正在发现页面");
            Set<String> seen = new LinkedHashSet<>(seedTitles);
            Deque<String> frontier = new ArrayDeque<>(seedTitles);
            Map<String, Fetched> fetchedPages = new LinkedHashMap<>();
            while (!frontier.isEmpty()) {
                if (fetchedPages.size() >= 5000) {
                    log.warn("离线导出页面数达到 5000 上限，停止继续发现");
                    break;
                }
                String title = frontier.removeFirst();
                if (title == null || title.isBlank() || fetchedPages.containsKey(title)) {
                    continue;
                }
                Fetched fetched = fetch(pageUrl(baseUrl, title, skinId), cookie);
                if (fetched == null) {
                    log.warn("离线导出跳过无法渲染的页面: {}", title);
                    continue;
                }
                fetchedPages.put(title, fetched);
                job.totalPages(fetchedPages.size() + frontier.size());
                if (fetchedPages.size() % 10 == 0) {
                    job.message("正在发现页面：" + fetchedPages.size());
                }
                if (!selectedMode) {
                    Document discovered = Jsoup.parse(fetched.body(), fetched.url());
                    for (String linked : discoverLinkedTitles(discovered, URI.create(fetched.url()), context)) {
                        if (seen.add(linked)) {
                            frontier.addLast(linked);
                        }
                    }
                }
            }

            List<String> titles = new ArrayList<>(fetchedPages.keySet());
            if (limit > 0 && titles.size() > limit) {
                titles = new ArrayList<>(titles.subList(0, limit));
            }
            if (titles.isEmpty()) {
                job.totalPages(0);
                job.finish(OfflineExportJob.State.DONE, "没有可导出的页面");
                job.archive(writeEmptyArchive(siteRoot, archive));
                deleteTree(workDir);
                return;
            }
            job.totalPages(titles.size());
            job.pagesDone(0);

            OfflineSiteMap siteMap = new OfflineSiteMap(context);
            List<OfflinePage> pages = new ArrayList<>();
            for (String title : titles) {
                String routePath = context + WikiUrls.pagePath(title);
                String slug = sha256(title).substring(0, 24) + ".html";
                OfflinePage page = new OfflinePage(title, slug, routePath,
                        siteRoot.resolve("pages").resolve(slug));
                pages.add(page);
                siteMap.add(page);
            }
            OfflinePage home = siteMap.home() == null ? pages.get(0) : siteMap.home();
            siteMap.home(home);

            OfflineAssetStore assets = new OfflineAssetStore(
                    baseUrl, cookie, siteRoot, workDir, fileService, thumbnailService);
            OfflineHtmlRewriter rewriter = new OfflineHtmlRewriter(siteMap, assets);
            List<SearchEntry> searchEntries = new ArrayList<>();

            for (OfflinePage page : pages) {
                job.message("正在渲染：" + page.title());
                Fetched fetched = fetchedPages.get(page.title());
                if (fetched == null) {
                    job.pagesDone(job.pagesDone() + 1);
                    continue;
                }
                Document document = Jsoup.parse(fetched.body(), fetched.url());
                String text = rewriter.searchableText(document, 700);
                rewriter.rewrite(document, fetched.url(), page, false);
                Files.writeString(page.file(), document.outerHtml(), StandardCharsets.UTF_8);
                searchEntries.add(new SearchEntry(page.title(), "pages/" + page.slug(), text));
                job.pagesDone(job.pagesDone() + 1);
                job.assets(assets.assetCount());
            }

            job.message("正在生成离线搜索");
            Files.writeString(siteRoot.resolve("assets").resolve("search-index.js"),
                    searchIndex(searchEntries), StandardCharsets.UTF_8);

            job.message("正在生成搜索页和入口");
            writeSearchPage(baseUrl, skinId, cookie, siteRoot, siteMap, assets, rewriter);
            writeIndex(siteRoot, home);
            writeOfflineFallback(siteRoot, home);
            writeOfflineAssets(siteRoot);
            writeManifest(siteRoot, home, searchEntries, assets.assetCount());
            writeReadme(siteRoot);

            job.message("正在打包 ZIP");
            zip(siteRoot, archive);
            job.archive(archive);
            job.finish(OfflineExportJob.State.DONE,
                    "导出完成：页面 " + searchEntries.size() + "，资源 " + assets.assetCount());
            deleteTree(workDir);
            log.info("离线导出完成: {} 页面={} 资源={} -> {}", job.id(),
                    searchEntries.size(), assets.assetCount(), archive);
        } catch (Throwable throwable) {
            log.error("离线导出失败: {}", job.id(), throwable);
            job.finish(OfflineExportJob.State.FAILED,
                    "导出失败：" + (throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage()));
            try {
                deleteTree(workDir);
            } catch (Exception ignored) {
                // 清理失败不影响任务状态
            }
        }
    }

    private List<String> collectTitles(WikiUser user) {
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getHided, false)
                .eq(WikiPage::getDeleted, false)
                .orderByAsc(WikiPage::getTitle));
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        List<WikiPage> readablePages = accessControlService.filterReadable(pages, WikiPage::getTitle, user);
        for (WikiPage page : readablePages) {
            if (page.getTitle() != null && !page.getTitle().isBlank()) {
                titles.add(page.getTitle());
            }
        }
        List<WikiFile> files = fileService.all();
        for (WikiFile file : files) {
            String title = FileTitles.titleOf(file.getName());
            if (accessControlService.canRead(title, user)) {
                titles.add(title);
            }
        }
        log.info("离线导出页面收集: 数据库页面={}, 文件={}, 去重后页面={}",
                pages.size(), files.size(), titles.size());
        return new ArrayList<>(titles);
    }

    /**
     * 从一张已渲染页面的站内链接中发现页面标题。只接受看起来像 wiki 页面的路径，
     * 排除静态资源、API、特殊页和外部站点；返回的标题之后会再经过回环请求校验。
     */
    private Set<String> discoverLinkedTitles(Document document, URI pageUri, String context) {
        Set<String> titles = new LinkedHashSet<>();
        for (Element link : document.select("a[href], area[href]")) {
            String raw = link.attr("href");
            if (raw == null || raw.isBlank() || raw.startsWith("#")
                    || raw.startsWith("mailto:") || raw.startsWith("javascript:")) {
                continue;
            }
            try {
                URI resolved = pageUri.resolve(raw.trim());
                String scheme = resolved.getScheme();
                if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                    continue;
                }
                String host = resolved.getHost();
                String pageHost = pageUri.getHost();
                if (host != null && pageHost != null && !host.equalsIgnoreCase(pageHost)
                        && !"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
                    continue;
                }
                String path = stripContextPath(resolved.getPath() == null ? "" : resolved.getPath(), context);
                if (path.isBlank() || "/".equals(path) || isAssetPath(path) || isSpecialPath(path)
                        || path.startsWith("/api/") || path.endsWith(".php")) {
                    continue;
                }
                String title = WikiUrls.pagePathRest(path);
                if (path.startsWith(WikiUrls.DEFAULT_PAGE_PREFIX)
                        && (title == null || title.startsWith("wiki/"))) {
                    // 裸根部署下，旧链接 /wiki/标题 也要能发现页面。
                    title = path.substring(WikiUrls.DEFAULT_PAGE_PREFIX.length());
                }
                if (title == null || title.isBlank()) {
                    continue;
                }
                title = URLDecoder.decode(title, StandardCharsets.UTF_8);
                if (!title.isBlank() && !title.contains("#")) {
                    titles.add(title);
                }
            } catch (IllegalArgumentException ignored) {
                // 非法链接不加入发现队列
            }
        }
        return titles;
    }

    private static String stripContextPath(String path, String context) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            String stripped = path.substring(context.length());
            return stripped.isEmpty() ? "/" : stripped;
        }
        return path;
    }

    private static boolean isSpecialPath(String path) {
        return path.startsWith("/特殊:") || path.startsWith("/Special:")
                || path.startsWith("/wiki/特殊:") || path.startsWith("/wiki/Special:");
    }

    private static boolean isAssetPath(String path) {
        return path.startsWith("/files/") || path.startsWith("/skins/")
                || path.startsWith("/plugins/") || path.startsWith("/vendor/")
                || path.startsWith("/css/") || path.startsWith("/js/")
                || path.startsWith("/img/") || path.equals("/favicon.ico")
                || path.startsWith("/load.php") || path.startsWith("/thumb.php")
                || path.startsWith("/auth/");
    }

    private String pageUrl(String baseUrl, String title, String skinId) {
        String path = WikiUrls.pagePath(title);
        String separator = path.contains("?") ? "&" : "?";
        String url = baseUrl + path + separator + "useskin="
                + URLEncoder.encode(skinId == null ? "" : skinId, StandardCharsets.UTF_8);
        return url;
    }

    private Fetched fetch(String url, String cookie) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .GET();
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            HttpRequest request = builder.build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            String type = response.headers().firstValue("content-type").orElse("");
            return new Fetched(new String(response.body(), StandardCharsets.UTF_8),
                    response.uri().toString(), type);
        } catch (Exception exception) {
            log.debug("离线导出请求失败: {}", url, exception);
            return null;
        }
    }

    private void writeSearchPage(String baseUrl, String skinId, String cookie, Path siteRoot,
                                 OfflineSiteMap siteMap, OfflineAssetStore assets,
                                 OfflineHtmlRewriter rewriter) throws IOException {
        String path = WikiUrls.pagePath("特殊:搜索");
        String url = baseUrl + path + (path.contains("?") ? "&" : "?")
                + "search=&useskin=" + URLEncoder.encode(skinId == null ? "" : skinId, StandardCharsets.UTF_8);
        Fetched fetched = fetch(url, cookie);
        if (fetched == null) {
            Files.writeString(siteRoot.resolve("search.html"),
                    "<!doctype html><meta charset=\"utf-8\"><title>搜索</title>"
                            + "<link rel=\"stylesheet\" href=\"assets/offline.css\">"
                            + "<main class=\"offline-search\"><h1>离线搜索</h1>"
                            + "<p>搜索页暂时无法渲染，请从首页开始浏览。</p></main>"
                            + "<script>window.OFFLINE_SEARCH_INDEX=[]</script>"
                            + "<script src=\"assets/offline.js\"></script>",
                    StandardCharsets.UTF_8);
            return;
        }
        Document document = Jsoup.parse(fetched.body(), fetched.url());
        rewriter.rewrite(document, fetched.url(), null, true);
        document.select(".searchresults, .mw-search-results").empty();
        Files.writeString(siteRoot.resolve("search.html"), document.outerHtml(), StandardCharsets.UTF_8);
    }

    private void writeIndex(Path siteRoot, OfflinePage home) throws IOException {
        String target = "pages/" + home.slug();
        String html = "<!doctype html><html lang=\"zh\"><meta charset=\"utf-8\">"
                + "<meta http-equiv=\"refresh\" content=\"0;url=" + target + "\">"
                + "<title>离线站点</title>"
                + "<body><p><a href=\"" + target + "\">进入离线站点</a></p></body></html>";
        Files.writeString(siteRoot.resolve("index.html"), html, StandardCharsets.UTF_8);
    }

    private void writeOfflineFallback(Path siteRoot, OfflinePage home) throws IOException {
        String target = "pages/" + home.slug();
        String html = "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<title>离线站点</title><link rel=\"stylesheet\" href=\"assets/offline.css\"></head>"
                + "<body class=\"wiki-offline\"><main class=\"offline-message\">"
                + "<h1>该页面不在离线包中</h1>"
                + "<p>离线导出只包含导出时存在的页面和静态资源。</p>"
                + "<p><a href=\"" + target + "\">返回首页</a> · <a href=\"search.html\">离线搜索</a></p>"
                + "</main><script src=\"assets/offline.js\"></script></body></html>";
        Files.writeString(siteRoot.resolve("offline.html"), html, StandardCharsets.UTF_8);
    }

    private void writeOfflineAssets(Path siteRoot) throws IOException {
        Path assets = siteRoot.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("offline.css"), OFFLINE_CSS, StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("offline.js"), OFFLINE_JS, StandardCharsets.UTF_8);
    }

    private void writeManifest(Path siteRoot, OfflinePage home,
                               List<SearchEntry> entries, int assetCount) throws IOException {
        String json = "{\"format\":\"wikikernel-offline\",\"version\":1,"
                + "\"home\":\"pages/" + jsonEscape(home.slug()) + "\","
                + "\"pages\":" + entries.size() + ",\"assets\":" + assetCount + ","
                + "\"generated\":\"" + Instant.now() + "\"}\n";
        Files.writeString(siteRoot.resolve("manifest.json"), json, StandardCharsets.UTF_8);
    }

    private void writeReadme(Path siteRoot) throws IOException {
        Files.writeString(siteRoot.resolve("README.txt"),
                "WikiKernel 离线导出\n"
                        + "================\n"
                        + "双击 index.html 或 search.html 打开。\n"
                        + "这是导出时生成的只读快照：编辑、登录、评论提交等需要服务器的操作不可用。\n"
                        + "页面内链接已改为相对路径；上传图片已转为半尺寸 WebP。\n",
                StandardCharsets.UTF_8);
    }

    private Path writeEmptyArchive(Path siteRoot, Path archive) throws IOException {
        writeOfflineAssets(siteRoot);
        Files.writeString(siteRoot.resolve("index.html"),
                "<!doctype html><meta charset=\"utf-8\"><title>空离线站点</title>"
                        + "<p>没有可导出的页面。</p>", StandardCharsets.UTF_8);
        zip(siteRoot, archive);
        return archive;
    }

    private String searchIndex(List<SearchEntry> entries) {
        StringBuilder out = new StringBuilder("window.OFFLINE_SEARCH_INDEX=[");
        for (int i = 0; i < entries.size(); i++) {
            SearchEntry entry = entries.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"t\":\"").append(jsonEscape(entry.title()))
                    .append("\",\"u\":\"").append(jsonEscape(entry.url()))
                    .append("\",\"x\":\"").append(jsonEscape(entry.text())).append("\"}");
        }
        return out.append("];\n").toString();
    }

    private void zip(Path siteRoot, Path archive) throws IOException {
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(archive));
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            try (Stream<Path> walk = Files.walk(siteRoot)) {
                for (Path path : walk.sorted(Comparator.naturalOrder()).toList()) {
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    String name = siteRoot.relativize(path).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private void cleanupOldArchives() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        try (Stream<Path> files = Files.list(archiveRoot)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".zip")) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ignored) {
                    // 另一个任务可能正在使用
                }
            }
        } catch (IOException exception) {
            log.debug("清理旧离线导出失败", exception);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 尽力清理
                }
            });
        } catch (IOException ignored) {
            // 尽力清理
        }
    }

    private static String contextPath(String baseUrl) {
        try {
            String path = URI.create(baseUrl).getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return "";
            }
            return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        } catch (Exception exception) {
            return "";
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String randomId() {
        return HexFormat.of().formatHex(randomBytes(8));
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private record SearchEntry(String title, String url, String text) {
    }

    private record Fetched(String body, String url, String contentType) {
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final String OFFLINE_CSS = """
            .wiki-offline .mw-editsection,
            .wiki-offline #ca-edit,
            .wiki-offline #ca-history,
            .wiki-offline .mw-cite-backlink,
            .wiki-offline .comment-form,
            .wiki-offline .comment-reply,
            .wiki-offline .ve-ui-toolbar,
            .wiki-offline .wiki-editor-toolbar {
                display: none !important;
            }
            .wiki-offline .offline-notice {
                margin: 0 0 1rem;
                padding: 8px 12px;
                border: 1px solid #c8ccd1;
                border-radius: 4px;
                background: #f8f9fa;
                color: #54595d;
                font-size: 0.875rem;
            }
            .offline-search-results { margin-top: 1.25rem; }
            .offline-search-results ul { margin: 0; padding: 0; list-style: none; }
            .offline-search-result {
                padding: 0.75rem 0;
                border-bottom: 1px solid #e5e5e5;
            }
            .offline-search-result a { font-weight: 700; }
            .offline-search-result p { margin: 0.25rem 0 0; color: #54595d; }
            .offline-search-empty { padding: 1rem 0; color: #72777d; }
            .offline-message { max-width: 42rem; margin: 4rem auto; padding: 1.5rem; }
            .offline-message h1 { margin-top: 0; }
            .offline-disabled { opacity: 0.55; cursor: not-allowed; }
            """;

    private static final String OFFLINE_JS = """
            (function () {
                'use strict';
                document.body.classList.add('wiki-offline');
                var nativeFetch = window.fetch;
                if (nativeFetch) {
                    window.fetch = function (input, init) {
                        var url = typeof input === 'string' ? input : (input && input.url) || '';
                        if (url.indexOf('/api/') >= 0 || url.indexOf('/api.php') >= 0 || url.indexOf('/auth/') >= 0) {
                            return Promise.reject(new Error('离线快照不支持服务器操作'));
                        }
                        return nativeFetch.apply(this, arguments);
                    };
                }
                var index = window.OFFLINE_SEARCH_INDEX || [];
                function text(value) {
                    return String(value == null ? '' : value).toLowerCase();
                }
                function escapeHtml(value) {
                    return String(value == null ? '' : value)
                        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
                        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
                }
                function renderSearch(query) {
                    var target = document.querySelector('.searchresults');
                    if (!target) {
                        target = document.createElement('div');
                        target.className = 'searchresults';
                        var form = document.querySelector('#search, .mw-search-form');
                        if (form && form.parentNode) {
                            form.parentNode.insertBefore(target, form.nextSibling);
                        } else {
                            document.body.appendChild(target);
                        }
                    }
                    var q = text(query).trim();
                    var results = q ? index.filter(function (item) {
                        return text(item.t).indexOf(q) >= 0 || text(item.x).indexOf(q) >= 0;
                    }) : index;
                    if (!q) {
                        target.innerHTML = '<p class="offline-search-empty">输入关键词搜索离线页面。</p>';
                        return;
                    }
                    if (!results.length) {
                        target.innerHTML = '<p class="offline-search-empty">没有找到匹配的页面。</p>';
                        return;
                    }
                    var html = '<div class="offline-search-results"><ul>';
                    results.slice(0, 200).forEach(function (item) {
                        html += '<li class="offline-search-result"><a href="' + item.u + '">'
                            + escapeHtml(item.t) + '</a><p>' + escapeHtml(item.x || '')
                            + '</p></li>';
                    });
                    target.innerHTML = html + '</ul></div>';
                }
                function setupSearch() {
                    var form = document.querySelector('form#search, form.mw-search-form');
                    if (!form) {
                        return;
                    }
                    var input = form.querySelector('input[name="search"], input[name="q"]');
                    var params = new URLSearchParams(window.location.search);
                    var initial = params.get('search') || params.get('q') || '';
                    form.addEventListener('submit', function (event) {
                        event.preventDefault();
                        var value = input ? input.value : '';
                        var url = new URL(window.location.href);
                        url.search = '';
                        if (value) { url.searchParams.set('search', value); }
                        window.history.replaceState(null, '', url.toString());
                        renderSearch(value);
                    });
                    if (initial) {
                        if (input) { input.value = initial; }
                        renderSearch(initial);
                    } else {
                        renderSearch('');
                    }
                }
                document.addEventListener('submit', function (event) {
                    var form = event.target;
                    if (form && form.getAttribute('data-offline-disabled') === '1') {
                        event.preventDefault();
                        window.alert('离线快照不支持编辑、登录或提交表单。');
                    }
                }, true);
                document.addEventListener('click', function (event) {
                    var link = event.target && event.target.closest
                        ? event.target.closest('a[data-offline-disabled="1"]') : null;
                    if (link) {
                        event.preventDefault();
                        window.alert('离线快照不支持该操作。');
                    }
                }, true);
                setupSearch();
            })();
            """;
}
