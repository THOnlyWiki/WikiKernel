package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 皮肤配置（LocalSettings.yaml 的 {@code wikikernel.skins}）。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.skins")
public class SkinProperties {

    /**
     * 默认皮肤 id（内置：{@code vector}、{@code fallback}；插件皮肤填其 id）。
     * 找不到或已被禁用时自动回退到 {@code fallback}。
     */
    private String defaultSkin = "vector";

    /**
     * 禁用的皮肤 id 列表（Vector 可禁用；内置 {@code fallback} 始终可用，配置了也会忽略）。
     */
    private List<String> disabled = new ArrayList<>();
}
