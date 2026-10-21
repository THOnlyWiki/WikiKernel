package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缩略图配置：上传图片时生成 1/2、1/4 两档 WebP，存放在 {@code <run-path>/thumbnail/}。
 * 默认总容量 2GB（2048MB），超过后按文件修改时间删除最旧的缩略图（只删缩略图，不删原图）。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.thumbnail")
public class ThumbnailProperties {

    /** 是否启用缩略图生成。 */
    private boolean enabled = true;

    /** 缩略图总容量上限（MB）；{@code <= 0} 表示不限制。默认 2048MB（2GB）。 */
    private long maxSizeMb = 2048;

    /** WebP 质量（1-100）。 */
    private int quality = 82;

    /** ImageMagick 可执行文件路径；空 = 自动查找 {@code magick}。 */
    private String executable = "";

    /** thumb.php 按需生成队列上限（默认 2；队列满时阻塞调用线程，延迟响应而不是拒绝）。 */
    private int queueLimit = 2;
}

