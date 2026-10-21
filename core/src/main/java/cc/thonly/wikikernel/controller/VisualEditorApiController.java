package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.wikitext.VisualEditorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 可视化编辑器 API（{@code @RestController}）：
 * <ul>
 *     <li>{@code POST /api/pages/visual/render}：把编辑框里的 Wikitext 渲染成可编辑 DOM
 *     （只从源码出发；目录 / 分类栏等生成内容会被服务端剥掉）；</li>
 *     <li>{@code POST /api/pages/visual/serialize}：把编辑后的 HTML 序列化回 Wikitext；
 *     不支持的结构整体拒绝，绝不会写回半成品；</li>
 *     <li>{@code POST /api/pages/visual/template}：模板对话框数据 —— 解析一次模板调用并发现模板的
 *     全部参数（扫描模板页源码里的 <code>{{{参数}}}</code>）＋渲染预览；也可以按参数表单重建调用源码
 *     （值里的 {@code |} 自动转义成 {@code {{!}}}）。</li>
 * </ul>
 * 权限与「显示预览」一致：登录 + 对该标题有编辑 / 创建权限 + 未被封禁。
 */
@RestController
@RequestMapping("/api/pages/visual")
public class VisualEditorApiController {

    private final VisualEditorService visualEditorService;
    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;
    private final BlockService blockService;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    public VisualEditorApiController(VisualEditorService visualEditorService,
                                     WikiPageService wikiPageService,
                                     AccessControlService accessControlService,
                                     BlockService blockService,
                                     ObjectMapper objectMapper,
                                     MessageSource messageSource) {
        this.visualEditorService = visualEditorService;
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
        this.blockService = blockService;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    /** 渲染可视化编辑器内容。 */
    @PostMapping("/render")
    public AjaxResult<Map<String, Object>> render(@RequestParam("title") String title,
                                                  @RequestParam("content") String content,
                                                  HttpServletRequest request) {
        Denied denied = permissionError(title, request);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        VisualEditorService.Rendered rendered =
                visualEditorService.render(content, Strings.trimToEmpty(title));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("html", rendered.html());
        data.put("tokens", rendered.tokens());
        return AjaxResult.success(data);
    }

    /** 把编辑后的 HTML 序列化回 Wikitext。 */
    @PostMapping("/serialize")
    public AjaxResult<Map<String, Object>> serialize(@RequestParam("title") String title,
                                                     @RequestParam("html") String html,
                                                     @RequestParam(value = "tokens", required = false)
                                                     String tokensJson,
                                                     HttpServletRequest request) {
        Denied denied = permissionError(title, request);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        List<String> tokens;
        try {
            String raw = Strings.trimToEmpty(tokensJson);
            tokens = raw.isEmpty() ? List.of()
                    : List.of(objectMapper.readValue(raw, String[].class));
        } catch (RuntimeException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("visualEditor.invalidTokens"));
        }
        try {
            String text = visualEditorService.serialize(html, tokens);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("text", text);
            return AjaxResult.success(data);
        } catch (VisualEditorService.UnsupportedException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    message("visualEditor.unsupported", exception.detail()));
        }
    }

    /** 模板对话框：解析当前调用（发现全部参数 + 预览）或按参数表单重建调用（返回新源码 + 预览）。 */
    @PostMapping("/template")
    public AjaxResult<Map<String, Object>> template(@RequestParam("title") String title,
                                                    @RequestParam(value = "source", required = false)
                                                    String source,
                                                    @RequestParam(value = "name", required = false)
                                                    String name,
                                                    @RequestParam(value = "params", required = false)
                                                    String paramsJson,
                                                    HttpServletRequest request) {
        Denied denied = permissionError(title, request);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        try {
            VisualEditorService.TemplateInfo info;
            if (!Strings.trimToEmpty(paramsJson).isEmpty()) {
                info = visualEditorService.buildTemplate(title, name, parseParams(paramsJson));
            } else {
                info = visualEditorService.openTemplate(title, source);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", info.name());
            data.put("title", info.title());
            data.put("exists", info.exists());
            data.put("source", info.source());
            data.put("previewHtml", info.previewHtml());
            List<Map<String, Object>> params = new java.util.ArrayList<>();
            for (VisualEditorService.TemplateParam param : info.params()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", param.name());
                row.put("value", param.value());
                row.put("positional", param.positional());
                row.put("present", param.present());
                row.put("known", param.known());
                params.add(row);
            }
            data.put("params", params);
            return AjaxResult.success(data);
        } catch (IllegalArgumentException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("visualEditor.templateInvalid"));
        }
    }

    /** 画廊对话框：解析 / 重建 {@code <gallery>}，返回图片行、属性和预览。 */
    @PostMapping("/gallery")
    public AjaxResult<Map<String, Object>> gallery(@RequestParam("title") String title,
                                                   @RequestParam(value = "source", required = false)
                                                   String source,
                                                   @RequestParam(value = "items", required = false)
                                                   String itemsJson,
                                                   @RequestParam(value = "attributes", required = false)
                                                   String attributesJson,
                                                   HttpServletRequest request) {
        Denied denied = permissionError(title, request);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        try {
            VisualEditorService.GalleryInfo info;
            boolean build = !Strings.trimToEmpty(itemsJson).isEmpty()
                    || !Strings.trimToEmpty(attributesJson).isEmpty();
            if (build) {
                info = visualEditorService.buildGallery(title, parseGalleryAttributes(attributesJson),
                        parseGalleryItems(itemsJson));
            } else {
                info = visualEditorService.openGallery(title, source);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", info.source());
            data.put("label", info.label());
            data.put("previewHtml", info.previewHtml());
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (VisualEditorService.GalleryItem item : info.items()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file", item.file());
                row.put("caption", item.caption());
                items.add(row);
            }
            data.put("items", items);
            List<Map<String, Object>> attributes = new java.util.ArrayList<>();
            for (VisualEditorService.GalleryAttribute attribute : info.attributes()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", attribute.name());
                row.put("value", attribute.value());
                attributes.add(row);
            }
            data.put("attributes", attributes);
            return AjaxResult.success(data);
        } catch (IllegalArgumentException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("visualEditor.galleryInvalid"));
        }
    }

    /** 画廊图片行 JSON（{@code [{file,caption}]}）。 */
    private List<VisualEditorService.GalleryItem> parseGalleryItems(String json) {
        if (Strings.trimToEmpty(json).isEmpty()) {
            return List.of();
        }
        List<?> raw;
        try {
            raw = objectMapper.readValue(json, List.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("gallery items", exception);
        }
        List<VisualEditorService.GalleryItem> items = new java.util.ArrayList<>();
        for (Object value : raw) {
            if (value instanceof Map<?, ?> row) {
                items.add(new VisualEditorService.GalleryItem(text(row.get("file")), text(row.get("caption"))));
            }
        }
        return items;
    }

    /** 画廊属性 JSON（{@code [{name,value}]}）。 */
    private List<VisualEditorService.GalleryAttribute> parseGalleryAttributes(String json) {
        if (Strings.trimToEmpty(json).isEmpty()) {
            return List.of();
        }
        List<?> raw;
        try {
            raw = objectMapper.readValue(json, List.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("gallery attributes", exception);
        }
        List<VisualEditorService.GalleryAttribute> attributes = new java.util.ArrayList<>();
        for (Object value : raw) {
            if (value instanceof Map<?, ?> row) {
                attributes.add(new VisualEditorService.GalleryAttribute(
                        text(row.get("name")), text(row.get("value"))));
            }
        }
        return attributes;
    }

    /** 参数 JSON（{@code [{name,value,positional,present}]}）→ 服务层参数列表。 */
    private List<VisualEditorService.TemplateParam> parseParams(String paramsJson) {
        List<VisualEditorService.TemplateParam> params = new java.util.ArrayList<>();
        List<?> raw;
        try {
            raw = objectMapper.readValue(paramsJson, List.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("params", exception);
        }
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            params.add(new VisualEditorService.TemplateParam(
                    text(row.get("name")), text(row.get("value")),
                    Boolean.TRUE.equals(row.get("positional")),
                    Boolean.TRUE.equals(row.get("present")), false));
        }
        return params;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 权限错误：{@code code} 对应 401（未登录）或 403（其它）。 */
    private record Denied(AjaxCode code, String message) {
    }

    /** 与「显示预览」一致的权限检查；返回非 null 表示错误。 */
    private Denied permissionError(String title, HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return new Denied(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        Optional<WikiBlock> block = blockService.currentBlock(user);
        if (block.isPresent()) {
            return new Denied(AjaxCode.FORBIDDEN, blockService.notice(block.get()));
        }
        String normalized = Strings.trimToEmpty(title);
        if (normalized.isEmpty()) {
            return new Denied(AjaxCode.BAD_REQUEST, message("error.titleRequired"));
        }
        WikiPage existing = wikiPageService.getByTitle(normalized).orElse(null);
        AccessAction action = existing == null ? AccessAction.CREATE : AccessAction.EDIT;
        if (!accessControlService.can(action, normalized, user)) {
            return new Denied(AjaxCode.FORBIDDEN,
                    message(existing == null ? "error.createForbidden" : "error.editForbidden"));
        }
        return null;
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
