package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.config.TransferProperties;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiExportService;
import cc.thonly.wikikernel.service.WikiImportService;
import cc.thonly.wikikernel.transfer.ExportData;
import cc.thonly.wikikernel.transfer.ImportResult;
import cc.thonly.wikikernel.transfer.MediaWikiXmlExporter;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.transfer.TransferFormat;
import cc.thonly.wikikernel.transfer.WikiKernelJsonExporter;
import cc.thonly.wikikernel.transfer.WikiKernelXmlExporter;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出 / 导入 API（{@code @RestController}）：
 * <ul>
 *     <li>{@code GET|POST /api/export}：导出页面（MediaWiki XML / WikiKernel XML / JSON），
 *     按访问规则过滤，{@code download=1} 时以附件下载；</li>
 *     <li>{@code POST /api/import}：multipart 上传导出文件（自动识别三种格式），
 *     管理员 / 行政员可用；重复导入同一文件不会产生重复版本。</li>
 * </ul>
 */
@RestController
public class TransferApiController {

    private static final Logger log = LoggerFactory.getLogger(TransferApiController.class);

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final WikiExportService exportService;
    private final WikiImportService importService;
    private final PermissionService permissionService;
    private final TransferProperties properties;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    public TransferApiController(WikiExportService exportService,
                                 WikiImportService importService,
                                 PermissionService permissionService,
                                 TransferProperties properties,
                                 ObjectMapper objectMapper,
                                 MessageSource messageSource) {
        this.exportService = exportService;
        this.importService = importService;
        this.permissionService = permissionService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    /** 导出：表单（POST）与脚本（GET）共用。 */
    @RequestMapping(value = "/api/export", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> export(@RequestParam(value = "pages", required = false) String pages,
                                         @RequestParam(value = "history", required = false, defaultValue = "false")
                                         boolean history,
                                         @RequestParam(value = "templates", required = false, defaultValue = "false")
                                         boolean templates,
                                         @RequestParam(value = "format", required = false) String format,
                                         @RequestParam(value = "download", required = false, defaultValue = "false")
                                         boolean download,
                                         HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        List<String> titles = splitTitles(pages);
        if (titles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message("export.empty"));
        }
        TransferFormat transferFormat = TransferFormat.fromId(format);
        ExportData data = exportService.collect(titles, history, templates, user, baseUrl(request));

        byte[] bytes;
        try {
            bytes = switch (transferFormat) {
                case MEDIAWIKI_XML -> MediaWikiXmlExporter.export(data);
                case WIKIKERNEL_XML -> WikiKernelXmlExporter.export(data);
                case WIKIKERNEL_JSON -> WikiKernelJsonExporter.export(data, objectMapper);
            };
        } catch (XMLStreamException | JacksonException exception) {
            log.error("生成导出文件失败：format={}", transferFormat.id(), exception);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message("export.failed"));
        }

        String fileName = "wikikernel-export-" + FILE_STAMP.format(LocalDateTime.now())
                + "." + transferFormat.extension();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(transferFormat.contentType()));
        headers.setContentDisposition(ContentDisposition
                .builder(download ? "attachment" : "inline")
                .filename(fileName)
                .build());
        headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /** 导入：multipart 字段 {@code file}，可选 {@code namespace}（空 = 保持原命名空间）。 */
    @PostMapping("/api/import")
    public AjaxResult<Map<String, Object>> importFile(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "namespace", required = false)
                                                      String namespace,
                                                      HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        if (!permissionService.canManage(user.getId())) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("import.forbidden"));
        }
        if (file == null || file.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("import.noFile"));
        }
        long maxBytes = properties.getImportMaxBytes();
        if (maxBytes > 0 && file.getSize() > maxBytes) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    message("import.tooLarge", Math.max(1, maxBytes / (1024 * 1024))));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            return AjaxResult.error(AjaxCode.ERROR, message("import.parseFailed"));
        }

        ImportResult result;
        try {
            result = importService.importFile(bytes, namespace, user);
        } catch (IllegalArgumentException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message(exception.getMessage()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pagesCreated", result.pagesCreated());
        data.put("pagesUpdated", result.pagesUpdated());
        data.put("pagesSkipped", result.pagesSkipped());
        data.put("revisionsImported", result.revisionsImported());
        data.put("revisionsSkipped", result.revisionsSkipped());
        data.put("truncated", result.truncated());
        data.put("errors", result.errors());

        String summary = message("import.success", result.pagesCreated(), result.pagesUpdated(),
                result.pagesSkipped(), result.revisionsImported(), result.revisionsSkipped());
        if (result.truncated()) {
            summary = summary + " " + message("import.truncated");
        }
        return AjaxResult.success(summary, data);
    }

    /** 多行文本 → 标题列表（去空行；去重由导出服务处理）。 */
    private static List<String> splitTitles(String text) {
        List<String> titles = new ArrayList<>();
        if (text == null) {
            return titles;
        }
        for (String line : text.split("\\R")) {
            String title = line.trim();
            if (!title.isEmpty()) {
                titles.add(title);
            }
        }
        return titles;
    }

    /** 站点首页 URL（写入导出文件的 siteinfo.base）。 */
    private static String baseUrl(HttpServletRequest request) {
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        return request.getScheme() + "://" + request.getServerName()
                + (defaultPort ? "" : ":" + port) + WikiUrls.pagePrefix();
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
