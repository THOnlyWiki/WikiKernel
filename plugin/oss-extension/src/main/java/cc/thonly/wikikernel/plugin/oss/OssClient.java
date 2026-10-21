package cc.thonly.wikikernel.plugin.oss;

import cc.thonly.wikikernel.common.extension.FileChangeEvent;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

/** 和 oss-server 通信：写操作使用共享密钥，健康检查不需要密钥。 */
public final class OssClient {

    private final OssSettings settings;
    private final HttpClient http;
    private final long healthTtlMillis = 4_000L;

    private volatile long checkedAt;
    private volatile boolean available;

    public OssClient(OssSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /** 健康检查缓存，避免每个页面渲染都请求 OSS。 */
    public boolean available() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < healthTtlMillis) {
            return available;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (now - checkedAt < healthTtlMillis) {
                return available;
            }
            checkedAt = now;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(settings.operationUrl() + "/api/health"))
                        .timeout(Duration.ofMillis(1500))
                        .GET()
                        .build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                available = response.statusCode() >= 200 && response.statusCode() < 300;
            } catch (Exception exception) {
                available = false;
            }
            return available;
        }
    }

    public boolean upload(FileChangeEvent event) {
        if (event == null || event.path() == null || !Files.isRegularFile(event.path())) {
            return false;
        }
        try {
            String url = settings.operationUrl() + "/api/files/" + pathSegment(event.storedName())
                    + "?name=" + URLEncoder.encode(event.fileName(), StandardCharsets.UTF_8)
                    + "&contentType=" + URLEncoder.encode(event.contentType(), StandardCharsets.UTF_8)
                    + "&fileId=" + event.fileId()
                    + "&version=" + event.version()
                    + "&current=" + event.current();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("X-OSS-Key", settings.key())
                    .header("Content-Type", event.contentType() == null
                            ? "application/octet-stream" : event.contentType())
                    .PUT(HttpRequest.BodyPublishers.ofFile(event.path()))
                    .build();
            return success(http.send(request, HttpResponse.BodyHandlers.discarding()));
        } catch (Exception exception) {
            return false;
        }
    }

    public boolean delete(String storedName) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            settings.operationUrl() + "/api/files/" + pathSegment(storedName)))
                    .timeout(Duration.ofSeconds(30))
                    .header("X-OSS-Key", settings.key())
                    .DELETE()
                    .build();
            return success(http.send(request, HttpResponse.BodyHandlers.discarding()));
        } catch (Exception exception) {
            return false;
        }
    }

    public boolean markDeleted(long fileId, boolean deleted) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            settings.operationUrl() + "/api/files/by-id/" + fileId
                                    + "/deleted?deleted=" + deleted))
                    .timeout(Duration.ofSeconds(30))
                    .header("X-OSS-Key", settings.key())
                    .method("PATCH", HttpRequest.BodyPublishers.noBody())
                    .build();
            return success(http.send(request, HttpResponse.BodyHandlers.discarding()));
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean success(HttpResponse<?> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
