package cc.thonly.wikikernel.common.service;

import java.util.Optional;

/**
 * 媒体文件访问桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 只读：按文件名查文件并拿到 URL（如 Flash 播放器解析 {@code [[文件:X.swf]]}）。
 */
public interface FileStore {

    /**
     * 按文件名查媒体文件（不区分大小写；支持「文件:X」/「File:X」前缀；找不到返回空）。
     */
    Optional<StoredMediaFile> find(String fileName);
}
