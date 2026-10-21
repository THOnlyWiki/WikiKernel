package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiFile;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 图片缩略图：为上传图片生成 1/2、1/4 两档 WebP，存放在 {@code <run-path>/thumbnail/<storedName>/}。
 * <p>
 * 配额由 {@code wikikernel.thumbnail.max-size-gb} 控制（默认 2GB）；超过后按文件修改时间删除最旧的缩略图，
 * 永远不会删除原图。生成失败只记录日志，不影响原图上传。
 */
public interface ThumbnailService {

    /** 半尺寸变体名。 */
    String HALF = "half";

    /** 四分之一尺寸变体名。 */
    String QUARTER = "quarter";

    /** 读取原图像素尺寸；读不到返回 {@code null}。 */
    Dimensions measure(Path source);

    /** 为已落盘的原图生成两档缩略图（已存在的变体跳过），随后执行配额清理。 */
    void generate(WikiFile file);

    /** 读取一档缩略图内容。 */
    Optional<byte[]> read(String storedName, String variant);

    /** 缩略图是否已存在。 */
    boolean exists(String storedName, String variant);

    /**
     * 按请求宽度按需生成 WebP（thumb.php 用）：
     * 宽度不会超过原图（不放大），结果缓存在 {@code thumbnail/<storedName>/w<width>.webp}。
     * 队列上限来自 {@code wikikernel.thumbnail.queue-limit}，满了会阻塞当前请求线程。
     *
     * @return 生成文件或原图路径；无法处理时为空
     */
    Optional<Path> ensureWidth(WikiFile file, int requestedWidth);

    /**
     * 对任意本地图片按宽度生成 WebP（离线导出等场景用；不放大，保持比例）。
     * 结果缓存在 {@code thumbnail/offline/}，不会影响上传文件的 half/quarter 变体。
     *
     * @param source         本地图片文件
     * @param cacheKey       稳定的缓存键（通常用原始 URL / 文件名）
     * @param requestedWidth 目标宽度；<=0 表示使用原图宽度
     * @return 生成的 WebP 文件；ImageMagick 不可用或源图无法识别时为空
     */
    Optional<Path> ensureWidth(Path source, String cacheKey, int requestedWidth);

    /** 缩略图访问 URL（不检查是否存在）。 */
    String url(String storedName, String variant);

    /** 删除某个原图（版本）对应的全部缩略图。 */
    void delete(String storedName);

    /** 执行一次配额清理，返回删除的文件数。 */
    int enforceQuota();

    /** 像素尺寸。 */
    record Dimensions(int width, int height) {
    }
}
