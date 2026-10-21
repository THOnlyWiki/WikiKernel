package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiFileVersion;
import cc.thonly.wikikernel.common.service.FileCatalog;
import cc.thonly.wikikernel.service.FileService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** {@link FileCatalog} 的 core 实现：直接复用 {@link FileService} 的磁盘 / 元数据视图。 */
@Service
public class FileCatalogImpl implements FileCatalog {

    private final FileService fileService;

    public FileCatalogImpl(FileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public List<WikiFile> all() {
        return fileService.all();
    }

    @Override
    public List<WikiFileVersion> versions(WikiFile file) {
        return fileService.versions(file);
    }

    @Override
    public Path pathOf(String storedName) {
        return fileService.pathOf(storedName);
    }

    @Override
    public byte[] read(String storedName) throws IOException {
        return fileService.read(storedName);
    }

    @Override
    public boolean isImage(WikiFile file) {
        return fileService.isImage(file);
    }
}
