package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.List;

/**
 * 插件在「站点设置」页里的设置分区（仿 MediaWiki 的 {@code Special:Preferences} 里扩展注册的设置块）：
 * 插件声明分区标题与字段，core 的 {@code 特殊:权限配置 → 站点设置} 会统一渲染成表单，
 * 并沿用同一批站点设置键保存（与 {@code LocalSettings.yaml}、安装向导兼容）。
 * <p>
 * 用途：功能设置是这个插件自己的东西（页面横幅的图片池 / 白名单 / 轮播、验证码免验组、
 * 注册问答的题目答案……），core 不该认识它们——插件注册一个分区即可，**不需要** core 里有任何
 * 该功能的代码。
 * <pre>{@code
 *   context.register(SiteSettingsSection.class, new PageBannerSettingsSection(messageSource));
 * }</pre>
 * 约定：
 * <ul>
 *     <li>{@link #id()} 稳定且全局唯一（表单里作为分区标记提交）；</li>
 *     <li>{@link #fields()} 的字段键就是站点设置键（空值 = 清空该项，checkbox 未勾选 = {@code "false"}）；</li>
 *     <li>标题 / 标签请返回**本地化后的文本**（插件自己的 {@code MessageSource} / {@code MessageBundle}）；</li>
 *     <li>插件未加载时分区不存在（core 不需要清理任何东西）。</li>
 * </ul>
 */
public interface SiteSettingsSection extends Extension {

    /** 分区稳定 id（提交 / 日志用；建议等于插件 id）。 */
    String id();

    /** 分区标题（本地化文本）。 */
    String title();

    /** 分区说明（可空；渲染在标题下方）。 */
    default String description() {
        return "";
    }

    /** 排序（小的在前；core 的通用设置之后）。 */
    default int order() {
        return 100;
    }

    /** 分区的字段（键 = 站点设置键）。 */
    List<SettingField> fields();
}
