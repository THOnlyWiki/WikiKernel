package cc.thonly.wikikernel.skin;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Skin;
import cc.thonly.wikikernel.config.SkinProperties;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 皮肤注册表：汇总 core Bean 与插件注册的 {@link Skin}，按配置过滤与排序，并解析当前请求生效的皮肤。
 * <p>
 * 选择顺序：{@code ?useskin=id}（临时预览）→ 登录用户的皮肤偏好 → {@code wikikernel.skins.default-skin}
 * → 内置 {@code fallback}。{@code fallback} 永远可用（禁用配置会被忽略）。
 */
@Component
public class SkinRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkinRegistry.class);

    private final List<Skin> coreSkins;
    private final PluginManager pluginManager;
    private final SkinProperties properties;
    private final MessageSource messageSource;

    public SkinRegistry(List<Skin> coreSkins,
                        PluginManager pluginManager,
                        SkinProperties properties,
                        MessageSource messageSource) {
        this.coreSkins = coreSkins;
        this.pluginManager = pluginManager;
        this.properties = properties;
        this.messageSource = messageSource;
    }

    /** 全部可用皮肤（禁用的排除、按 order 排序、core 优先去重；fallback 始终在）。 */
    public List<Skin> available() {
        Map<String, Skin> byId = new LinkedHashMap<>();
        for (Skin skin : coreSkins) {
            byId.putIfAbsent(skin.id(), skin);
        }
        for (Skin skin : pluginManager.extensions(Skin.class)) {
            if (byId.putIfAbsent(skin.id(), skin) != null) {
                log.warn("皮肤 id 重复，忽略插件皮肤: {}（{}）", skin.id(), skin.getClass().getName());
            }
        }
        List<Skin> result = new ArrayList<>();
        for (Skin skin : byId.values()) {
            if (isDisabled(skin.id())) {
                continue;
            }
            result.add(skin);
        }
        result.sort(Comparator.comparingInt(Skin::order).thenComparing(Skin::id));
        return result;
    }

    /** 按 id 找可用皮肤（禁用的返回空）。 */
    public Optional<Skin> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return available().stream().filter(skin -> skin.id().equals(id.trim())).findFirst();
    }

    /** fallback 不允许禁用。 */
    public boolean isDisabled(String skinId) {
        return !FallbackSkin.ID.equals(skinId) && properties.getDisabled().contains(skinId);
    }

    /** 当前请求生效的皮肤。 */
    public Skin active(HttpServletRequest request) {
        return active(request.getParameter("useskin"), UserSession.current(request));
    }

    /** 当前生效皮肤（供没有 HttpServletRequest 的场景，如特殊页）：?useskin= 临时预览 → 用户偏好 → 默认 → fallback。 */
    public Skin active(String useskin, WikiUser user) {
        Optional<Skin> preview = byId(useskin);
        if (preview.isPresent()) {
            return preview.get();
        }
        if (user != null) {
            Optional<Skin> preferred = byId(user.getSkin());
            if (preferred.isPresent()) {
                return preferred.get();
            }
        }
        return byId(properties.getDefaultSkin())
                .orElseGet(() -> byId(FallbackSkin.ID).orElseThrow(
                        () -> new IllegalStateException("内置 fallback 皮肤缺失")));
    }

    /** 皮肤来源：core（内置）或 plugin（插件注册，见 特殊:版本）。 */
    public String sourceOf(Skin skin) {
        return coreSkins.contains(skin) ? "core" : "plugin";
    }

    /** 站点默认皮肤 id（配置不可用时回退 fallback）。 */
    public String defaultSkinId() {
        return byId(properties.getDefaultSkin()).map(Skin::id).orElse(FallbackSkin.ID);
    }

    /** 当前请求生效皮肤的模板视图。 */
    public ActiveSkin activeView(HttpServletRequest request) {
        return view(active(request));
    }

    /** 皮肤展示信息（名称 / 说明走 {@code skin.<id>.name|description} 消息键，带默认值兜底）。 */
    public ActiveSkin view(Skin skin) {
        return new ActiveSkin(skin.id(), skin.layout(), skin.stylesheets(), skin.scripts(),
                skin.logoUrl(),
                message("skin." + skin.id() + ".name", skin.displayName()),
                message("skin." + skin.id() + ".description", skin.description()),
                skin.version());
    }

    private String message(String key, String fallback) {
        return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
