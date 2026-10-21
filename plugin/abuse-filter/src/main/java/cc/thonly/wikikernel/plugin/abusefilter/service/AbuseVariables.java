package cc.thonly.wikikernel.plugin.abusefilter.service;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.abusefilter.model.AbuseFilterTitles;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动作 → 过滤变量（{@code action} / {@code page_title} / {@code added_lines} …）的构造。
 * <p>
 * 从 core 的 {@code AbuseFilterServiceImpl#buildEditContext} 等原样移植：
 * 各动作只填自己的变量，其余变量（{@code user_*} / {@code ip} / {@code timestamp} / {@code wiki_name}）
 * 由 {@link AbuseFilterService} 在求值前统一补齐。
 */
public final class AbuseVariables {

    private static final Pattern LINK_PATTERN = Pattern.compile("\\[\\[([^\\]|#<>]+)");

    private AbuseVariables() {
    }

    /** 编辑 / 创建页面的变量（{@code action=edit}，与迁移前一致：创建也用 edit + {@code page_is_new}）。 */
    public static Map<String, Object> edit(long pageId, String title, boolean newPage, String oldText,
                                           String newText, String summary, boolean minor) {
        Map<String, Object> variables = new LinkedHashMap<>();
        putTitleVariables(variables, title, pageId);
        variables.put("action", "edit");
        variables.put("page_is_new", newPage || pageId <= 0);
        variables.put("summary", Strings.trimToEmpty(summary));
        variables.put("minor_edit", minor);
        variables.put("old_wikitext", oldText == null ? "" : oldText);
        variables.put("new_wikitext", newText == null ? "" : newText);
        int oldSize = byteSize(oldText);
        int newSize = byteSize(newText);
        variables.put("old_size", (long) oldSize);
        variables.put("new_size", (long) newSize);
        variables.put("edit_delta", (long) newSize - oldSize);
        variables.put("edit_diff", "");
        variables.put("added_lines", addedLines(oldText, newText));
        variables.put("removed_lines", removedLines(oldText, newText));
        variables.put("added_lines_pst", addedLines(oldText, newText));
        variables.put("all_links", links(newText));
        variables.put("old_links", links(oldText));
        variables.put("added_links", addedLinks(oldText, newText));
        variables.put("removed_links", removedLinks(oldText, newText));
        variables.put("new_text", newText == null ? "" : newText);
        variables.put("new_html", "");
        variables.put("new_pst", newText == null ? "" : newText);
        variables.put("edit_diff_pst", "");
        variables.put("old_content_model", "wikitext");
        variables.put("new_content_model", "wikitext");
        return variables;
    }

    /** 移动页面的变量（{@code action=move}）。 */
    public static Map<String, Object> move(String from, String to) {
        Map<String, Object> variables = new LinkedHashMap<>();
        putTitleVariables(variables, from, 0L);
        long fromNamespace = AbuseFilterTitles.namespaceId(from);
        long toNamespace = AbuseFilterTitles.namespaceId(to);
        String fromText = AbuseFilterTitles.titleOnly(from);
        String toText = AbuseFilterTitles.titleOnly(to);
        variables.put("action", "move");
        variables.put("page_is_new", false);
        variables.put("moved_from", fromText);
        variables.put("moved_to", toText);
        variables.put("moved_from_title", fromText);
        variables.put("moved_to_title", toText);
        variables.put("moved_from_text", fromText);
        variables.put("moved_to_text", toText);
        variables.put("moved_from_prefixedtext", Strings.trimToEmpty(from));
        variables.put("moved_to_prefixedtext", Strings.trimToEmpty(to));
        variables.put("moved_from_articleid", 0L);
        variables.put("moved_to_articleid", 0L);
        variables.put("moved_from_namespace", fromNamespace);
        variables.put("moved_to_namespace", toNamespace);
        variables.put("summary", "");
        return variables;
    }

    /** 注册账号的变量（{@code action=createaccount}）。 */
    public static Map<String, Object> createAccount(String username) {
        Map<String, Object> variables = new LinkedHashMap<>();
        putTitleVariables(variables, username, 0L);
        variables.put("action", "createaccount");
        variables.put("page_is_new", true);
        variables.put("accountname", Strings.trimToEmpty(username));
        variables.put("account_name", Strings.trimToEmpty(username));
        variables.put("summary", "");
        return variables;
    }

    /**
     * 文件上传的变量（{@code action=upload}）：
     * {@code page_title} = 文件名（去「文件:」前缀），{@code file_size} / {@code file_mime} 是文件信息。
     */
    public static Map<String, Object> upload(String fileName, long size, String contentType, String description) {
        Map<String, Object> variables = new LinkedHashMap<>();
        putTitleVariables(variables, fileName, 0L);
        variables.put("action", "upload");
        variables.put("page_is_new", true);
        variables.put("summary", Strings.trimToEmpty(description));
        variables.put("file_size", size);
        variables.put("file_mime", Strings.trimToEmpty(contentType));
        variables.put("file_mediatype", Strings.trimToEmpty(contentType));
        variables.put("file_sha1", "");
        variables.put("file_width", 0L);
        variables.put("file_height", 0L);
        variables.put("file_bits_per_channel", 8L);
        return variables;
    }

    /** 文件删除的变量（{@code action=delete}）。 */
    public static Map<String, Object> deleteFile(String fileName) {
        Map<String, Object> variables = new LinkedHashMap<>();
        putTitleVariables(variables, fileName, 0L);
        variables.put("action", "delete");
        variables.put("page_is_new", false);
        variables.put("summary", "");
        return variables;
    }

    /** 页面保护的变量（{@code action=protect}）。 */
    public static Map<String, Object> protect(String title, boolean protectedFlag) {
        Map<String, Object> variables = new LinkedHashMap<>();
        putTitleVariables(variables, title, 0L);
        variables.put("action", "protect");
        variables.put("page_is_new", false);
        variables.put("summary", String.valueOf(protectedFlag));
        return variables;
    }

    /** 封禁的变量（{@code action=block}）：{@code page_title} = 被封禁的用户名 / IP。 */
    public static Map<String, Object> block(String target, String reason) {
        Map<String, Object> variables = new LinkedHashMap<>();
        putTitleVariables(variables, target, 0L);
        variables.put("action", "block");
        variables.put("page_is_new", false);
        variables.put("summary", Strings.trimToEmpty(reason));
        return variables;
    }

    /** 标题 / 页面 ID / 命名空间，同时补齐 MediaWiki 新旧名称（article_* / page_*）。 */
    private static void putTitleVariables(Map<String, Object> variables, String title, long pageId) {
        String prefixed = Strings.trimToEmpty(title);
        String titleOnly = AbuseFilterTitles.titleOnly(prefixed);
        long namespace = AbuseFilterTitles.namespaceId(prefixed);
        variables.put("page_id", pageId);
        variables.put("article_articleid", pageId);
        variables.put("page_title", titleOnly);
        variables.put("article_text", titleOnly);
        variables.put("page_namespace", namespace);
        variables.put("article_namespace", namespace);
        variables.put("page_prefixedtitle", prefixed);
        variables.put("article_prefixedtext", prefixed);
        variables.put("page_age", 0L);
        variables.put("page_restrictions_edit", List.of());
        variables.put("page_restrictions_move", List.of());
        variables.put("page_restrictions_create", List.of());
        variables.put("page_restrictions_upload", List.of());
        variables.put("article_restrictions_edit", "");
        variables.put("article_restrictions_move", "");
        variables.put("article_restrictions_create", "");
        variables.put("article_restrictions_upload", "");
        variables.put("page_recent_contributors", List.of());
        variables.put("article_recent_contributors", List.of());
        variables.put("page_first_contributor", "");
        variables.put("article_first_contributor", "");
    }

    // ==================== 工具（与迁移前 core 的实现一致） ====================

    private static List<String> lines(String text) {
        String value = text == null ? "" : text;
        if (value.isEmpty()) {
            return List.of();
        }
        return List.of(value.split("\\R", -1));
    }

    static List<String> addedLines(String oldText, String newText) {
        Set<String> before = new HashSet<>(lines(oldText));
        return lines(newText).stream()
                .filter(line -> !line.isBlank() && !before.contains(line))
                .distinct()
                .toList();
    }

    static List<String> removedLines(String oldText, String newText) {
        Set<String> after = new HashSet<>(lines(newText));
        return lines(oldText).stream()
                .filter(line -> !line.isBlank() && !after.contains(line))
                .distinct()
                .toList();
    }

    private static List<String> links(String text) {
        List<String> links = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            if (!target.isEmpty() && !links.contains(target)) {
                links.add(target);
            }
        }
        return links;
    }

    static List<String> addedLinks(String oldText, String newText) {
        List<String> before = links(oldText);
        return links(newText).stream().filter(link -> !before.contains(link)).toList();
    }

    static List<String> removedLinks(String oldText, String newText) {
        List<String> after = links(newText);
        return links(oldText).stream().filter(link -> !after.contains(link)).toList();
    }

    static int byteSize(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
