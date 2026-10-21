package cc.thonly.wikikernel.common.service;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiFileVersion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 给插件使用的只读文件目录：枚举当前文件和历史版本、读取原图路径 / 内容。
 * <p>
 * 供 OSS 扩展在首次迁移时全量扫描；普通插件查文件仍优先用 {@link FileStore}。
 */
public interface FileCatalog {

    /** 全部活跃文件（倒序）。 */
    List<WikiFile> all();

    /** 某个文件的历史版本（版本号倒序）。 */
    List<WikiFileVersion> versions(WikiFile file);

    /** 存储名对应的本地原图路径。 */
    Path pathOf(String storedName);

    /** 读取存储名对应的原图内容。 */
    byte[] read(String storedName) throws IOException;

    /** 是否图片（按文件名后缀）。 */
    boolean isImage(WikiFile file);
}
