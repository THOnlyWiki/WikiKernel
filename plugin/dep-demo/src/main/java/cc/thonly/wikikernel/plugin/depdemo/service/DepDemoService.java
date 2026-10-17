package cc.thonly.wikikernel.plugin.depdemo.service;

import cc.thonly.wikikernel.common.extension.CoreAopHook;
import org.springframework.stereotype.Component;

/**
 * 插件自己的 Bean（演示「插件也能对**自己插件里的 Bean** 加切面」）。
 * <p>
 * core 在插件子容器里也挂了一份 AOP 桥（{@code PluginSpringContextFactory.registerAopBridge}），
 * 因此插件在 {@code onStart} 里注册的 {@link CoreAopHook}
 * 对这个 Bean 同样生效 —— 即使它在 Advisor 注册之前就已经创建好了。
 */
@Component
public class DepDemoService {

    /** 打个招呼（切面演示的目标方法）。 */
    public String greet(String name) {
        return "hello " + name;
    }
}
