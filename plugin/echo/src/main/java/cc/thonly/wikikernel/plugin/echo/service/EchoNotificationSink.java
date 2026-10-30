package cc.thonly.wikikernel.plugin.echo.service;

import cc.thonly.wikikernel.common.extension.NotificationSink;
import org.springframework.stereotype.Service;

/**
 * 通知发送桥的 Echo 实现：其它插件 {@code @Autowired NotificationSink} 就能发通知，
 * 不用依赖 Echo 的任何类。
 *
 * <p>尊重收件人在偏好里关掉的类型（{@link EchoNotifier#notify} 内部判断）。
 */
@Service
public class EchoNotificationSink implements NotificationSink {

    private final EchoNotifier notifier;

    public EchoNotificationSink(EchoNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public boolean notify(long recipientUserId, String type, Long actorUserId, String pageTitle,
                          String comment) {
        return notifier.notify(recipientUserId, type, actorUserId, pageTitle, comment);
    }
}
