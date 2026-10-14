package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.FileWriter;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文件写入桥：把 core 的 {@link FileService}（唯一写入方）暴露给插件
 * （审核通过待审上传 / 待审删除、以及其它需要「以某个用户身份写文件」的场景）。
 * <p>
 * 参数校验、落盘、文件历史与上传 / 删除日志都在 {@code FileService} 里，插件不需要重复实现。
 */
@Service
public class FileWriterImpl implements FileWriter {

    private static final Logger log = LoggerFactory.getLogger(FileWriterImpl.class);

    private final FileService fileService;
    private final UserService userService;

    public FileWriterImpl(FileService fileService, UserService userService) {
        this.fileService = fileService;
        this.userService = userService;
    }

    @Override
    public Result store(long actorUserId, String name, String description, byte[] content) {
        WikiUser actor = userService.findById(actorUserId).orElse(null);
        if (actor == null) {
            return Result.failed("userMissing");
        }
        String fileName = Strings.trimToEmpty(name);
        if (fileName.isEmpty()) {
            return Result.failed("files.nameRequired");
        }
        try {
            WikiFile file = fileService.store(actor, fileName,
                    Strings.trimToEmpty(description), content == null ? new byte[0] : content);
            return Result.ok(file.getId() == null ? 0L : file.getId(), file.getName(),
                    file.getSize() == null ? 0L : file.getSize(), file.getContentType());
        } catch (IllegalArgumentException exception) {
            // FileService 用异常消息当消息键（扩展名不允许 / 超过大小上限等）
            return Result.failed(Strings.trimToEmpty(exception.getMessage()));
        } catch (RuntimeException exception) {
            log.warn("文件写入桥：写入 {} 失败：{}", fileName, exception.toString());
            return Result.failed("files.writeFailed");
        }
    }

    @Override
    public boolean delete(long actorUserId, long fileId, boolean hard) {
        WikiUser actor = userService.findById(actorUserId).orElse(null);
        if (actor == null) {
            return false;
        }
        return fileService.delete(actor, fileId, hard);
    }
}
