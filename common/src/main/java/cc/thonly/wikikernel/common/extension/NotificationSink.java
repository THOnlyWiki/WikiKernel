package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 通知发送桥（由通知类插件实现，其它插件 {@code @Autowired} 注入即可）。
 *
 * <p>典型实现是 Echo：插件之间不直接依赖，靠这个接口解耦——
 * 没有装通知插件时字段注入为 {@code null}，调用方判空跳过即可。
 */
public interface NotificationSink extends Extension {

    /**
     * 给某个用户发一条通知。
     *
     * @param recipientUserId 收件人用户 id
     * @param type            通知类型（由实现方定义，未知类型按通用文案渲染）
     * @param actorUserId     触发者用户 id（{@code null} = 系统）
     * @param pageTitle       关联页面标题（{@code null} = 不关联页面）
     * @param comment         摘要（会拼在通知后面）
     * @return 是否真的发出（收件人关掉了这类通知、或功能关闭时为 {@code false}）
     */
    boolean notify(long recipientUserId, String type, Long actorUserId, String pageTitle,
                   String comment);
}
