package cc.thonly.wikikernel.common.extension;

import java.nio.file.Path;

/**
 * 一次文件状态变化（上传 / 新版本 / 回滚 / 删除）。
 * <p>
 * 事件由 core 在数据库和磁盘写入完成后派发；插件应尽快把任务写入自己的失败队列，
 * 不要在监听器里执行长时间网络上传，避免拖慢 Wiki 的文件保存请求。
 *
 * @param type        变化类型
 * @param fileId      wiki_file.id
 * @param fileName    展示文件名
 * @param storedName  磁盘存储名（OSS 对象键）
 * @param contentType MIME
 * @param size        字节数
 * @param version     版本号
 * @param path        本地原图路径（删除事件为 null）
 * @param current     是否为当前最新版本
 */
public record FileChangeEvent(Type type, long fileId, String fileName, String storedName,
                              String contentType, long size, int version, Path path,
                              boolean current) {

    public enum Type {
        /** 新文件或新当前版本（包含回滚产生的新副本）。 */
        UPLOAD,
        /** 整个文件被软删除：OSS 应停止公开读取，但保留对象。 */
        SOFT_DELETE,
        /** 软删除恢复。 */
        RESTORE,
        /** 某个原图版本被永久删除。 */
        DELETE
    }
}
