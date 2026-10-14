package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.data.WikiUser;

import java.util.Set;

/**
 * 一次页面浏览事件。
 *
 * @param pageId    页面 ID
 * @param pageTitle 页面标题（含命名空间）
 * @param user      当前登录用户（未登录为 null）
 * @param bot       是否机器人（core 按「机器人」用户组判断）
 * @param groups    当前用户的用户组（未登录为空集合；可用于自定义豁免组，如 HitCounters 的 hitcounter-exempt）
 */
public record PageViewEvent(long pageId, String pageTitle, WikiUser user, boolean bot, Set<String> groups) {

    public PageViewEvent {
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }
}
