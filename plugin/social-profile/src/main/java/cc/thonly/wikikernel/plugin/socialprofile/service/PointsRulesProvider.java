package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import org.springframework.stereotype.Component;

/**
 * 给分规则（{@link PointsRules}）的 Spring 组件：从站点设置 / 插件配置读一次配置，
 * 供 {@code PointsService}、{@code 特殊:积分排行} 与前端展示共用同一个实例（配置改了下次读取即生效）。
 * <p>
 * 配置键与默认值见 {@link PointsRules}：
 * <ul>
 *     <li>站点设置（{@code 特殊:权限配置 → 站点设置} / LocalSettings.yaml）：
 *     {@code socialProfile.points.enabled}、{@code socialProfile.points.pageView}、
 *     {@code socialProfile.points.boardPost}、{@code socialProfile.points.friend}、
 *     {@code socialProfile.points.gift}、{@code socialProfile.points.profileEdit}、
 *     {@code socialProfile.points.viewCooldownSeconds}、{@code socialProfile.points.levels}；</li>
 *     <li>插件配置（{@code wikikernel.plugins.settings.social-profile.*}）：同名的短键
 *     {@code points.enabled} / {@code points.pageView} / … 优先于站点设置。</li>
 * </ul>
 */
@Component
public final class PointsRulesProvider {

    private final SocialProfileSupport support;

    public PointsRulesProvider(SocialProfileSupport support) {
        this.support = support;
    }

    /** 当前给分规则（每次调用都重新读配置）。 */
    public PointsRules rules() {
        return support == null ? PointsRules.DISABLED : support.pointsRules();
    }
}
