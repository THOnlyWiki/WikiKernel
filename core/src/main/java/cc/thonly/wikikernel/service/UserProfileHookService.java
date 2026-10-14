package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.extension.UserProfileContext;
import cc.thonly.wikikernel.common.extension.UserProfileHook;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 用户页资料卡钩子的 core 侧调度：把插件的 {@link UserProfileHook} 接进用户页资料卡。
 * <ul>
 *     <li>{@link #avatarUrl}：第一个返回非空头像地址的插件胜出（按 {@code order()} 升序）；</li>
 *     <li>{@link #avatarUploadUrl}：第一个返回非空上传页地址的插件胜出（没有插件时资料卡不显示该入口）；</li>
 *     <li>{@link #linksHtml}：把各插件返回的额外链接 HTML 依次拼接（按 {@code order()} 升序）；</li>
 *     <li>{@link #panelHtml}：把各插件返回的「资料卡下方、正文之前」整宽面板 HTML 依次拼接。</li>
 * </ul>
 * 容错：单个插件抛异常时只记警告并跳过它，页面照常渲染（不会 500）；
 * 没有任何插件注册时两个方法都返回空，资料卡用默认头像且没有额外链接（与迁移前一致）。
 */
@Component
public class UserProfileHookService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileHookService.class);

    private final PluginManager pluginManager;

    public UserProfileHookService(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * 用户页资料卡是否启用：任一插件返回 {@code false} → core 不渲染资料卡（缺省 true；
     * 没有插件时照常渲染内置资料卡——开关由插件自己声明，core 不需要认识它）。
     */
    public boolean userProfileEnabled() {
        for (UserProfileHook hook : sortedHooks()) {
            try {
                if (!hook.enabled()) {
                    return false;
                }
            } catch (RuntimeException exception) {
                log.warn("用户页资料卡启用判断失败（按启用处理）: {}", hook.getClass().getName(), exception);
            }
        }
        return true;
    }

    /**
     * 资料卡头像地址（没有插件接管时为 null，调用方回退默认头像）。
     */
    public String avatarUrl(UserProfileContext context) {
        for (UserProfileHook hook : sortedHooks()) {
            try {
                String url = Strings.trimToEmpty(hook.avatarUrl(context));
                if (!url.isEmpty()) {
                    return url;
                }
            } catch (RuntimeException exception) {
                log.warn("用户页资料卡头像钩子执行失败（已跳过）: {}", hook.getClass().getName(), exception);
            }
        }
        return null;
    }

    /**
     * 资料卡「贡献 | …」一行的额外链接 HTML（已按 order 拼接、过滤空白）；没有插件贡献时为空串。
     */
    public String linksHtml(UserProfileContext context) {
        StringBuilder html = new StringBuilder();
        for (UserProfileHook hook : sortedHooks()) {
            try {
                String provided = Strings.trimToEmpty(hook.linksHtml(context));
                if (!provided.isEmpty()) {
                    html.append(provided);
                }
            } catch (RuntimeException exception) {
                log.warn("用户页资料卡链接钩子执行失败（已跳过）: {}", hook.getClass().getName(), exception);
            }
        }
        return html.toString();
    }

    /**
     * 资料卡**整体** HTML（第一个返回非空的插件胜出，按 order 升序）；没有插件接管时返回 null
     * （core 回退内置资料卡）。
     */
    public String profileTopHtml(UserProfileContext context) {
        for (UserProfileHook hook : sortedHooks()) {
            try {
                String provided = Strings.trimToEmpty(hook.profileTopHtml(context));
                if (!provided.isEmpty()) {
                    return provided;
                }
            } catch (RuntimeException exception) {
                log.warn("用户页资料卡整体钩子执行失败（已跳过）: {}", hook.getClass().getName(), exception);
            }
        }
        return null;
    }

    /**
     * 资料卡头像下方「上传头像」入口的地址（第一个返回非空的插件胜出）；没有插件提供时返回 null。
     */
    public String avatarUploadUrl(UserProfileContext context) {
        for (UserProfileHook hook : sortedHooks()) {
            try {
                String url = Strings.trimToEmpty(hook.avatarUploadUrl(context));
                if (!url.isEmpty()) {
                    return url;
                }
            } catch (RuntimeException exception) {
                log.warn("用户页资料卡头像上传入口钩子执行失败（已跳过）: {}", hook.getClass().getName(), exception);
            }
        }
        return null;
    }

    /**
     * 资料卡下方、页面正文之前的整宽面板 HTML（按 order 拼接、过滤空白）；没有插件贡献时为空串。
     */
    public String panelHtml(UserProfileContext context) {
        StringBuilder html = new StringBuilder();
        for (UserProfileHook hook : sortedHooks()) {
            try {
                String provided = Strings.trimToEmpty(hook.panelHtml(context));
                if (!provided.isEmpty()) {
                    html.append(provided);
                }
            } catch (RuntimeException exception) {
                log.warn("用户页资料卡面板钩子执行失败（已跳过）: {}", hook.getClass().getName(), exception);
            }
        }
        return html.toString();
    }

    /** 按 order 升序的用户页资料卡钩子。 */    private List<UserProfileHook> sortedHooks() {
        return this.pluginManager.extensions(UserProfileHook.class).stream()
                .sorted(Comparator.comparingInt(UserProfileHook::order))
                .toList();
    }
}
