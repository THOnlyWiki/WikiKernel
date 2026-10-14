package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.extension.PersonalToolbarContext;
import cc.thonly.wikikernel.common.extension.PersonalToolbarHook;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 顶栏个人工具区钩子的 core 侧调度：把插件的 {@link PersonalToolbarHook} 收集成
 * {@code <li>…</li>} 片段列表（按 {@code order()} 升序），供 {@code fragments/personal-tools.html} 渲染。
 * <p>
 * 容错：单个插件抛异常时只记警告并跳过它，页面照常渲染（不会 500）；
 * 没有任何插件注册时返回空列表，个人工具区与迁移前完全一致。
 */
@Component
public class PersonalToolbarHookService {

    private static final Logger log = LoggerFactory.getLogger(PersonalToolbarHookService.class);

    private final PluginManager pluginManager;

    public PersonalToolbarHookService(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** 个人工具区的额外条目 HTML（按 order 升序，已过滤空白）。 */
    public List<String> itemsHtml(PersonalToolbarContext context) {
        List<String> items = new ArrayList<>();
        for (PersonalToolbarHook hook : sortedHooks()) {
            try {
                String html = Strings.trimToEmpty(hook.itemsHtml(context));
                if (!html.isEmpty()) {
                    items.add(html);
                }
            } catch (RuntimeException exception) {
                log.warn("个人工具区钩子执行失败（已跳过）: {}", hook.getClass().getName(), exception);
            }
        }
        return List.copyOf(items);
    }

    /** 按 order 升序的个人工具区钩子。 */
    private List<PersonalToolbarHook> sortedHooks() {
        return this.pluginManager.extensions(PersonalToolbarHook.class).stream()
                .sorted(Comparator.comparingInt(PersonalToolbarHook::order))
                .toList();
    }
}
