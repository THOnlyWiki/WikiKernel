package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.wikitext.HtmlSanitizer;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code 特殊:替换文本?search=查找&replace=替换} / {@code Special:ReplaceText}：管理员在全部页面正文里查找并替换文本
 * （literal 或正则；仿 MediaWiki 的 ReplaceText 扩展）。
 * <p>
 * 本页只做「查找 + 预览」（GET，查询条件保留在 URL 里）；真正的替换由 {@code /js/replace-text.js}
 * 提交到 {@code POST /api/replace-text/apply}。无管理员 / 行政员权限时只显示提示、不渲染表单。
 */
@Component
public class ReplaceTextSpecialPage implements SpecialPage {

    /** 单次最多扫描多少行页面（标题升序取前 N 个）。 */
    public static final int MAX_SCAN = 1000;

    /** 模板里最多展示多少条匹配结果。 */
    private static final int MAX_RESULTS = 200;

    /** 预览：从第一个匹配位置前后各取多少个字。 */
    private static final int PREVIEW_RADIUS = 30;

    /** 查询参数名（模板 / replace-text.js 共用）。 */
    public static final String PARAM_SEARCH = "search";
    public static final String PARAM_REPLACE = "replace";
    public static final String PARAM_REGEX = "regex";
    public static final String PARAM_PREFIX = "prefix";

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public ReplaceTextSpecialPage(WikiPageMapper wikiPageMapper,
                                  WikiPageService wikiPageService,
                                  AccessControlService accessControlService,
                                  PermissionService permissionService,
                                  MessageSource messageSource) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "替换文本";
    }

    @Override
    public String displayName() {
        return message("special.replaceText");
    }

    @Override
    public List<String> aliases() {
        return List.of("ReplaceText");
    }

    @Override
    public String category() {
        return SpecialPageCategories.MAINTENANCE;
    }

    @Override
    public String template() {
        return "special/replace-text";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        boolean canManage = user != null && permissionService.canManage(user.getId());
        model.put("canManage", canManage);

        Map<String, String> parameters = request.parameters();
        String search = Strings.trimToEmpty(parameters.get(PARAM_SEARCH));
        String replace = Strings.trimToEmpty(parameters.get(PARAM_REPLACE));
        boolean regex = isOn(parameters.get(PARAM_REGEX));
        String prefix = Strings.trimToEmpty(parameters.get(PARAM_PREFIX)).trim();
        model.put("search", search);
        model.put("replace", replace);
        model.put("regex", regex);
        model.put("prefix", prefix);
        model.put("maxScan", MAX_SCAN);
        model.put("searched", false);
        if (!canManage || search.isEmpty()) {
            return; // 无权限只显示提示；未输入查找内容时只显示查找表单
        }

        Pattern pattern = null;
        if (regex) {
            try {
                pattern = Pattern.compile(search);
            } catch (PatternSyntaxException exception) {
                model.put("regexError", exception.getMessage());
                return; // 提示 replaceText.invalidRegex，不再继续遍历
            }
        }

        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .likeRight(!prefix.isEmpty(), WikiPage::getTitle, prefix) // SQL：title LIKE '前缀%'
                .orderByAsc(WikiPage::getTitle)
                .last("LIMIT " + MAX_SCAN));

        List<Map<String, Object>> results = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, user)) {
            if (results.size() >= MAX_RESULTS) {
                break;
            }
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            if (content.isEmpty()) {
                continue;
            }
            Map<String, Object> result = match(content, search, replace, pattern);
            if (result == null) {
                continue; // 无匹配（或替换串引用了不存在的分组）
            }
            result.put("title", page.getTitle());
            results.add(result);
        }
        model.put("results", results);
        model.put("resultCount", results.size());
        model.put("searched", true);
    }

    /**
     * 统计一页的匹配数并生成「替换后」预览；无匹配返回 null。
     * <p>
     * literal 用 {@link String#indexOf(String, int)} 循环计数，正则用 {@link Matcher#find()}；
     * 预览取替换后文本里第一个匹配位置前后各 {@link #PREVIEW_RADIUS} 字（第一个匹配之前的内容不受替换影响，
     * 所以匹配起点在替换前后一致），转义成纯文本交给模板。
     */
    private Map<String, Object> match(String content, String search, String replace, Pattern pattern) {
        long count = 0;
        int firstMatch = -1;
        String replaced;
        if (pattern == null) {
            int from = 0;
            while (true) {
                int index = content.indexOf(search, from);
                if (index < 0) {
                    break;
                }
                if (firstMatch < 0) {
                    firstMatch = index;
                }
                count++;
                from = index + search.length();
            }
            if (count == 0) {
                return null;
            }
            replaced = content.replace(search, replace);
        } else {
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                if (firstMatch < 0) {
                    firstMatch = matcher.start();
                }
                count++;
            }
            if (count == 0) {
                return null;
            }
            try {
                replaced = pattern.matcher(content).replaceAll(replace);
            } catch (RuntimeException exception) {
                replaced = null; // 替换串里的 $n 引用了不存在的分组：只展示匹配数，预览用原文
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        result.put("preview", HtmlSanitizer.escapeText(
                preview(replaced == null ? content : replaced, firstMatch)));
        return result;
    }

    /** 取第一个匹配位置前后各 {@link #PREVIEW_RADIUS} 字。 */
    private static String preview(String text, int index) {
        if (text.isEmpty()) {
            return "";
        }
        int start = Math.max(0, index - PREVIEW_RADIUS);
        int end = Math.min(text.length(), index + PREVIEW_RADIUS);
        return text.substring(start, end);
    }

    private static boolean isOn(String value) {
        return "1".equals(value) || "on".equalsIgnoreCase(value)
                || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
