package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.offline.OfflineExportService;
import cc.thonly.wikikernel.service.PermissionService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:导出离线} / {@code Special:ExportOffline}：
 * 把当前站点所有可读页面、皮肤、插件静态资源和上传文件导出为只读离线 ZIP。
 *
 * <p>只有行政员可以启动导出；实际执行由 {@code POST /api/offline-export} 创建后台任务。</p>
 */
@Component
public class OfflineExportSpecialPage implements SpecialPage {

    private final OfflineExportService exportService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public OfflineExportSpecialPage(OfflineExportService exportService,
                                    PermissionService permissionService,
                                    MessageSource messageSource) {
        this.exportService = exportService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "导出离线";
    }

    @Override
    public String displayName() {
        return message("special.offlineExport", "导出离线");
    }

    @Override
    public List<String> aliases() {
        return List.of("ExportOffline", "导出前端");
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String template() {
        return "special/offline-export";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        boolean canExport = request.user() != null
                && request.user().getId() != null
                && permissionService.has(request.user().getId(), PermissionService.GROUP_BUREAUCRAT);
        model.put("canExport", canExport);
        model.put("pageCount", canExport ? safePageCount(request) : 0);
        model.put("offlineExportIntro", message("offlineExport.intro",
                "导出当前站点的只读离线版本：保留皮肤、插件资源、全部页面和图片，并生成离线搜索。"));
        model.put("offlineExportPermission", message("offlineExport.permission",
                "需要行政员权限才能导出完整站点。"));
        model.put("offlineExportPages", message("offlineExport.pages", "可导出页面"));
        model.put("offlineExportStart", message("offlineExport.start", "开始导出"));
        model.put("offlineExportHint", message("offlineExport.hint",
                "图片会转为 WebP，并按原图宽高的一半保存；导出是只读快照，编辑、登录和评论提交不可用。"));
    }

    private int safePageCount(SpecialPageRequest request) {
        try {
            return exportService.countExportablePages(request.user());
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private String message(String key, String fallback) {
        return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
