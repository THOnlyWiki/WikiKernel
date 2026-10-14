package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.data.WikiUser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 特殊页面请求。
 *
 * @param name       去前缀后的页面名（如 "全部页面"）
 * @param parameters 查询参数
 * @param user       当前登录用户（未登录为 null；特殊页可用它做权限判断）
 */
public record SpecialPageRequest(String name, Map<String, String> parameters, WikiUser user) {

    public SpecialPageRequest {
        name = name == null ? "" : name;
        parameters = Collections.unmodifiableMap(
                new LinkedHashMap<>(parameters == null ? Map.of() : parameters));
    }

    /** 兼容旧用法：不带当前用户（等价于未登录）。 */
    public SpecialPageRequest(String name, Map<String, String> parameters) {
        this(name, parameters, null);
    }
}
