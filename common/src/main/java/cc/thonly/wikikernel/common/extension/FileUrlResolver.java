package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 外部文件存储（OSS）桥接：core 在生成原图 / 缩略图 URL 时询问插件。
 * <p>
 * 插件没有实现或返回 {@code null} 时，core 回退本地 {@code /files} / {@code /files/thumbnail}。
 * 一个站点通常只启用一个 OSS 插件；多个时按插件加载顺序取第一个声明处理对应类型的实现。
 */
public interface FileUrlResolver extends Extension {

    /** 是否接管原图 URL（OSS 模式 all 时返回 true）。 */
    default boolean handlesOriginals() {
        return false;
    }

    /** 是否接管缩略图 URL（OSS 模式 thumbnail / all 时返回 true）。 */
    default boolean handlesThumbnails() {
        return false;
    }

    /**
     * OSS 当前是否可用。不可用时：
     * <ul>
     *     <li>缩略图由 core 生成本地回退；</li>
     *     <li>{@link #handlesOriginals()} 为 true 时，原图返回 404，而不是静默回退本地。</li>
     * </ul>
     */
    default boolean available() {
        return true;
    }

    /** 当前版本原图的远程 URL；返回 {@code null} 表示回退本地。 */
    default String originalUrl(WikiFile file) {
        return null;
    }

    /** 历史版本原图的远程 URL；返回 {@code null} 表示回退本地。 */
    default String originalUrl(String storedName) {
        return null;
    }

    /** 当前文件指定缩略图变体（half / quarter）的远程 URL；返回 {@code null} 表示回退本地。 */
    default String thumbnailUrl(WikiFile file, String variant) {
        return null;
    }
}
