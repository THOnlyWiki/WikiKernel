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
