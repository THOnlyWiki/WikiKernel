package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.plugin.PluginFeatureSections;
/**
 * **core 自己的**功能开关（存站点设置 {@code wiki_setting}，可在 {@code 特殊:权限配置 → 站点设置}
 * 或 {@code /init} 修改，也可以在 {@code LocalSettings.yaml} 的 {@code wikikernel.features} 里给默认值）。
 * <p>
 * 目前只剩 core 拥有的三个功能：
 * <ul>
 *     <li>{@code features.register}：注册（创建账号）；</li>
 *     <li>{@code features.typescript} / {@code features.typescript-compile-on-save}：TypeScript 服务端编译；</li>
 *     <li>{@code features.codex-example}：是否列出「特殊:CodexExample」。</li>
 * </ul>
 * <b>插件自己的功能开关不在这个接口里</b>：插件在 {@code META-INF/wiki-plugin.properties} 里声明
 * <pre>
 *   plugin.featureKey=features.comments
 *   plugin.featureDefault=true
 * </pre>
 * core 就会在「站点设置 → 插件设置」与安装向导里自动渲染该开关（见
 * {@code PluginFeatureSections}），插件自己用 {@code common.service.SiteConfig}
 * 读同一个键——core 不需要为插件开关写任何代码，插件没加载时开关也不出现。
 */
public interface FeatureService {

    /** 站点设置键：注册（创建账号；默认开启，关闭后仅管理员 / 行政员能进 特殊:注册）。 */
    String REGISTER_KEY = "features.register";

    /** 站点设置键：TypeScript 服务端编译（{@code XXX.ts} / {@code XXX.tsx} 页面编译成 JavaScript；默认关闭）。 */
    String TYPESCRIPT_KEY = "features.typescript";

    /** 站点设置键：保存 TypeScript 页面时立即编译（默认关闭；只在 TypeScript 编译开启时生效）。 */
    String TYPESCRIPT_COMPILE_ON_SAVE_KEY = "features.typescript-compile-on-save";

    /** 站点设置键：是否列出并允许访问 特殊:CodexExample（默认开启）。 */
    String CODEX_EXAMPLE_KEY = "features.codex-example";

    /** 是否启用注册（创建账号；关闭后仅管理员 / 行政员可进 {@code 特殊:注册}）。 */
    boolean registerEnabled();

    /** 是否启用 TypeScript 服务端编译。 */
    boolean typescriptEnabled();

    /** 是否在保存 TypeScript 页面时立即编译。 */
    boolean typescriptCompileOnSave();

    /** 是否启用 / 列出 特殊:CodexExample。 */
    boolean codexExampleEnabled();

    /** 是否启用注册。 */
    void setRegister(boolean enabled);

    /** 是否启用 TypeScript 服务端编译。 */
    void setTypescript(boolean enabled);

    /** 是否在保存 TypeScript 页面时立即编译。 */
    void setTypescriptCompileOnSave(boolean enabled);

    /** 是否启用 / 列出 特殊:CodexExample。 */
    void setCodexExample(boolean enabled);
}
