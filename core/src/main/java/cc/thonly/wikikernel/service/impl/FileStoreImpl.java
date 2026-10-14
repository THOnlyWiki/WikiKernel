package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.service.FileStore;
import cc.thonly.wikikernel.common.service.StoredMediaFile;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.web.FileTitles;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 媒体文件访问桥（{@code FileService} 的只读视图，对插件暴露 common 接口）。
 */
@Service
public class FileStoreImpl implements FileStore {

    private final FileService fileService;

    public FileStoreImpl(FileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public Optional<StoredMediaFile> find(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String name = fileName.trim();
        if (FileTitles.isFileTitle(name)) {
            name = FileTitles.fileNameOf(name);
        }
        return fileService.findAnyByName(name).map(file -> new StoredMediaFile(
                file.getName(),
                fileService.urlOf(file),
                fileService.pageUrlOf(file),
                fileService.isImage(file),
                fileService.mediaKind(file)));
    }
}
