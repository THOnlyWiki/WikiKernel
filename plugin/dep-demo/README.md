# dep-demo（插件平台能力演示 / 测试用插件）

> **不是 MediaWiki 扩展移植**：这是仓库自带的最小**测试插件**，用来验证插件平台的四项能力：
> 运行期插件依赖（`plugin.requires`）、core 版本约束（`plugin.requiresCore`）、
> 插件对 core 的 AOP（`CoreAopHook`）、客户端运行时插件需求（`plugin.clientRequires`）。
> 它只依赖 `common`（core 的类名以字符串出现），因此满足 `:checkPluginDependencies`。

## 描述文件（`META-INF/wiki-plugin.properties`）

```properties
plugin.id=dep-demo
plugin.requires=                 # 运行期依赖（本插件留空 = 不依赖其它插件）
plugin.requiresCore=>=0.0.1      # core 版本约束（数字段比较，0.0.1-SNAPSHOT 也满足）
plugin.clientRequires=comments   # 前端资源依赖：comments 的 head 片段先注入
plugin.aop=true                  # 声明需要插件 AOP 桥（默认 auto 只有声明了才启用）
```

## 它做了什么

| 扩展点 | 作用 |
| --- | --- |
| `CoreAopHook`（`hook/DepDemoAopHook`） | 提供两个 `DefaultPointcutAdvisor`：① 环绕 core 的 `SiteSettingsService#get(String, String)` 并计数（`GET_CALLS`）——演示「插件对 core / common 加切面」，且**对已经注入到其它 Bean 的引用同样生效**；② 环绕插件自己的 `DepDemoService#greet(String)` 并计数（`GREET_CALLS`）——演示「插件对自己插件里的 Bean 加切面」（core 在插件子容器里也挂了一份 AOP 桥） |
| `HtmlHeadProvider`（`hook/DepDemoHeadProvider`） | 站点级 `<head>` 片段（注释 + `<meta name="dep-demo">`），用来观察 `plugin.clientRequires` 决定的注入顺序 |
| `MessageBundle`（`DepDemoMessages`） | 插件名 / 描述的本地化文案（`i18n/messages*.properties`，中英键集合一致） |
| `@Component DepDemoService` | 插件自己的业务 Bean（被上面第 ② 个切面命中） |

## 相关测试

- **插件自身**：`DepDemoAopHookTest`（两个切面各自只命中目标类型 / 方法，且只计数、不改返回值）——
  `.\gradlew.bat :plugin:dep-demo:build`
- **core 侧集成**：`core` 的 `DepDemoPluginTest` 用真实的 dep-demo jar（`plugin/dep-demo/build/libs/`）启动一次应用，断言：
  插件被加载、`PluginManager.clientLoadOrder()` 含 `dep-demo`、`clientDependencyWarnings()` 在 comments 未加载时给出 HTML 注释、
  core Bean 被切面命中（`GET_CALLS` 增加）、插件自己的 Bean 也被切面命中（`GREET_CALLS` 增加）。
  先构建插件：`.\gradlew.bat :plugin:dep-demo:build`；没有 jar 时该测试自动跳过。

## 需要注意

- 插件 AOP 桥默认「安全优先」：`wikikernel.plugins.aop-bridge=auto` 只有在有插件声明 `plugin.aop=true`
  （本插件就是这样）时才启用；也可以在启动参数里 `--wikikernel.plugins.aop-bridge=true` 强制打开给所有插件用。
  框架 / Web 基础设施 Bean（Servlet Filter、Servlet、HandlerInterceptor、数据源、事务管理器、
  带 `final` 方法的类等）永远不会被代理 —— 这是为了不让代理破坏容器生命周期（`GenericFilterBean.init()` 是 final）；
- core 不带 `aspectjweaver`，所以 pointcut 用 Spring 原生实现（`NameMatchMethodPointcut` + `ClassFilter`），
  不能用 `@Aspect` / `execution(...)`（`AspectJExpressionPointcut`）；
- 统计值放在静态字段上，是为了让 core 侧测试在**没有编译期依赖**的前提下读回来（插件与 core 只共享 `common`）。
