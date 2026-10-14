package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiFileVersion;
import cc.thonly.wikikernel.service.FileService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件展示行（上传页 / 文件列表 / 文件页 / 已删除文件共用）：URL、是否图片、大小文案等。
 * 不在这里做权限判定，调用方先用 {@code AccessControlService.filterReadable} 过滤。
 */
@Component
public class FileViewMapper {

    private final FileService fileService;

    public FileViewMapper(FileService fileService) {
        this.fileService = fileService;
    }

    /** 批量取上传者 / 删除者 ID（配合 {@code UserService.usernames} 一次查名字）。 */
    public Set<Long> uploaderIds(List<WikiFile> files) {
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiFile file : files) {
            if (file.getUploaderId() != null) {
                ids.add(file.getUploaderId());
            }
            if (file.getDeletedBy() != null) {
                ids.add(file.getDeletedBy());
            }
        }
        return ids;
    }

    /** 批量取历史版本的上传者 ID。 */
    public Set<Long> versionUploaderIds(List<WikiFileVersion> versions) {
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiFileVersion version : versions) {
            if (version.getUploaderId() != null) {
                ids.add(version.getUploaderId());
            }
        }
        return ids;
    }

    public Map<String, Object> row(WikiFile file, Map<Long, String> userNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", file.getId());
        row.put("name", file.getName());
        row.put("url", fileService.urlOf(file));
        row.put("pageUrl", fileService.pageUrlOf(file));
        row.put("image", fileService.isImage(file));
        row.put("kind", fileService.mediaKind(file));
        row.put("contentType", file.getContentType());
        row.put("sizeText", formatSize(file.getSize()));
        row.put("description", file.getDescription());
        row.put("version", file.getVersion() == null ? 1 : file.getVersion());
        row.put("deleted", Boolean.TRUE.equals(file.getDeleted()));
        row.put("uploaderName", file.getUploaderId() == null ? null : userNames.get(file.getUploaderId()));
        row.put("createdAt", file.getCreatedAt());
        return row;
    }

    /** 历史版本行（文件页「文件历史」表格用）。 */
    public Map<String, Object> versionRow(WikiFileVersion version, Map<Long, String> userNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("version", version.getVersion());
        row.put("url", fileService.urlOf(version));
        row.put("sizeText", formatSize(version.getSize()));
        row.put("contentType", version.getContentType());
        row.put("comment", version.getComment());
        row.put("uploaderName",
                version.getUploaderId() == null ? null : userNames.get(version.getUploaderId()));
        row.put("createdAt", version.getCreatedAt());
        return row;
    }

    /** 字节数 → 人类可读（B / KB / MB）。 */
    public String formatSize(Long bytes) {
        long value = bytes == null ? 0 : bytes;
        if (value < 1024) {
            return value + " B";
        }
        if (value < 1024 * 1024) {
            return String.format("%.1f KB", value / 1024.0);
        }
        return String.format("%.1f MB", value / (1024.0 * 1024.0));
    }
}
