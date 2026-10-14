package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 页面显示钩子的 core 侧调度：把插件的 {@link PageDisplayHook} 接进词条阅读视图。
 * <ul>
 *     <li>{@link #renderBody} / {@link #renderMissingPage}：认领整页正文（图表: / 微件: 这类非 wikitext 页）
 *     或**不存在的标题**（审核的「待审创建」页），第一个认领的生效；</li>
 *     <li>{@link #beforeTitle} / {@link #beforeContent} / {@link #afterContent} / {@link #headItems}：
 *     标题上方 / 正文之前（提示条）/ 正文下方（评论区）/ 该页额外的 head 片段。</li>
 * </ul>
 * 容错：单个插件抛异常时只记警告并跳过它（页面照常渲染，不会 500）；
 * 没有任何插件注册时所有方法都返回空（列表为 {@code List.of()}，认领为 {@code null}），页面与迁移前完全一致。
 */
@Component
public class PageDisplayHookService {

    private static final Logger log = LoggerFactory.getLogger(PageDisplayHookService.class);

    private final PluginManager pluginManager;

    public PageDisplayHookService(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** 认领整页正文（返回 {@code null} 表示没有插件认领）。 */
    public String renderBody(PageDisplayContext context) {
        return firstClaim(context, true);
    }

    /** 认领「不存在的标题」的显示（审核的「待审创建」页；返回 {@code null} 表示照常 404）。 */
    public String renderMissingPage(PageDisplayContext context) {
        return firstClaim(context, false);
    }

    /** 标题上方的片段（页面横幅等）。 */
    public List<String> beforeTitle(PageDisplayContext context) {
        return fragments(context, FragmentSlot.BEFORE_TITLE);
    }

    /** 正文之前的片段（提示条等）。 */
    public List<String> beforeContent(PageDisplayContext context) {
        return fragments(context, FragmentSlot.BEFORE_CONTENT);
    }

    /** 正文下方的片段（评论区、页脚等）。 */
    public List<String> afterContent(PageDisplayContext context) {
        return fragments(context, FragmentSlot.AFTER_CONTENT);
    }

    /** 该页额外的 {@code <head>} 片段（脚本 / 样式）。 */
    public List<String> headItems(PageDisplayContext context) {
        List<String> items = new ArrayList<>();
        for (PageDisplayHook hook : sortedHooks()) {
            try {
                List<String> headItems = hook.headItems(context);
                if (headItems != null) {
                    items.addAll(headItems);
                }
            } catch (RuntimeException exception) {
                warn(hook, exception);
            }
        }
        return List.copyOf(items);
    }

    /** 片段槽位：标题上方 / 正文之前 / 正文下方。 */
    private enum FragmentSlot {
        BEFORE_TITLE,
        BEFORE_CONTENT,
        AFTER_CONTENT
    }

    private List<String> fragments(PageDisplayContext context, FragmentSlot slot) {
        List<String> fragments = new ArrayList<>();
        for (PageDisplayHook hook : sortedHooks()) {
            try {
                String html = switch (slot) {
                    case BEFORE_TITLE -> hook.beforeTitle(context);
                    case BEFORE_CONTENT -> hook.beforeContent(context);
                    case AFTER_CONTENT -> hook.afterContent(context);
                };
                if (html != null && !html.isBlank()) {
                    fragments.add(html);
                }
            } catch (RuntimeException exception) {
                warn(hook, exception);
            }
        }
        return List.copyOf(fragments);
    }

    private String firstClaim(PageDisplayContext context, boolean body) {
        for (PageDisplayHook hook : sortedHooks()) {
            try {
                String html = body ? hook.renderBody(context) : hook.renderMissingPage(context);
                if (html != null) {
                    return html;
                }
            } catch (RuntimeException exception) {
                warn(hook, exception);
            }
        }
        return null;
    }

    /** 按 {@link PageDisplayHook#order()} 升序的页面显示钩子。 */
    private List<PageDisplayHook> sortedHooks() {
        return this.pluginManager.extensions(PageDisplayHook.class).stream()
                .sorted(Comparator.comparingInt(PageDisplayHook::order))
                .toList();
    }

    private static void warn(PageDisplayHook hook, RuntimeException exception) {
        log.warn("页面显示钩子执行失败（已跳过）: {}", hook.getClass().getName(), exception);
    }
}
