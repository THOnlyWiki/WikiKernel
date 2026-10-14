package cc.thonly.wikikernel.actionapi;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.transfer.TransferTimes;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MediaWiki 兼容 Action API：{@code GET|POST /api.php?action=...}。
 * <p>
 * 支持 {@code format=json|jsonfm}、{@code formatversion=1|2}、{@code callback=}（JSONP）、
 * {@code curtimestamp=1}；错误返回 MediaWiki 风格 {@code {"error":{"code":...}}}（HTTP 200）。
 * 写操作（edit）使用会话 token（{@code action=query&meta=tokens&type=csrf}）。
 */
@RestController
public class ActionApiController {

    private static final Logger log = LoggerFactory.getLogger(ActionApiController.class);

    private final ActionApiService service;
    private final ObjectMapper objectMapper;

    public ActionApiController(ActionApiService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(value = "/api.php", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> api(@RequestParam Map<String, String> params, HttpServletRequest request) {
        int formatVersion = parseFormatVersion(params.get("formatversion"));
        String action = Strings.defaultIfBlank(params.get("action"), "help");

        ActionApiService.RequestContextHolder.set(request);
        Object root;
        try {
            root = service.handle(action, params, request, formatVersion);
        } catch (ActionApiService.ApiError error) {
            root = errorBody(error.code(), error.getMessage(), error.extra());
        } catch (RuntimeException exception) {
            log.error("Action API 处理失败：action={}", action, exception);
            root = errorBody("internal_api_error",
                    exception.getMessage() == null ? "Internal error" : exception.getMessage(), Map.of());
        } finally {
            ActionApiService.RequestContextHolder.clear();
        }

        if (root instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> original = (Map<String, Object>) raw;
            // 顶层统一拷贝成可变 LinkedHashMap（service 可能返回 Map.of() 这类不可变实现）
            Map<String, Object> map = new LinkedHashMap<>(original);
            map.putIfAbsent("servedby", "wikikernel");
            if (!Strings.isBlank(params.get("curtimestamp"))) {
                map.put("curtimestamp", TransferTimes.toUtcIso(LocalDateTime.now()));
            }
            root = map;
        }

        // format=xml：简化 XML 输出（结构可直接解析；错误对象同样适用）
        String requestedFormat = Strings.trimToEmpty(params.get("format")).toLowerCase(Locale.ROOT);
        if ("xml".equals(requestedFormat)) {
            String xml;
            try {
                xml = ActionApiXml.toXml(root);
            } catch (RuntimeException exception) {
                log.error("Action API XML 序列化失败", exception);
                xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<api><error code=\"internal_api_error\"/></api>";
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/xml;charset=UTF-8"))
                    .body(xml);
        }

        String json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (RuntimeException exception) {
            log.error("Action API 序列化失败", exception);
            json = "{\"error\":{\"code\":\"internal_api_error\",\"info\":\"serialization failed\",\"*\":\"serialization failed\"}}";
        }

        String callback = Strings.trimToEmpty(params.get("callback"));
        if (!callback.isEmpty() && callback.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/javascript;charset=UTF-8"))
                    .body(callback + "(" + json + ");");
        }
        String format = Strings.trimToEmpty(params.get("format")).toLowerCase(Locale.ROOT);
        if ("jsonfm".equals(format)) {            String html = "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"UTF-8\">"
                    + "<title>API result</title></head><body><pre>"
                    + HtmlUtils.htmlEscape(json) + "</pre></body></html>";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .body(html);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(json);
    }

    private static int parseFormatVersion(String value) {
        try {
            return Integer.parseInt(Strings.trimToEmpty(value)) == 2 ? 2 : 1;
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private static Map<String, Object> errorBody(String code, String info, Map<String, Object> extra) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("info", info == null ? "" : info);
        if (extra != null) {
            error.putAll(extra);
        }
        error.put("*", info == null ? "" : info);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", error);
        return root;
    }
}
