package cc.thonly.wikikernel.plugin.hitcounters;

import cc.thonly.wikikernel.common.plugin.PluginConfig;

import java.util.List;

/**
 * HitCounters 配置（对应 MediaWiki 的 {@code $wgDisableCounters} / {@code $wgEnableAddPageId} /
 * {@code $wgEnableAddTextLength} 等），在 {@code LocalSettings.yaml} 里：
 *
 * <pre>
 * wikikernel:
 *   plugins:
 *     settings:
 *       hit-counters:
 *         disabled: false            # true = 关闭浏览计数
 *         footer: true               # 页脚显示「本页面已被访问 N 次」
 *         enable-add-page-id: false  # 「最多浏览」列出页面 ID
 *         enable-add-text-length: false # 「最多浏览」列出字节数
 *         exempt-groups: ""          # 不计数（豁免）的用户组，逗号分隔（机器人始终不计数）
 * </pre>
 *
 * @param disabled        关闭计数（含 {{NUMBEROFVIEWS}} 显示与页脚）
 * @param footer          页脚显示本页浏览量（对应 MediaWiki 的 SkinAddFooterLinks）
 * @param addPageId       「最多浏览」显示页面 ID
 * @param addTextLength   「最多浏览」显示字节数
 * @param exemptGroups    豁免计数的用户组（除机器人外）
 */
public record HitCountersConfig(boolean disabled, boolean footer, boolean addPageId,
                                boolean addTextLength, List<String> exemptGroups) {

    public static HitCountersConfig from(PluginConfig config) {
        return new HitCountersConfig(
                config.getBoolean("disabled", false),
                config.getBoolean("footer", true),
                config.getBoolean("enable-add-page-id", false),
                config.getBoolean("enable-add-text-length", false),
                List.copyOf(config.getList("exempt-groups")));
    }
}
