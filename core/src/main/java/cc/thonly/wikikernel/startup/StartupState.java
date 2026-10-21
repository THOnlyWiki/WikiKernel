package cc.thonly.wikikernel.startup;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 启动就绪状态：Tomcat 在 ApplicationReadyEvent 之前就可能开始接受请求，
 * 这个状态用于让所有请求在「插件 / 初始化任务完成」之前进入等待页。
 * <p>
 * {@code PluginManager} 的 ApplicationReadyEvent 监听器是 HIGHEST_PRECEDENCE；
 * 本监听器用 LOWEST_PRECEDENCE，确保插件全部启动后才把 ready 置为 true。
 */
@Component
public class StartupState {

    private final Instant startedAt = Instant.now();
    private volatile boolean ready;

    public boolean ready() {
        return ready;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public long uptimeMillis() {
        return Math.max(0L, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void markReady() {
        ready = true;
    }
}
