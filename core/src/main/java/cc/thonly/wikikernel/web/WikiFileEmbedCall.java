package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.extension.FileEmbedCall;
import cc.thonly.wikikernel.service.FileService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link FileEmbedCall} 的 core 实现：把一个 {@link WikiFile} 与词条里的嵌入参数交给插件渲染器
 * （多媒体预览等；见 {@code common.extension.FileEmbedRenderer}）。
 */
public final class WikiFileEmbedCall implements FileEmbedCall {

    private final String fileName;
    private final String url;
    private final String pageUrl;
    private final String mediaKind;
    private final String mimeType;
    private final long size;
    private final Integer width;
    private final String caption;
    private final String alt;
    private final Map<String, String> options;

    private WikiFileEmbedCall(String fileName, String url, String pageUrl, String mediaKind, String mimeType,
                              long size, Integer width, String caption, String alt, Map<String, String> options) {
        this.fileName = fileName;
        this.url = url;
        this.pageUrl = pageUrl;
        this.mediaKind = mediaKind;
        this.mimeType = mimeType;
        this.size = size;
        this.width = width;
        this.caption = caption == null ? "" : caption;
        this.alt = alt == null ? "" : alt;
        this.options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** 由文件元信息与嵌入参数构造（{@code width} 可为 null = 未指定宽度）。 */
    public static WikiFileEmbedCall of(WikiFile file, FileService fileService, Integer width,
                                       String caption, String alt, Map<String, String> options) {
        Map<String, String> extras = options == null ? Map.of() : new LinkedHashMap<>(options);
        return new WikiFileEmbedCall(
                file.getName(),
                fileService.urlOf(file),
                fileService.pageUrlOf(file),
                fileService.mediaKind(file),
                file.getContentType(),
                file.getSize() == null ? 0L : file.getSize(),
                width, caption, alt, extras);
    }

    @Override
    public String fileName() {
        return fileName;
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public String pageUrl() {
        return pageUrl;
    }

    @Override
    public String mediaKind() {
        return mediaKind;
    }

    @Override
    public String mimeType() {
        return mimeType;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public Integer width() {
        return width;
    }

    @Override
    public String caption() {
        return caption;
    }

    @Override
    public String alt() {
        return alt;
    }

    @Override
    public Map<String, String> options() {
        return options;
    }
}
