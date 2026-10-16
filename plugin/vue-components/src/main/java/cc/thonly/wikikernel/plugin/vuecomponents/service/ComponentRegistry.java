package cc.thonly.wikikernel.plugin.vuecomponents.service;

import cc.thonly.wikikernel.common.service.PageQueryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 组件注册表：**标签名 → 页面标题**（{@code test-component.vue} → {@code test-component}）。
 * <p>
 * 发现规则（两条并行，各取所需）：
 * <ol>
 *     <li>{@code 组件:} 命名空间下的 {@code *.vue} 页面（前缀查询，便宜、权威；同名时优先用它）；</li>
 *     <li>其它命名空间的 {@code *.vue} 页面（全站列表 + 后缀过滤；有上限与缓存）。</li>
 * </ol>
 * 缓存 {@link #TTL_MILLIS} 毫秒；页面保存（{@link cc.thonly.wikikernel.plugin.vuecomponents.hook.VueEditGuard}）
 * 时立即失效，所以「新建组件 → 立即可用」。
 * <p>
 * 标签名 = 文件名去掉 {@code .vue} 后转 kebab-case（{@code TestComponent.vue} → {@code test-component}），
 * 与写在正文里的 {@code <test-component />} 一致（大小写不敏感）。
 */
@Component
public class ComponentRegistry {

    /** 缓存时长：短 TTL 兜底（保存钩子会提前失效，正常情况用不到）。 */
    private static final long TTL_MILLIS = 15_000;

    /** 全站扫描的页面上限（只有 {@code *.vue} 后缀的会被收进来）。 */
    private static final int SCAN_LIMIT = 5_000;

    private static final String COMPONENT_NAMESPACE = "组件:";
    private static final String SUFFIX = ".vue";

    private final PageQueryStore pageQueryStore;

    private volatile Map<String, String> tagToTitle = Map.of();
    private volatile long refreshedAt = 0L;

    @Autowired
    public ComponentRegistry(PageQueryStore pageQueryStore) {
        this.pageQueryStore = pageQueryStore;
    }

    /** 组件标签名 → 页面标题（小写标签名；缓存过期时刷新）。 */
    public Map<String, String> components() {
        long now = System.currentTimeMillis();
        if (now - refreshedAt > TTL_MILLIS) {
            refresh();
        }
        return tagToTitle;
    }

    /** 全部组件标签名（供 {@code ParserTag#names()} 用；顺序稳定）。 */
    public List<String> tagNames() {
        return List.copyOf(components().keySet());
    }

    /** 标签名（大小写不敏感）→ 页面标题；没有这个组件返回 {@code null}。 */
    public String titleOf(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        return components().get(tag.trim().toLowerCase(Locale.ROOT));
    }

    /** 让缓存立即失效（页面保存 / 删除 {@code *.vue} 页面后调用）。 */
    public synchronized void invalidate() {
        refreshedAt = 0L;
    }

    private synchronized void refresh() {
        Map<String, String> found = new LinkedHashMap<>();
        // 1) 组件: 命名空间（前缀查询，同名优先）
        try {
            for (PageQueryStore.PageEntry entry : pageQueryStore.list(COMPONENT_NAMESPACE, "all", 500)) {
                put(found, entry.title(), true);
            }
        } catch (RuntimeException ignored) {
            // 命名空间查询失败不影响其它来源
        }
        // 2) 其它命名空间：全站列表 + 后缀过滤（上限保护）
        try {
            for (PageQueryStore.PageEntry entry : pageQueryStore.list("", "all", SCAN_LIMIT)) {
                put(found, entry.title(), false);
            }
        } catch (RuntimeException ignored) {
            // 列表失败时保留已发现的部分
        }
        tagToTitle = Map.copyOf(found);
        refreshedAt = System.currentTimeMillis();
    }

    private static void put(Map<String, String> target, String title, boolean authoritative) {
        if (title == null || !title.toLowerCase(Locale.ROOT).endsWith(SUFFIX)) {
            return;
        }
        String tag = tagOf(title);
        if (tag.isEmpty()) {
            return;
        }
        if (authoritative) {
            target.put(tag, title);
        } else {
            target.putIfAbsent(tag, title);
        }
    }

    /** 页面标题 → 标签名：去掉命名空间与 {@code .vue}，转 kebab-case（小写）。 */
    public static String tagOf(String title) {
        String name = title == null ? "" : title.trim();
        int colon = name.lastIndexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(SUFFIX)) {
            name = name.substring(0, name.length() - SUFFIX.length());
        }
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int index = 0; index < name.length(); index++) {
            char ch = name.charAt(index);
            if (Character.isUpperCase(ch)) {
                boolean previousLower = index > 0 && (Character.isLowerCase(name.charAt(index - 1))
                        || Character.isDigit(name.charAt(index - 1)));
                if (previousLower && out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                    out.append('-');
                }
                out.append(Character.toLowerCase(ch));
            } else if (ch == '_' || ch == ' ') {
                out.append('-');
            } else {
                out.append(Character.toLowerCase(ch));
            }
        }
        return out.toString();
    }
}
