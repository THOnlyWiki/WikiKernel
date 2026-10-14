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
            // 用户
            "user_name", "user_id", "user_groups", "user_editcount", "user_age",
            "user_emailconfirm", "user_blocked", "user_autoconfirmed", "ip",
            // 编辑内容
            "summary", "minor_edit", "old_wikitext", "new_wikitext",
            "old_size", "new_size", "added_lines", "removed_lines",
            "added_links", "removed_links",
            // 其它动作
            "moved_from", "moved_to", "accountname",
            // 文件上传（MediaWiki 同名；只有上传动作会填值）
            "file_size", "file_mime",
            // 元信息
            "timestamp", "wiki_name");

    private final Map<String, Object> values = new LinkedHashMap<>();

    /** 写入一个变量（可链式）。 */
    public AbuseContext put(String name, Object value) {
        values.put(name, value);
        return this;
    }

    /** 读取变量；未写入返回 null。 */
    public Object get(String name) {
        return values.get(name);
    }

    /** 变量快照（日志展示 / JSON 序列化用）。 */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(values);
    }
}
