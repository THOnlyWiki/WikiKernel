package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 功能开关（LocalSettings.yaml 的 {@code wikikernel.features}）：
 * 启动时导入站点设置（非 null 才覆盖，和访问规则同一约定）；之后可在
 * {@code 特殊:权限配置 → 站点设置} 或 {@code /init} 里修改。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.features")
public class FeatureProperties {

    /** 注册（创建账号；默认开启）；null = 不改数据库里的设置。 */
    private Boolean register;

    /** TypeScript 服务端编译（{@code .ts} / {@code .tsx} 页面 → JavaScript；默认关闭）。 */
    private Boolean typescript;

    /** 保存 TypeScript 页面时立即编译（默认关闭）。 */
    private Boolean typescriptCompileOnSave;

    /** 是否列出 / 允许访问 特殊:CodexExample（默认开启）。 */
    private Boolean codexExample;


}
