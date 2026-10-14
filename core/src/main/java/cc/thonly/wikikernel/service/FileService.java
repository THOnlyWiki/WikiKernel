package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiFileVersion;
import cc.thonly.wikikernel.common.data.WikiUser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 上传文件服务：内容存 {@code wikikernel.run-path}，元信息存 {@code wiki_file}（当前版本）
 * + {@code wiki_file_version}（历史版本）。
 * <p>
 * 模型（仿 MediaWiki）：同名文件重复上传 = 新版本，旧版本自动归档并可回滚；
 * 删除默认软删除（可恢复），也可以勾选彻底删除（连同全部历史版本与磁盘内容一起清掉）。
 * 访问权限按 {@code 文件:<name>} 判定（上传 = {@code upload} 动作，查看 = {@code read} 动作）。
 */
public interface FileService {

    /**
     * 上传 / 覆盖：同名活跃文件 → 归档当前版本并新建版本；
     * 文件已被软删除时拒绝（需要先恢复，或换一个文件名）。
     */
    WikiFile store(WikiUser uploader, String name, String description, byte[] content);

    /** 按 ID 查文件（含已删除，供删除 / 恢复用）。 */
    Optional<WikiFile> findById(long id);

    /** 按文件名查活跃文件（不含已删除）；wikitext 嵌入与列表用它。 */
    Optional<WikiFile> findByName(String name);

    /** 按文件名查文件（含已删除）；文件页与恢复用它。 */
    Optional<WikiFile> findAnyByName(String name);

    /** 按磁盘存储名定位文件或历史版本（{@code /files/**} 与权限判定用）。 */
    Optional<StoredFile> resolveStored(String storedName);

    /** 最近上传的活跃文件（倒序）。 */
    List<WikiFile> recent(int limit);

    /** 全部活跃文件（倒序）。 */
    List<WikiFile> all();

    /** 已删除（软删除）的文件（倒序）。 */
    List<WikiFile> deleted();

    /** 某个文件的旧版本（版本号倒序，不含当前版本）。 */
    List<WikiFileVersion> versions(WikiFile file);

    /** 恢复到指定旧版本：复制该版本内容成为新的当前版本（版本号 +1，历史保留）。 */
    WikiFile revert(WikiUser user, String name, int version);

    /** 删除文件：{@code hard=false} 软删除（可恢复），{@code hard=true} 彻底删除（含全部历史版本）。 */
    boolean delete(WikiUser user, long id, boolean hard);

    /** 恢复软删除的文件。 */
    boolean restore(WikiUser user, long id);

    Path pathOf(WikiFile file);

    Path pathOf(String storedName);

    byte[] read(WikiFile file) throws IOException;

    byte[] read(String storedName) throws IOException;

    boolean isImage(WikiFile file);

    boolean isImage(String fileName);

    /** 媒体类型：{@code image} / {@code audio} / {@code video} / {@code other}（按扩展名，仿 TimedMediaHandler 的分类）。 */
    String mediaKind(WikiFile file);

    String mediaKind(String fileName);

    /** 是否是可播放的音频。 */
    default boolean isAudio(String fileName) {
        return "audio".equals(mediaKind(fileName));
    }

    /** 是否是可播放的视频。 */
    default boolean isVideo(String fileName) {
        return "video".equals(mediaKind(fileName));
    }

    String urlOf(WikiFile file);

    String urlOf(WikiFileVersion version);

    /** 文件页 URL：{@code /wiki/文件:<name>}。 */
    String pageUrlOf(WikiFile file);

    /** 文件页 URL（按文件名）。 */
    String pageUrl(String fileName);

    /** 磁盘存储名对应的文件引用（文件名 + 类型 + 文件是否已删除）。 */
    record StoredFile(String name, String storedName, String contentType, boolean deleted) {
    }
}
