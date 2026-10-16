package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.UserService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:已删除文件} / {@code Special:DeletedFiles}：软删除文件的回收站。
 * <p>
 * 需要 {@code delete} 权限（默认管理员）；可以恢复（撤销软删除）或彻底删除
 * （连同全部历史版本与磁盘内容一起清掉，不可恢复）。表单由 {@code /js/file-upload.js} 提交。
 */
@Component
public class DeletedFilesSpecialPage implements SpecialPage {

    private final FileService fileService;
    private final AccessControlService accessControlService;
    private final UserService userService;
    private final FileViewMapper fileViewMapper;
    private final MessageSource messageSource;

    public DeletedFilesSpecialPage(FileService fileService,
                                   AccessControlService accessControlService,
                                   UserService userService,
                                   FileViewMapper fileViewMapper,
                                   MessageSource messageSource) {
        this.fileService = fileService;
        this.accessControlService = accessControlService;
        this.userService = userService;
        this.fileViewMapper = fileViewMapper;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "已删除文件";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.deletedFiles", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        // Undelete 让给「已删除页面」（Special:Undelete 在 MediaWiki 里是页面恢复；文件回收站用 DeletedFiles）
        return List.of("DeletedFiles");
    }

    @Override
    public String template() {
        return "special/deleted-files";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        boolean canDelete = accessControlService.can(AccessAction.DELETE, "文件:*", user);
        model.put("canDelete", canDelete);
        if (!canDelete) {
            return;
        }
        boolean canPurge = accessControlService.can(AccessAction.PURGE, "文件:*", user);
        model.put("canPurge", canPurge);
        List<WikiFile> files = accessControlService.filterReadable(
                fileService.deleted(), file -> "文件:" + file.getName(), user);
        Map<Long, String> names = userService.usernames(fileViewMapper.uploaderIds(files));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiFile file : files) {
            Map<String, Object> row = new LinkedHashMap<>(fileViewMapper.row(file, names));
            row.put("deletedAt", file.getDeletedAt());
            row.put("deletedByName",
                    file.getDeletedBy() == null ? null : names.get(file.getDeletedBy()));
            // 彻底删除按钮按 purge 权限逐个判断（默认管理员，可在 特殊:权限配置 改）
            row.put("canPurge", accessControlService.can(AccessAction.PURGE, "文件:" + file.getName(), user));
            rows.add(row);
        }
        model.put("files", rows);
    }
}
