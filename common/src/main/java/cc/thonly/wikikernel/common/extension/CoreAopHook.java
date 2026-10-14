package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;
import org.springframework.aop.Advisor;

import java.util.List;

/**
 * 扩展点：插件向 core 提供的 Spring AOP 切面（**对 core / common 的 Bean 加切面**）。
 * <p>
 * 与其它扩展点一样在 {@code WikiPlugin.onStart} 里注册：
 *
 * <pre>
 * context.register(CoreAopHook.class, () -&gt; List.of(new DefaultPointcutAdvisor(pointcut, advice)));
 * </pre>
 *
 * core 侧（{@code core/plugin/aop/PluginAopBridge}）把这些 Advisor 套到匹配的 core Bean 上
 * （{@code ProxyFactory} 生成代理），插件也可以对**自己插件子容器里的 Bean** 生效。
 * <p>
 * 注意事项：
 * <ul>
 *     <li>只对**单例、非基础设施、非 {@code @Configuration}、非 {@code @Bean} 工厂方法产物**的 core Bean 生效
 *         （Spring / Thymeleaf / MyBatis / JDBC 等框架 Bean 一律跳过）；
 *         「代理哪些 Bean」在 Bean 创建阶段就定了（那时还不知道插件会声明什么 pointcut），
 *         而「哪个方法命中」在每次调用时判断：未命中的调用只多一次判断，行为与没有切面时完全一致；</li>
 *     <li>pointcut 请用 Spring 原生实现（{@code NameMatchMethodPointcut} / {@code JdkRegexpMethodPointcut} /
 *         {@code AnnotationMatchingPointcut} 等）。core 不带 {@code aspectjweaver}，
 *         因此 {@code AspectJExpressionPointcut}（{@code @Aspect} / {@code execution(...)}）不可用；</li>
 *     <li>插件加载发生在应用就绪之后，core Bean 在 Bean 创建阶段就套好了「桥接代理」，
 *         因此**插件自己的引用、其它 Bean 注入的引用**都能被切面命中（不需要重启或重新注入）；</li>
 *     <li>{@code advisors()} 抛异常只记 WARN 并跳过该插件；单个 Advisor 的 pointcut 匹配抛异常也只记 WARN 并跳过它，
 *         页面 / 请求不会 500；</li>
 *     <li>插件卸载（{@code PluginManager.stop()}）后 Advisor 立即失效（代理壳变成直通），
 *         已注入的引用不需要重新绑定；</li>
 *     <li><b>默认不建代理</b>：请在本插件描述文件里加 {@code plugin.aop=true}
 *         （core 看到声明才会启用插件 AOP 桥；也可以由运维统一设
 *         {@code wikikernel.plugins.aop-bridge=true}）。没启用时 core 会打一条 WARN 提示。</li>
 * </ul>
 */
public interface CoreAopHook extends Extension {

    /**
     * 插件提供的 Spring AOP Advisor（{@code PointcutAdvisor} / {@code DefaultPointcutAdvisor} 等）。
     * <p>
     * 返回 null / 空列表 = 本插件不贡献切面。Advisor 的执行顺序 = 列表顺序（同一插件的多个 Advisor 按声明顺序）。
     */
    List<Advisor> advisors();
}
