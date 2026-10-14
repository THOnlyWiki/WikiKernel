package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：页面浏览钩子（HitCounters 等扩展用）。
 * <p>
 * core 在「词条阅读视图」成功渲染后调用（不含 diff / 旧版本 / 特殊页 / 预览 / API / action=raw）。
 * 插件可据此累计浏览量等。
 */
public interface PageViewHook extends Extension {

    /** 一次页面浏览（跳过与否由插件自行决定，事件里带用户 / 机器人 / 用户组信息）。 */
    void pageViewed(PageViewEvent event);
}
