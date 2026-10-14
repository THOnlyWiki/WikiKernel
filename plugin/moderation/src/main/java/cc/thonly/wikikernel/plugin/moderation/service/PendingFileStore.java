package cc.thonly.wikikernel.plugin.moderation.service;

import java.util.Optional;

/**
 * 待审文件暂存（审核插件自己的磁盘暂存区）。
 * <p>
 * 待审上传的文件内容先落在插件的 {@code PluginContext.dataDir()/pending/} 下，
 * 通过审核后才写入正式文件存储（用 core 的 {@code common.service.FileWriter} 桥）。
 * <p>
 * 实现是插件子容器里的 {@link PendingFileStoreImpl}；方法签名与迁移前的
 * {@code common.service.PendingFileStore} 完全一致。
 */
public interface PendingFileStore {

    /** 暂存文件内容；返回暂存名（存进 {@code WikiPendingChange.fileStoredName}）。 */
    String stage(String fileName, byte[] content);

    /** 读取暂存内容（不存在返回空）。 */
    Optional<byte[]> read(String storedName);

    /** 删除暂存（通过 / 拒绝后清理）。 */
    boolean delete(String storedName);
}
