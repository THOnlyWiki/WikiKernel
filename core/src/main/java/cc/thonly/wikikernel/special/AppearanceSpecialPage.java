package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Skin;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.skin.ActiveSkin;
import cc.thonly.wikikernel.skin.SkinRegistry;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:外观} / {@code Special:Appearance}：选择皮肤（用户偏好，存 {@code wiki_user.skin}）。
 * <p>
 * 列表可在当前皮肤下直接「预览」（{@code ?useskin=id}，临时、不保存）；保存由
 * {@code /js/skin-preference.js} 提交到 {@code POST /api/user/skin}。
 */
@Component
public class AppearanceSpecialPage implements SpecialPage {

    private final SkinRegistry skinRegistry;
    private final MessageSource messageSource;

    public AppearanceSpecialPage(SkinRegistry skinRegistry, MessageSource messageSource) {
        this.skinRegistry = skinRegistry;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "外观";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.appearance", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Appearance");
    }

    @Override
    public String template() {
        return "special/appearance";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        String selected = user == null ? "" : Strings.trimToEmpty(user.getSkin());
        ActiveSkin active = skinRegistry.view(
                skinRegistry.active(request.parameters().get("useskin"), user));

        model.put("loginRequired", user == null);
        model.put("selected", selected);
        model.put("activeSkinId", active.id());
        model.put("defaultSkinId", skinRegistry.defaultSkinId());
        model.put("skins", skinRegistry.available().stream()
                .map(skin -> {
                    ActiveSkin view = skinRegistry.view(skin);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", skin.id());
                    row.put("name", view.name());
                    row.put("description", view.description());
                    row.put("isDefault", skin.id().equals(skinRegistry.defaultSkinId()));
                    return row;
                })
                .toList());
    }
}
