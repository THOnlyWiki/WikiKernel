package cc.thonly.wikikernel.plugin.socialprofile.hook;

import cc.thonly.wikikernel.common.extension.PageViewEvent;
import cc.thonly.wikikernel.common.extension.PageViewHook;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsService;

/**
 * 浏览词条给分（仿 MediaWiki SocialProfile 的 UserStats 计分入口之一）。
 * <p>
 * <b>为什么用浏览钩子而不是编辑钩子</b>：core 目前没有「编辑保存成功」给插件的事件
 * （{@code EditGuard} 只在保存前拦截），而 {@link PageViewHook} 是现成、稳定、对 core 零侵入的钩子
 * （HitCounters 用的同一个）。所以「编辑页面给分」在这一版里由「浏览词条给分」代偿：
 * <ul>
 *     <li>匿名 / 机器人不计分（事件里自带 {@code bot} 标记）；</li>
 *     <li>同一个用户对同一个页面在 {@code socialProfile.points.viewCooldownSeconds} 秒内只计一次
 *     （默认 300 秒），避免刷新刷分；</li>
 *     <li>分值由 {@code socialProfile.points.pageView} 配置（默认 1 分）。</li>
 * </ul>
 * 局限与替代方案见 {@link PointsService} 的类注释；core 将来加了编辑完成事件后，
 * 换掉这个钩子实现即可，给分逻辑与等级阈值表不用动。
 */
public final class PointsViewHook implements PageViewHook {

    private final PointsService points;

    public PointsViewHook(PointsService points) {
        this.points = points;
    }

    @Override
    public void pageViewed(PageViewEvent event) {
        if (event == null || event.user() == null || event.user().getId() == null) {
            return;
        }
        points.awardPageView(event.user().getId(), event.pageId(), event.bot());
    }
}
