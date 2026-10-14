package cc.thonly.wikikernel.web;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 多媒体类型（仿 MediaWiki TimedMediaHandler 支持的常见音视频格式）：
 * <ul>
 *     <li>图片：png / jpg / jpeg / gif / webp；</li>
 *     <li>音频：ogg / oga / opus / mp3 / wav / m4a / flac；</li>
 *     <li>视频：webm / ogv / mp4。</li>
 * </ul>
 * 同时给出这些扩展名的标准 MIME（文件服务不信任客户端提交的 Content-Type）。
 */
public final class MediaTypes {

    /** 文件类别。 */
    public enum Kind {
        IMAGE, AUDIO, VIDEO, OTHER
    }

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("ogg", "oga", "opus", "mp3", "wav", "m4a", "flac");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("webm", "ogv", "mp4");

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("oga", "audio/ogg"),
            Map.entry("opus", "audio/ogg"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("flac", "audio/flac"),
            Map.entry("webm", "video/webm"),
            Map.entry("ogv", "video/ogg"),
            Map.entry("mp4", "video/mp4"));

    private MediaTypes() {
    }

    /** 按文件名（扩展名）判断类别。 */
    public static Kind kindOf(String fileName) {
        String extension = extensionOf(fileName);
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return Kind.IMAGE;
        }
        if (AUDIO_EXTENSIONS.contains(extension)) {
            return Kind.AUDIO;
        }
        if (VIDEO_EXTENSIONS.contains(extension)) {
            return Kind.VIDEO;
        }
        return Kind.OTHER;
    }

    /** 是否是可播放的音视频。 */
    public static boolean isPlayable(String fileName) {
        Kind kind = kindOf(fileName);
        return kind == Kind.AUDIO || kind == Kind.VIDEO;
    }

    /** 已知媒体扩展名的 MIME；未知返回 null。 */
    public static String contentTypeOf(String extension) {
        return extension == null ? null : CONTENT_TYPES.get(extension.trim().toLowerCase(Locale.ROOT));
    }

    /** 文件名的小写扩展名（无扩展名返回空串）。 */
    public static String extensionOf(String fileName) {
        String value = fileName == null ? "" : fileName.trim();
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot == value.length() - 1) {
            return "";
        }
        return value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
