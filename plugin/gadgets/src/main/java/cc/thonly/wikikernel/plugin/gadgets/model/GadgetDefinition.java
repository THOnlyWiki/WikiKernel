package cc.thonly.wikikernel.plugin.gadgets.model;

import java.util.List;

/**
 * 一个 Gadget 的定义（{@code WikiKernel:Gadgets-definition} 里的一行）。
 *
 * @param name          名称（唯一，如 {@code DarkMode}）
 * @param description   说明（定义行里的文字列或 {@code description=…}）
 * @param scripts       JS 资源名（相对名如 {@code Gadget-DarkMode.js}，或完整标题 {@code 小部件:X.js}）
 * @param styles        CSS 资源名
 * @param dependencies  声明的模块依赖（{@code dependencies=oojs-ui-core,mediawiki.api}）
 * @param rights        可见 / 可加载的用户组（空 = 所有人）
 * @param hidden        是否隐藏（不出现在个人设置里）
 * @param defaultEnabled 是否默认对所有用户启用
 */
public record GadgetDefinition(String name, String description, List<String> scripts, List<String> styles,
                               List<String> dependencies, List<String> rights, boolean hidden,
                               boolean defaultEnabled) {

    public GadgetDefinition {
        scripts = scripts == null ? List.of() : List.copyOf(scripts);
        styles = styles == null ? List.of() : List.copyOf(styles);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        rights = rights == null ? List.of() : List.copyOf(rights);
    }
}
