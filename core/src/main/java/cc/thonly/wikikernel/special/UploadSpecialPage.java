package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.web.FileTitles;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:上传文件} / {@code Special:Upload}：上传文件 + 最近上传预览。
 * <p>
 * 表单由 {@code /js/file-upload.js} 提交到 {@code POST /api/files/upload}（multipart）。
 * 是否需要登录 / 哪个用户组可上传，由访问规则 {@code upload} 动作决定（目标 {@code 文件:<文件名>}）。
 */
@Component
public class UploadSpecialPage implements SpecialPage {

    private static final int RECENT_LIMIT = 24;
    /** 上传页可选的授权协议（文件页会原样展示）。 */
    private static final List<String> LICENSE_OPTIONS = List.of(
            FileService.DEFAULT_LICENSE,
            "知识共享 署名-相同方式共享 4.0",
            "知识共享 署名 4.0",
            "CC0 1.0 公共领域贡献",
            "公有领域",
            "保留所有权利");

    private final FileService fileService;
    private final AccessControlService accessControlService;
    private final UserService userService;
    private final FileViewMapper fileViewMapper;
    private final FeatureService featureService;
    private final MessageSource messageSource;

    public UploadSpecialPage(FileService fileService,
                             AccessControlService accessControlService,
                             UserService userService,
                             FileViewMapper fileViewMapper,
                             FeatureService featureService,
                             MessageSource messageSource) {
        this.fileService = fileService;
        this.accessControlService = accessControlService;
        this.userService = userService;
        this.fileViewMapper = fileViewMapper;
        this.featureService = featureService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "上传文件";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.upload", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Upload");
    }

    @Override
    public String template() {
        return "special/upload";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        model.put("loginRequired", user == null);
        model.put("canUpload", accessControlService.can(AccessAction.UPLOAD, "文件:*", user));
        // 多文件批量上传开关（勾选后文件选择框支持多选，逐个上传；见 file-upload.js）
        // 多媒体预览开关（开启后音视频文件页 / 词条嵌入显示播放器）

        // 从文件页「上传新版本」跳转过来时预填文件名；同名文件已存在 → 提示将创建新版本
        String name = FileTitles.normalizeName(request.parameters().get("name"));
        model.put("prefillName", name);
        model.put("newVersion", !name.isEmpty() && fileService.findByName(name).isPresent());
        model.put("licenseOptions", LICENSE_OPTIONS);
        model.put("defaultLicense", FileService.DEFAULT_LICENSE);

        List<WikiFile> files = accessControlService.filterReadable(
                fileService.recent(RECENT_LIMIT), file -> "文件:" + file.getName(), user);
        Map<Long, String> names = userService.usernames(fileViewMapper.uploaderIds(files));
        model.put("recentFiles", files.stream()
                .map(file -> fileViewMapper.row(file, names))
                .toList());
    }
}
