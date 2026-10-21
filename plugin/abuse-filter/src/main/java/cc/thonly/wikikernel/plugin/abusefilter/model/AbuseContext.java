package cc.thonly.wikikernel.plugin.abusefilter.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 滥用过滤器的求值上下文：变量名 → 值（字符串 / 数字 / 布尔 / 列表）。
 * <p>
 * 变量名与 MediaWiki AbuseFilter 保持一致（snake_case），见 {@link #KNOWN_VARIABLES}；
 * 保存过滤器时按这个集合做语法检查（未知变量会报错）。
 */
public final class AbuseContext {

    /** 已知变量（保存过滤器时校验；命中日志里展示的就是这些值）。 */
    public static final Set<String> KNOWN_VARIABLES = Set.of(
            // 动作 / 页面
            "action", "page_id", "page_title", "page_namespace", "page_is_new",
            "article_articleid", "article_namespace",
            "article_text", "article_prefixedtext", "page_prefixedtitle", "page_age",
            "page_restrictions_edit", "page_restrictions_move", "page_restrictions_create",
            "page_restrictions_upload", "article_restrictions_edit", "article_restrictions_move",
            "article_restrictions_create", "article_restrictions_upload",
            "page_recent_contributors", "page_first_contributor",
            "article_recent_contributors", "article_first_contributor",
            // 用户
            "user_name", "user_id", "user_groups", "user_rights", "user_type",
            "user_editcount", "user_age",
            "user_emailconfirm", "user_blocked", "user_autoconfirmed", "ip", "user_unnamed_ip",
            // 编辑内容
            "summary", "minor_edit", "old_wikitext", "new_wikitext",
            "old_size", "new_size", "edit_delta", "edit_diff",
            "added_lines", "removed_lines", "added_lines_pst",
            "all_links", "old_links", "added_links", "removed_links",
            "new_text", "new_html", "new_pst", "edit_diff_pst",
            "old_content_model", "new_content_model",
            // 其它动作
            "moved_from", "moved_to", "moved_from_title", "moved_to_title",
            "moved_from_text", "moved_to_text",
            "moved_from_prefixedtext", "moved_to_prefixedtext",
            "moved_from_articleid", "moved_to_articleid",
            "moved_from_namespace", "moved_to_namespace",
            "accountname", "account_name",
            // 文件上传（MediaWiki 同名；只有上传动作会填值）
            "file_size", "file_mime", "file_sha1", "file_mediatype",
            "file_width", "file_height", "file_bits_per_channel",
            // 元信息
            "timestamp", "wiki_name", "wiki_language", "tor_exit_node");

    private final Map<String, Object> values = new LinkedHashMap<>();
    /** 过滤器内通过 {@code name := value;} 定义的局部变量（不进入命中日志快照）。 */
    private final Map<String, Object> locals = new LinkedHashMap<>();

    /** 写入一个变量（可链式）。 */
    public AbuseContext put(String name, Object value) {
        values.put(name, value);
        return this;
    }

    /** 写入过滤器局部变量（可链式）；同名局部变量会覆盖全局变量。 */
    public AbuseContext putLocal(String name, Object value) {
        locals.put(name, value);
        return this;
    }

    /** 读取变量 / 局部变量；未写入返回 null。 */
    public Object get(String name) {
        return locals.containsKey(name) ? locals.get(name) : values.get(name);
    }

    /** 清空过滤器局部变量（每次独立求值前调用，避免一个过滤器的赋值泄漏到下一个）。 */
    public AbuseContext clearLocals() {
        locals.clear();
        return this;
    }

    /** 变量快照（日志展示 / JSON 序列化用）。 */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(values);
    }
}
