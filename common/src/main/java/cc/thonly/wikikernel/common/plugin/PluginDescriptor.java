package cc.thonly.wikikernel.common.plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 插件元信息（含运行期 / 客户端依赖声明）。
 *
 * @param id             插件唯一标识（建议使用 kebab-case，如 my-plugin）
 * @param name           展示名称
 * @param version        版本号
 * @param description    描述
 * @param requires       **运行期依赖**的其它插件 id（{@code plugin.requires}）：被依赖的插件先启动；
 *                       依赖缺失 / 被白名单禁用 / 启动失败 / 处于循环依赖时，本插件跳过启动（只记 WARN，不影响应用）
 * @param requiresCore   **core 版本约束**（{@code plugin.requiresCore}，如 {@code >=0.0.1}；空 = 不限制）：
 *                       不满足时本插件跳过启动（只记 WARN）
 * @param clientRequires **客户端（前端资源）依赖**的其它插件 id（{@code plugin.clientRequires}）：
 *                       只影响 {@code <head>} 注入顺序（被依赖者在前）；缺失时页面留一条 HTML 注释并记 WARN，
 *                       插件本身照常启动（前端能力降级，不影响后端）
 * @param featureKey     本插件的**功能开关**（站点设置键，{@code plugin.featureKey}；空 = 没有开关）：
 *                       声明后 core 自动在「站点设置 → 插件设置」与安装向导里渲染一个复选框，
 *                       插件自己用 {@code SiteConfig} 读同一个键——core 不需要为这个开关写任何代码
 * @param featureDefault 功能开关在 yaml / 站点设置都没写时的默认值（{@code plugin.featureDefault}；缺省 true）
 */
public record PluginDescriptor(String id, String name, String version, String description,
                               List<String> requires, String requiresCore, List<String> clientRequires,
                               String featureKey, boolean featureDefault) {

    public PluginDescriptor {
        Objects.requireNonNull(id, "插件 id 不能为空");
        if (id.isBlank()) {
            throw new IllegalArgumentException("插件 id 不能为空白");
        }
        id = id.trim();
        name = (name == null || name.isBlank()) ? id : name;
        version = (version == null || version.isBlank()) ? "0.0.0" : version;
        description = description == null ? "" : description;
        requires = normalize(requires);
        requiresCore = requiresCore == null ? "" : requiresCore.trim();
        clientRequires = normalize(clientRequires);
        featureKey = featureKey == null ? "" : featureKey.trim();
    }

    /**
     * 兼容构造：有依赖声明、没有功能开关（等价于 {@code featureKey} 为空、{@code featureDefault=true}）。
     */
    public PluginDescriptor(String id, String name, String version, String description,
                            List<String> requires, String requiresCore, List<String> clientRequires) {
        this(id, name, version, description, requires, requiresCore, clientRequires, "", true);
    }

    /** 是否声明了功能开关（{@code plugin.featureKey}）。 */
    public boolean hasFeatureKey() {
        return !featureKey.isBlank();
    }

    /**
     * 兼容构造：只有元信息、没有依赖声明（等价于 {@code requires / clientRequires} 为空、{@code requiresCore} 不限制）。
     * <p>
     * 现有插件（只实现 {@code descriptor()} 返回四元信息）不需要任何改动。
     */
    public PluginDescriptor(String id, String name, String version, String description) {
        this(id, name, version, description, List.of(), "", List.of(), "", true);
    }

    /** 解析后的 core 版本约束；{@link PluginVersionConstraint#isAny()} 为 true 表示不限制。 */
    public PluginVersionConstraint coreConstraint() {
        return PluginVersionConstraint.parse(requiresCore);
    }

    /** 是否声明了运行期依赖。 */
    public boolean hasRequires() {
        return !requires.isEmpty();
    }

    /** 是否声明了客户端依赖。 */
    public boolean hasClientRequires() {
        return !clientRequires.isEmpty();
    }

    public static PluginDescriptor of(String id, String version) {
        return new PluginDescriptor(id, id, version, "");
    }

    /** 去空白、去重、保持声明顺序（自依赖保留，交由依赖图按循环依赖处理并给出清晰日志）。 */
    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return unique.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(unique));
    }
}
