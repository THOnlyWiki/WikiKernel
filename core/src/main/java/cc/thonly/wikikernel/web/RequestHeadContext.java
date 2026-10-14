package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.service.FileService;
import jakarta.servlet.http.HttpServletRequest;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link HeadContext} 的请求实现：页面信息 + 请求信息 + 文件服务（WikiSEO 的图片尺寸等）。
 * <p>
 * 词条阅读视图由 {@code WikiPageController} 构造（带本次渲染的页面属性）；
 * 其它页面（特殊页 / 编辑页 / …）由 {@code PluginHeadAdvice} 构造（只有请求信息，无页面属性）。
 */
public class RequestHeadContext implements HeadContext {

    private final long pageId;
    private final String pageTitle;
    private final Map<String, String> query;
    private final Map<String, String> properties;
    private final HttpServletRequest request;
    private final String siteName;
    private final FileService fileService;
    private final String baseUrl;

    public RequestHeadContext(long pageId, String pageTitle, Map<String, String> query,
                              Map<String, String> properties, HttpServletRequest request,
                              String siteName, FileService fileService) {
        this.pageId = pageId;
        this.pageTitle = pageTitle == null ? "" : pageTitle;
        this.query = query == null ? Map.of() : new LinkedHashMap<>(query);
        this.properties = properties == null ? Map.of() : new LinkedHashMap<>(properties);
        this.request = request;
        this.siteName = siteName == null ? "" : siteName;
        this.fileService = fileService;
        this.baseUrl = buildBaseUrl(request);
    }

    /** 请求的站点根地址（如 {@code http://localhost:18080}，不带尾斜杠）。 */
    public static String buildBaseUrl(HttpServletRequest request) {
        StringBuilder base = new StringBuilder();
        base.append(request.isSecure() ? "https" : "http").append("://").append(request.getServerName());
        int port = request.getServerPort();
        boolean standard = request.isSecure() ? port == 443 : port == 80;
        if (!standard) {
            base.append(':').append(port);
        }
        return base.toString();
    }

    @Override
    public long pageId() {
        return pageId;
    }

    @Override
    public String pageTitle() {
        return pageTitle;
    }

    @Override
    public String path() {
        return WikiUrls.pagePath(pageTitle) + querySuffix();
    }

    @Override
    public Map<String, String> query() {
        return query;
    }

    @Override
    public Map<String, String> properties() {
        return properties;
    }

    @Override
    public String siteName() {
        return siteName;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public String fileUrl(String fileName) {
        WikiFile file = findFile(fileName);
        return file == null || fileService == null ? "" : fileService.urlOf(file);
    }

    @Override
    public int fileWidth(String fileName) {
        return imageSize(fileName)[0];
    }

    @Override
    public int fileHeight(String fileName) {
        return imageSize(fileName)[1];
    }

    private String querySuffix() {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder suffix = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) {
                suffix.append('&');
            }
            first = false;
            suffix.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return suffix.toString();
    }

    /** 本地图片的像素尺寸（读文件头；非图片 / 不存在返回 0）。 */
    private int[] imageSize(String fileName) {
        WikiFile file = findFile(fileName);
        if (file == null || fileService == null || !fileService.isImage(file)) {
            return new int[]{0, 0};
        }
        try {
            java.awt.image.BufferedImage image = ImageIO.read(new ByteArrayInputStream(fileService.read(file)));
            if (image == null) {
                return new int[]{0, 0};
            }
            return new int[]{image.getWidth(), image.getHeight()};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    /** 文件名 → 媒体文件（不区分大小写；支持「文件:X」前缀；找不到返回 null）。 */
    private WikiFile findFile(String fileName) {
        if (fileName == null || fileName.isBlank() || fileService == null) {
            return null;
        }
        String name = fileName.trim();
        if (FileTitles.isFileTitle(name)) {
            name = FileTitles.fileNameOf(name);
        }
        return fileService.findAnyByName(name).orElse(null);
    }
}
