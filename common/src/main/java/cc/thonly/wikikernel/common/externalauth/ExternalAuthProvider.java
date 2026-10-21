package cc.thonly.wikikernel.common.externalauth;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.List;

/**
 * 第三方登录 provider 扩展点（插件实现）。
 * <p>
 * provider 自己通过 {@code common.service.SiteConfig} 读取站点设置中的启用开关和密钥；
 * core 只负责登录 / 注册 / 绑定流程、回调地址和配置界面渲染。
 * 新增 provider 不需要改 core：实现本接口，在插件 {@code onStart} 里调用
 * {@code context.register(ExternalAuthProvider.class, provider)} 即可。
 */
public interface ExternalAuthProvider extends Extension {

    /** 稳定 id（小写字母、数字、-），用于 URL 与站点设置键。 */
    String id();

    /** 展示名（如 GitHub / QQ）。 */
    String displayName();

    /** 排序：越小越靠前。 */
    default int order() {
        return 100;
    }

    /** 启用开关的站点设置键。 */
    String enabledSettingKey();

    /** Client ID / App ID 的站点设置键。 */
    String clientIdSettingKey();

    /** Client Secret / App Key 的站点设置键。 */
    String clientSecretSettingKey();

    /** 管理界面里 Client ID 字段的文字（直接展示，不依赖 core 的 MessageSource）。 */
    String clientIdLabel();

    /** 管理界面里 Client Secret 字段的文字。 */
    String clientSecretLabel();

    /** OAuth scope 列表。 */
    List<String> scopes();

    /** provider 是否在站点设置中启用。 */
    boolean enabled();

    /** 是否已经配置了 id + secret；未配置的 provider 不显示登录按钮。 */
    boolean configured();

    /** 可以显示 / 使用：已启用且配置完整。 */
    default boolean available() {
        return enabled() && configured();
    }

    /** 构造 provider 授权页 URL。 */
    String authorizationUrl(String state, String redirectUri) throws Exception;

    /**
     * 用授权码换取用户身份。
     *
     * @param code        provider 返回的 authorization code
     * @param redirectUri 与授权时完全一致的回调地址
     */
    ExternalAuthUser exchange(String code, String redirectUri) throws Exception;
}
