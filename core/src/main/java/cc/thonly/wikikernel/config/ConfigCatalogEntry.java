package cc.thonly.wikikernel.config;

import java.util.Collections;
import java.util.List;

/**
 * 配置全集目录里的一项（见 {@code core/src/main/resources/config-catalog.yaml}）。
 *
 * @param key         配置键（完整路径，含 {@code wikikernel.} / {@code spring.} 前缀）
 * @param type        {@code string} / {@code boolean} / {@code integer} / {@code long} / {@code list} / {@code map} / {@code object}
 * @param defaultValue 默认值（值类型跟随 {@link #type}；{@code list} = {@code List}，{@code map} = {@code Map}）
 * @param enumValues  可选值清单（无则空列表）
 * @param writable    是否写进生成的 LocalSettings.yaml
 * @param secret      敏感项（不把值落盘，只留注释与键名）
 * @param description 中文说明（写进生成的注释与 JSON Schema）
 */
public record ConfigCatalogEntry(String key,
                                String type,
                                Object defaultValue,
                                List<String> enumValues,
                                boolean writable,
                                boolean secret,
                                String description) {

    public ConfigCatalogEntry {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    /** 是否属于某个前缀（按 {@code .} 分段，避免 {@code wiki} 误匹配 {@code wikikernel}）。 */
    public boolean belongsTo(String prefix) {
        return key.equals(prefix) || key.startsWith(prefix + ".");
    }

    /** 相对某个前缀的短键名（不属于该前缀时返回全键）。 */
    public String shortKey(String prefix) {
        if (!belongsTo(prefix) || key.equals(prefix)) {
            return key;
        }
        return key.substring(prefix.length() + 1);
    }

    /** 键路径分段（用于构建嵌套结构）。 */
    public List<String> segments() {
        return Collections.unmodifiableList(List.of(key.split("\\.")));
    }

    /** 生成注释：中文说明 + 可选值 + 默认值（或当前生效值）+ 敏感提示。 */
    public String commentText() {
        return commentText(null);
    }

    /**
     * 生成注释行。
     *
     * @param effectiveValue 当前生效值（null = 用目录默认值）；与默认值不同时注释里会额外标出
     */
    public String commentText(Object effectiveValue) {
        StringBuilder text = new StringBuilder(description);
        if (!enumValues.isEmpty()) {
            text.append("（可选值：").append(String.join(" / ", enumValues)).append("）");
        }
        if (secret) {
            text.append("【敏感项：不在生成的文件里给出值，请按需填写】");
        } else if (effectiveValue != null && !effectiveValue.equals(defaultValue)) {
            text.append("（默认：").append(ConfigCatalog.describeValue(defaultValue))
                    .append("；当前：").append(ConfigCatalog.describeValue(effectiveValue)).append("）");
        } else {
            text.append("（默认：").append(ConfigCatalog.describeValue(defaultValue)).append("）");
        }
        return text.toString();
    }
}
