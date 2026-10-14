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

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:文件列表} / {@code Special:FileList}：已上传文件（图片缩略图预览）。
 * 无权查看的文件不出现（标题也不泄露）。
 */
@Component
public class FileListSpecialPage implements SpecialPage {

    private static final int LIMIT = 200;

    private final FileService fileService;
    private final AccessControlService accessControlService;
    private final UserService userService;
    private final FileViewMapper fileViewMapper;
    private final MessageSource messageSource;

    public FileListSpecialPage(FileService fileService,
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
        return "文件列表";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.fileList", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("FileList", "Files");
    }

    @Override
    public String template() {
        return "special/file-list";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        List<WikiFile> visible = accessControlService.filterReadable(
                fileService.recent(LIMIT), file -> "文件:" + file.getName(), user);
        Map<Long, String> names = userService.usernames(fileViewMapper.uploaderIds(visible));
        model.put("files", visible.stream()
                .map(file -> fileViewMapper.row(file, names))
                .toList());
        model.put("canUpload", accessControlService.can(AccessAction.UPLOAD, "文件:*", user));
        model.put("canViewDeleted", accessControlService.can(AccessAction.DELETE, "文件:*", user));
    }
}
