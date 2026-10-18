package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiFileVersion;
import cc.thonly.wikikernel.config.ThumbnailProperties;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.ThumbnailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动后后台补齐已有图片的缩略图：
 * 老站点导入 / 旧版本升级时磁盘上已有原图，但没有 thumbnail 目录；
 * 这里用低优先级虚拟线程逐个检查并生成，避免阻塞启动。
 * <p>
 * 已存在缩略图的文件只做两次文件存在检查；日常启动几乎没有成本。
 */
@Component
public class ThumbnailBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailBackfillRunner.class);

    private final FileService fileService;
    private final ThumbnailService thumbnailService;
    private final ThumbnailProperties properties;

    public ThumbnailBackfillRunner(FileService fileService,
                                   ThumbnailService thumbnailService,
                                   ThumbnailProperties properties) {
        this.fileService = fileService;
        this.thumbnailService = thumbnailService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        Thread.ofVirtual().name("thumbnail-backfill").start(() -> {
            try {
                int checked = 0;
                int generated = 0;
                for (WikiFile file : fileService.all()) {
                    if (!fileService.isImage(file)) {
                        continue;
                    }
                    checked++;
                    if (!thumbnailService.exists(file.getStoredName(), ThumbnailService.HALF)
                            || !thumbnailService.exists(file.getStoredName(), ThumbnailService.QUARTER)) {
                        try {
                            thumbnailService.generate(file);
                            generated++;
                        } catch (RuntimeException exception) {
                            log.warn("补齐缩略图失败：{}", file.getName(), exception);
                        }
                    }
                    // 旧版本也生成一份缩略图，文件历史 / 旧版本预览以后可直接复用。
                    for (WikiFileVersion version : fileService.versions(file)) {
                        if (!fileService.isImage(file.getName())) {
                            continue;
                        }
                        if (thumbnailService.exists(version.getStoredName(), ThumbnailService.HALF)
                                && thumbnailService.exists(version.getStoredName(), ThumbnailService.QUARTER)) {
                            continue;
                        }
                        WikiFile versionFile = new WikiFile();
                        versionFile.setName(file.getName());
                        versionFile.setStoredName(version.getStoredName());
                        versionFile.setContentType(version.getContentType());
                        try {
                            thumbnailService.generate(versionFile);
                            generated++;
                        } catch (RuntimeException exception) {
                            log.warn("补齐历史版本缩略图失败：{} v{}", file.getName(), version.getVersion(), exception);
                        }
                    }
                }
                if (generated > 0) {
                    log.info("缩略图后台补齐完成：检查 {} 张图片，生成 {} 张", checked, generated);
                } else {
                    log.debug("缩略图检查完成：{} 张图片均已存在", checked);
                }
            } catch (RuntimeException exception) {
                log.warn("缩略图后台补齐异常", exception);
            }
        });
    }
}
