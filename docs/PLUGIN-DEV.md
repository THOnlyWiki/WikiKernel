# 插件开发与运行

模块依赖方向、构建与运行（`installPlugins` / `run.bat` / `/init` / 动态装卸 / 日志）、插件里使用 Spring、
新增一个插件的目录与包结构约定、插件加载原理（core 侧）、插件依赖声明与 `CoreAopHook`，
以及**内置插件**的完整清单（能力、扩展点、平台能力、配置键与与 MediaWiki 原版的差异；
下节正文里的「28 个」是原 README 的说法，插件目录实际是 29 个，见 [README](../README.md#内置插件一览表) 的更正）。
每个插件自己的细节见仓库里的 `plugin/<id>/README.md`（清单表格里给出了直达链接）。

其它文档：[README](../README.md) ｜ [接口与兼容 API](./API.md) ｜ [配置与数据](./CONFIG.md) ｜ [功能详述](./FEATURES.md) ｜ [皮肤与前端界面](./SKIN.md) ｜ [Wikitext 与内容标记](./WIKITEXT.md) ｜ [插件 Mixin](./MIXIN.md)

## 依赖方向（不会产生循环依赖的关键）

```
            common（共享层：SPI / 扩展点 / 模型）
           ↑               ↑
        core            plugin/*
   （只依赖 common）   （只依赖 common）
```

三条铁律：

1. `common` 不依赖任何项目模块（只带 MyBatis-Plus 注解等编译期依赖），跨插件协作的接口（扩展点）和共享模型都定义在 `common`；
2. `core` 只依赖 `common`，**不依赖具体插件**，插件在运行时由 `PluginManager` 装配；
3. 插件之间不互相依赖，需要协作就通过 `common` 里的扩展点 + `PluginContext.register(...)`。

根目录的 `checkPluginDependencies` 任务会在任何模块执行 `check` / `build` 时自动校验上述规则，
一旦有人写出 `core -> plugin`、`plugin -> core`、`plugin -> plugin` 的依赖，构建直接失败。

## 插件里使用 Spring（注解全部生效）

插件用 `compileOnly` 引用 Spring Boot，**运行时由 core 提供，插件 jar 不打包 Spring**：

```groovy
dependencies {
    implementation project(':common')
    compileOnly platform("org.springframework.boot:spring-boot-dependencies:${springBootVersion}")
    compileOnly 'org.springframework.boot:spring-boot-starter-webmvc'
}
```

core 会为每个插件创建一个**子 Spring 容器**（父容器是 core 主容器），因此插件的注解全部生效：

| 能力 | 说明 |
| --- | --- |
| `@Component` / `@Service` / `@Repository` | 扫描入口类所在包（含子包）自动注册 |
| `@Configuration` / `@Bean` | 正常处理，可用于插件自己的装配 |
| `@Autowired` / `@Inject` / `@Resource` | 支持构造器/字段/方法注入；**可以注入 core 的 Bean**（如 `DataSource`） |
| `@Value("${...}")` | 可读取 `application.yaml` / 命令行参数等主应用配置 |
| `@ConfigurationProperties` | 正常绑定（core 注册了 Boot 的绑定处理器） |
| `@Controller` / `@RestController` | 由 core 动态注册到主 MVC，可直接对外提供接口，插件停止时自动注销 |

当前仓库未内置示例插件；需要时按文末「新增一个插件」自行添加，上面这些能力在插件实现里直接可用。

约定与边界：

- 插件代码放在**入口类所在包及其子包**下，才会被扫描；入口类本身也会被注册为 Bean；
- 子容器可以访问父容器（core），反过来不行，所以 core 不会 `@Autowired` 插件的 Bean，协作请走扩展点；
- 子容器不跑 Boot 自动配置，`@Scheduled` / `@Async` / 事务等需要插件在自己的 `@Configuration` 上显式 `@EnableScheduling` / `@EnableAsync` / `@EnableTransactionManagement`；
- 不要用 `implementation` 引 Spring，避免插件 jar 打包 Spring 与 core 冲突。

## 新增一个插件

1. 在 `plugin/` 下新建 `plugin/your-plugin/`，最小结构：
   `build.gradle` + `src/main/java/...` + `META-INF/wiki-plugin.properties` + `META-INF/services/...WikiPlugin`；
2. `build.gradle` 用 `plugins { id 'java'; id 'application' }`，依赖只加 `implementation project(':common')`；
   需要 Spring 时按上文用 `compileOnly` 引；
3. 编写 `META-INF/wiki-plugin.properties`（插件 id、入口类）与 `META-INF/services/...WikiPlugin`；
4. 实现 `WikiPlugin`，在 `onStart` 里注册扩展点实现（扩展点接口定义在 `common`）；
5. 需要 Spring 能力时，直接加 `@Service` / `@RestController` 等注解即可；
6. `plugin/` 下任何带 `build.gradle` 的目录都会被 `settings.gradle` 自动纳入构建，无需改根配置；
7. 插件也可以提供**皮肤**：`plugin/plain-skin` 是完整示例（`Skin` SPI + layout + CSS，见「皮肤系统」一节）。

插件 jar 打包要求：

- 不要打包 `common` 和 Spring（用 `implementation project(':common')` + `compileOnly` 时 Gradle 不会打进去）；
- 如果插件引入第三方库，建议用 Shadow / 独立依赖目录等方式处理，避免和 core 的依赖冲突；
- 描述文件按 UTF-8 读取，可直接写中文。

### 插件的包结构（约定，必须遵守）

插件的**入口类必须留在根包**（`META-INF/wiki-plugin.properties` 的 `plugin.mainClass` 与 `META-INF/services/...WikiPlugin` 指向它）；
其余类按职责分包，**main 类 ≥ 6 个就必须分包**（≤ 5 个可以平铺）：

```
cc.thonly.wikikernel.plugin.<id>            入口 XxxPlugin + 只服务入口的 Support / Settings / Messages
cc.thonly.wikikernel.plugin.<id>.hook       所有 common.extension.* 实现（ParserTag / PageDisplayHook / EditGuard /
                                            ActionGuard / RegistrationGuard / HtmlHeadProvider / MessageBundle /
                                            LogType / PreferenceSection / BehaviorSwitch …）
cc.thonly.wikikernel.plugin.<id>.web        @RestController 与 SpecialPage
cc.thonly.wikikernel.plugin.<id>.service    业务逻辑（Service / 渲染器 / 会话编排 / 厂商适配器 / Store 包装 …）
cc.thonly.wikikernel.plugin.<id>.model      record / DTO / 枚举 / 常量表 / 异常（没有就不建）
```

- 包名 `<id>` 用插件目录的小写形式（`wiki-seo` → `wikiseo`、`abuse-filter` → `abusefilter`）；
- 插件子容器扫描的是**入口类所在包及其子包**（`PluginSpringContextFactory.scanPluginPackage`），
  所以 `@Component` / `@Service` / `@RestController` 放子包照样生效；
- 测试类放到与被测类对应的子包，测试替身（Fakes）放测试侧根包或对应子包。

### 本地化（i18n，必须遵守）

**每个模块只负责自己的文案**——多模块 wiki 不允许把各插件文案集中到 core 的 `messages.properties`：

| 模块 | 文案位置 |
| --- | --- |
| core | `core/src/main/resources/messages.properties`（默认＝中文）、`messages_en.properties`（英文） |
| 插件 `plugin/<id>` | `plugin/<id>/src/main/resources/i18n/messages.properties`（中文）、`i18n/messages_en.properties`（英文） |

- 插件消息包只要一行（core 的 `ResourceMessageBundle` 会从**插件自己的** jar 里读上面两个文件）：
  ```java
  public class XxxMessages extends ResourceMessageBundle {
  }
  ```
  并在入口 `onStart` 里 `context.register(MessageBundle.class, new XxxMessages())`；
  properties 用 UTF-8（**无 BOM**），`:` / `=` / `\` 要转义（`\:` / `\=` / `\\`），`{0}` 这类占位符照旧；
  一个插件有多个消息包时用 `super("i18n/<名字>")` 区分基名，默认基名是 `i18n/messages`；
- **缺翻译时显示翻译键本身，绝不回落中文**（`PluginMessageSource`：core 与插件都查不到就返回 `files.multiListLabel` 这样的键名，
  一眼能看出漏翻）。因此这些写法**禁止**：
  - 模板：`th:text="#{key} ?: '中文'"`（应直接 `th:text="#{key}"`）；
  - Java：`message("key", "中文兜底")`（应 `message("key")`；只有「不是中文」的数据型默认值才可保留）；
- **插件名与描述也要本地化**（`特殊:版本` 的插件列表用它）：
  ```properties
  plugin.<id>.name=多文件批量上传（MsUpload）
  plugin.<id>.description=上传页多选列表 + 编辑器上传对话框批量插入
  ```
  解析顺序：当前语言的消息键 → 描述文件里的 `plugin.description`（英文技术描述）→ 插件 id；
- **版本与名称放本插件的 `gradle.properties`**，不再手改描述文件：
  ```properties
  pluginName=MsUpload
  pluginVersion=1.0.0
  pluginDescription=Multi-file batch upload for the upload page and the editor upload dialog
  ```
  配合 `build.gradle` 里的展开（`processResources { filesMatching('META-INF/wiki-plugin.properties') { expand(...) } }`），
  描述文件写成 `plugin.name=${pluginName}` / `plugin.version=${pluginVersion}` / `plugin.description=${pluginDescription}` 即可；
- 语言切换：`?uselang=zh|en`（与 MediaWiki 同名参数，会话内记住），用户长期偏好在 `特殊:参数设置` 里设；
- 改完自检：中文与英文两个文件的**键集合必须完全一致**（插件与 core 都是），且模板 / Java 里不再出现中文兜底。

### 代码风格（同样的要求也适用于 core）

- **不要写内联全限定类名**，一律 `import` 后用简名：
  `context.register(HtmlHeadProvider.class, new XxxHeadProvider())` 而不是
  `context.register(cc.thonly.wikikernel.common.extension.HtmlHeadProvider.class, …)`；
  泛型方法引用同理（`Wrappers.<WikiLog>lambdaQuery()`，不是 `Wrappers.<cc.thonly.wikikernel.common.data.WikiLog>lambdaQuery()`）；
  例外：javadoc 的 `{@link}` / `{@code}`、字符串字面量里的类名（反射用）；
- 不要用通配 `import xxx.*;`；
- 被 `@RestController` / `SpecialPage` 构造器依赖的支撑对象**必须是 Spring Bean**（加 `@Component`），
  不能只在 `onStart` 里 `new`（否则插件在真实实例上会以 `NoSuchBeanDefinitionException` 启动失败，
  单元测试发现不了——`core` 的 `PluginRuntimeLoadTest` 专门拦这类问题）。

## 插件加载原理（core 侧）

`PluginManager` 在 `ApplicationReadyEvent`（应用完全就绪）后：

1. 扫描 `wikikernel.plugins.dir` 下的 `*.jar`，每个 jar 创建独立的 `PluginClassLoader`
   （父优先，`common` 类型统一由 core 提供，保证扩展点类型一致）；
2. 读取 jar 内 `META-INF/wiki-plugin.properties` 得到插件元信息（含下表的依赖声明）；
   没有描述文件时回退到 `META-INF/services` + ServiceLoader；
3. 按 `enabled` 白名单过滤、去掉重复 id，再按 **`plugin.requires` 拓扑排序**（被依赖的先启动），
   逐个检查 core 版本约束与循环依赖，然后为每个插件创建子 Spring 容器：
   继承主应用配置源、注册 `PluginContext`、扫描插件包、注册入口 Bean（细节见下）；
4. 把插件 Controller 动态注册到 core 的 `RequestMappingHandlerMapping`；
5. 调用 `WikiPlugin.onStart`。
6. 应用关闭时调用 `onStop`、注销路由、关闭子容器与插件类加载器。

插件运行时数据统一放在 `data/plugins/<插件id>/`（已加入 `.gitignore`）。

### 插件依赖声明（`META-INF/wiki-plugin.properties`）

| 键 | 语义 | 缺失 / 不满足时的行为 |
| --- | --- | --- |
| `plugin.requires` | **运行期依赖**的其它插件 id（逗号分隔，可空）。被依赖的插件必须先启动 | 缺失（未安装 / 被 `enabled` 白名单禁用）/ 对方启动失败 / 对方处于循环依赖 → **本插件跳过启动**，只记一条 WARN（列出缺哪个依赖、为什么），应用照常启动 |
| `plugin.requiresCore` | **core 版本约束**：`>=`、`>`、`<=`、`<`、`=` 或裸版本号（裸版本号 = 至少该版本；空 = 不限制）。比较**只看数字段**，因此 `>=0.0.1` 在 `0.0.1-SNAPSHOT` 上成立 | 不满足 → 跳过启动 + WARN（日志里给出要求值与当前 `WikiKernelInfo.VERSION`） |
| `plugin.clientRequires` | **客户端（前端资源）依赖**的其它插件 id（逗号分隔，可空）：本插件的 `<head>` 片段需要那些插件的前端资源先加载 | 对方没加载 → **插件照常启动**，页面 `<head>` 里留一条 HTML 注释警告 + WARN 日志（前端能力降级，后端不受影响） |
| `plugin.aop`（可选） | 本插件要用 `CoreAopHook` 对 core 的 Bean 加切面（`true` / 空）。**声明后 core 才会在默认配置下启用插件 AOP 桥**（`wikikernel.plugins.aop-bridge=auto`）；不声明则一个代理都不建（默认安全） | 未声明 → 桥不启用，`CoreAopHook` 注册了也不会生效（WARN 提示按需开启） |
| `plugin.mixins`（可选） | 本插件的 Mixin / MixinExtras 配置资源（逗号分隔，如 `mixins.my-plugin.json`）。启动期 Java Agent 会在 core 类加载前注册；不写时自动发现 `*.mixins.json` / `mixins*.json`。详见 [插件 Mixin](./MIXIN.md) | 未加载 Agent → 插件照常启动但 Mixin 不生效（WARN）；运行期新增 → 跳过启动，重启后生效 |

```properties
plugin.id=your-plugin
plugin.name=${pluginName}
plugin.version=${pluginVersion}
plugin.description=${pluginDescription}
plugin.mainClass=cc.thonly.wikikernel.plugin.yourplugin.YourPlugin
plugin.requires=comments,social-profile      # 运行期依赖（可空）
plugin.requiresCore=>=0.0.1                    # core 版本约束（可空）
plugin.clientRequires=comments                 # 前端资源依赖（可空）
plugin.aop=true                                # 可选：声明要用 CoreAopHook 对 core Bean 加切面
plugin.mixins=mixins.your-plugin.json          # 可选：插件 Mixin 配置（启动期生效）
```

行为汇总（都是「只跳过 / 只告警」，**不会让应用启动失败**）：

| 情况 | 结果 | 日志 |
| --- | --- | --- |
| 依赖都在，顺序正确 | 按拓扑顺序启动（被依赖者先） | `已启动插件 X vN（依赖: …；core >=…；客户端依赖: …）（来源: …）` |
| `plugin.requires` 里的插件没装 / 被禁用 | 本插件跳过 | WARN（含缺失 id 与原因） |
| 依赖的插件自己也跳过了（链式缺失） | 本插件跳过 | WARN（含「依赖的插件未启动 + 原因」） |
| `plugin.requires` 形成环（含自依赖） | **环上所有插件跳过** | ERROR（打印一条环路，如 `a → b → a`） |
| 依赖了环上的插件（自身不在环上） | 本插件跳过 | WARN |
| `plugin.requiresCore` 不满足 | 本插件跳过 | WARN（要求 X，当前 Y） |
| `plugin.clientRequires` 里的插件没加载 | 插件照常启动 | 页面 HTML 注释 + WARN |

插件作者可以用 `PluginManager` 的公开方法自查这些顺序（core 内部与测试用它）：

- `clientLoadOrder()`：客户端注入顺序（已加载插件 id，被依赖者在前；环只打乱顺序并记 WARN，不会让插件消失）；
- `clientDependencyWarnings()`：缺失的客户端依赖对应的 HTML 注释片段（core 已经自动塞进 `<head>`）；
- `headProviders(siteWideOnly)`：按客户端依赖顺序排好的 `HtmlHeadProvider` 列表（同一插件内仍按 `order()` 升序）。

### 插件对 core / common 做 AOP（`CoreAopHook`）

插件在 `onStart` 里注册 `CoreAopHook`（接口在 `common/extension`），就能对 **core 自己的 Bean**
（以及插件自己插件容器里的 Bean）加切面：

```java
public interface CoreAopHook extends Extension {
    /** 插件提供的 Spring AOP Advisor（PointcutAdvisor / DefaultPointcutAdvisor 等）。 */
    List<Advisor> advisors();
}
```

```java
// 插件侧：统计 core 的站点设置读取次数（core 的类名只能用字符串，插件不依赖 core）
NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
pointcut.addMethodName("get");
pointcut.setClassFilter(type -> isSiteSettingsService(type));   // 按类名判断
context.register(CoreAopHook.class, () -> List.of(
        new DefaultPointcutAdvisor(pointcut, invocation -> { counter.incrementAndGet(); return invocation.proceed(); })));
```

core 侧接线：

- `core/plugin/aop/PluginAopRegistry`（`@Component`）：把所有插件 `CoreAopHook.advisors()` 汇总成不可变快照，
  Advisor 顺序 = 插件加载顺序 → 插件内声明顺序；插件卸载（`PluginManager.stop()`）后快照自动变空；
- `core/plugin/aop/PluginAopBridge`（`@Component`，`SmartInstantiationAwareBeanPostProcessor`）：
  在 **Bean 创建阶段**给可能被切中的 core Bean 套一层 `ProxyFactory` 桥接代理
  （`PluginAdviceBridge`：一个「活点切面」，调用时现取插件 Advisor），
  因此插件在应用就绪后才加载，**切面对已经注入到其它 Bean 的引用同样生效**（core 全部用构造器注入）；
- 插件子容器里也会挂一份同样的桥（`PluginSpringContextFactory.registerAopBridge`），
  所以插件对自己的 Bean 加切面也生效；
- 代理范围（**默认安全：宁可少代理，也不能让应用起不来**）：
  - 开关 `wikikernel.plugins.aop-bridge`：`auto`（默认）= **只有插件在描述文件里声明 `plugin.aop=true` 时才启用**
    （一个插件都不声明 → 一个代理都不建，零开销、零行为变化）；`true` = 强制开（所有插件的 `CoreAopHook` 都生效）；
    `false` = 关。
  - 只处理 `cc.thonly.wikikernel.*` 自己的单例 Bean，**且必须能被安全代理**：跳过框架 Bean
    （Spring / Thymeleaf / MyBatis / JDBC）、**Servlet 容器相关 Bean**（`Filter` / `GenericFilterBean` /
    `FilterRegistrationBean` / `Servlet` / `ServletRegistrationBean` / `ServletContextInitializer` /
    `HandlerInterceptor` / `WebMvcConfigurer`）、数据源与事务管理器、各种 `*Aware` 基础设施、
    `ApplicationListener`、`BeanPostProcessor` / `BeanFactoryPostProcessor` / `FactoryBean` / `Advisor` /
    `Pointcut`、`@Configuration` 与 `@ConfigurationProperties`、`@Bean` 工厂方法产出的 Bean、
    已经是 AOP / JDK 代理的 Bean（`@Transactional`、MyBatis Mapper）、`final` / 非 public 类；
  - **类（或其父类）里有非 private、非 static 的 `final` 方法的 Bean 一律不代理**：CGLIB 覆盖不了 final 方法，
    它会直接在**代理实例**上执行，而代理实例的字段是空的（踩过的坑：Servlet `GenericFilterBean.init()` 是 final，
    读到 `this.logger == null` → NPE → Tomcat 起不来；`@SpringBootTest` + MockMvc 不经 Tomcat，发现不了这类问题）；
  - 代理创建失败（类型不支持、CGLIB 报错等）只记 **WARN 并跳过该 Bean**，绝不影响启动；
- 没有命中任何切面的方法**只多一次判断**，直接调用原对象（行为与没有插件时一致）。
  为什么不是「创建时逐个方法判断是否命中」：`ProxyFactory` / CGLIB 在**代理创建时**就为每个方法固定了回调
  （`CglibAopProxy.ProxyCallbackFilter` 会按当时的通知链把方法编译成「直接调用目标」），而插件是在 core Bean
  创建完成之后才加载的 —— 创建时链为空的方法，之后注册的 Advisor 永远不会生效；平台因此**收窄 Bean 范围**、
  把方法匹配放到每次调用时做（`PluginAdviceBridge` 用 `Pointcut.TRUE` 保证调用一定进入桥接切面）；
- 容错：`advisors()` 抛异常只记 WARN 并跳过该插件；单个 Advisor 的 pointcut 抛异常只记 WARN 并跳过该 Advisor
  （不影响同一插件里其它 Advisor，也不会让请求 500）；插件卸载后代理壳变成直通，**无需重启或重新注入**；
- 限制：core 不带 `aspectjweaver`，pointcut 请用 Spring 原生实现
  （`NameMatchMethodPointcut` / `JdkRegexpMethodPointcut` / `AnnotationMatchingPointcut`），
  `@Aspect` / `execution(...)` 不可用；`@Controller` 的 handler 实例由 MVC 在启动时缓存，
  切面作用于控制器 Bean 的**方法调用**（其它 Bean 注入的引用没问题），如需拦截请求链路建议切服务层。

最小示例见插件 [`plugin/dep-demo`](../plugin/dep-demo/README.md)（`installPlugins` 不会把它复制进 `plugins/`）。

## 内置插件（MediaWiki 扩展移植）

`plugin/` 下随仓库提供了 **28 个**插件（26 个 MediaWiki 扩展移植 + 皮肤插件 [`plain-skin`](https://www.mediawiki.org/wiki/Manual:Skins) + 自研的浏览器指纹登录 `fingerprint-login`；另有演示插件 `dep-demo` 只构建、不随发行；`.\gradlew.bat installPlugins` 构建并把 jar 复制到根目录 `plugins/` 后加载）：

| 插件 id | 对应扩展 | 能力 |
| --- | --- | --- |
| `no-title` | [NoTitle](https://www.mediawiki.org/wiki/Extension:NoTitle) | `__NOTITLE__` / `__无标题__` 隐藏页面大标题 |
| `variables` | [Variables](https://www.mediawiki.org/wiki/Extension:Variables) | `#vardefine` / `#vardefineecho` / `#var` / `#varexists` / `#var_final` 页面级变量 |
| `hit-counters` | [HitCounters](https://www.mediawiki.org/wiki/Extension:HitCounters) | 浏览计数：`{{NUMBEROFVIEWS}}` / `{{NUMBEROFPAGEVIEWS}}`、`特殊:最多浏览`、页脚计数 |
| `wiki-seo` | [WikiSEO](https://www.mediawiki.org/wiki/Extension:WikiSEO) | `{{#seo:}}` / `<seo>`：标题改写、description/keywords、OpenGraph / Twitter / SchemaOrg / Citation / DublinCore、canonical / hreflang / robots / 站点验证 |
| `template-data` | [TemplateData](https://www.mediawiki.org/wiki/Extension:TemplateData) | `<templatedata>` 模板参数文档（页面文档表格）+ `action=templatedata` API |
| `category-tree` | [CategoryTree](https://www.mediawiki.org/wiki/Extension:CategoryTree) | `<categorytree>` 标签 / `#categorytree` 函数、`特殊:分类树`、AJAX 展开、`action=categorytree` API |
| `input-box` | [InputBox](https://www.mediawiki.org/wiki/Extension:InputBox)（**原 core 内置，已拆出**） | `<inputbox>` 创建 / 搜索表单 + 前端组装脚本 |
| `easy-timeline` | [EasyTimeline](https://www.mediawiki.org/wiki/Extension:EasyTimeline)（**原 core 内置，已拆出**） | `<timeline>` 服务端渲染成内联 SVG |
| `math` | [Math](https://www.mediawiki.org/wiki/Extension:Math)（**原 core 内置，已拆出**） | `<math>` / `<chem>` 公式占位（KaTeX 前端渲染） |
| `flash-player` | [Flash 播放器](https://www.mediawiki.org/wiki/Extension:FlashPlayer)（**原 core 内置，已拆出**） | `<flash-player>` 占位（Ruffle 前端播放） |
| `popups` | [Popups](https://www.mediawiki.org/wiki/Extension:Popups)（**原 core 内置，已拆出**） | 悬停链接显示摘要浮层（预览 API 仍在 core） |
| `graph` | [Graph](https://www.mediawiki.org/wiki/Extension:Graph)（**原 core 内置，已拆出**） | `<graph>` 标签与 `图表:` 命名空间整页图（Vega / Vega-Lite 前端渲染） |
| `media-viewer` | [TimedMediaHandler](https://www.mediawiki.org/wiki/Extension:TimedMediaHandler) + [MultimediaViewer](https://www.mediawiki.org/wiki/Extension:MultimediaViewer)（**原 core 内置，已拆出**） | 音视频播放器（词条嵌入 + 文件页）与图片放大查看 |
| `widget` | [Widgets](https://www.mediawiki.org/wiki/Extension:Widgets)（**原 core 内置，已拆出**） | `{{#widget:名称\|参数=值}}` 调用「微件:」页面（HTML + 迷你 Smarty） |
| `gadgets` | [Gadgets](https://www.mediawiki.org/wiki/Extension:Gadgets)（**原 core 内置，已拆出**） | `WikiKernel:Gadgets-definition` 定义、按用户加载 Gadget JS/CSS、`特殊:Gadgets`、参数设置分区 |
| `page-banner` | [WikidataPageBanner](https://www.mediawiki.org/wiki/Extension:WikidataPageBanner)（**原 core 内置，已拆出**） | `{{PAGEBANNER:文件\|caption=说明}}` / `<exclude-banner-page />`：标题上方横幅（白名单 / 逐页配置 / 轮换） |
| `confirm-edit` | [ConfirmEdit](https://www.mediawiki.org/wiki/Extension:ConfirmEdit)（**原 core 内置，已拆出**） | 编辑 / 创建页面的算术验证码：编辑页表单字段（`EditFormField`）+ 保存前校验（`EditGuard`） |
| `registration-quiz` | [注册问答验证](https://www.mediawiki.org/wiki/Extension:QuestyCaptcha)（仿 QuestyCaptcha）（**原 core 内置，已拆出**） | 两个等长数组随机抽题：注册页表单字段（`RegistrationFormField`）+ 注册前校验（`RegistrationGuard`） |
| `comments` | [Comments](https://www.mediawiki.org/wiki/Extension:Comments)（**原 core 内置，已拆出**） | `<comment />` / `<comments />` 页面评论区：发表 / 回复（wikitext）/ 软删除 + `/api/comments`，头像与权限走 common 桥 |
| `multi-upload` | [MsUpload](https://www.mediawiki.org/wiki/Extension:MsUpload)（**原 core 内置，已拆出**） | 多文件批量上传：上传页多选列表（可改名 / 逐个上传 / 插入画廊）+ 编辑器上传对话框批量插入 |
| `social-profile` | 用户交互 / [SocialProfile](https://www.mediawiki.org/wiki/Extension:SocialProfile)（**原 core 内置，已拆出**） | 用户页资料卡（头像 + 私信 / 上传头像链接）、头像上传 `POST /api/user/avatar` 与 `/avatars/<文件名>`、`特殊:上传头像`、`特殊:私信`（收件箱 / 发件箱 / 会话 / 写私信）+ `/api/messages/*`、顶栏「私信（N）」 |
| `abuse-filter` | [AbuseFilter](https://www.mediawiki.org/wiki/Extension:AbuseFilter)（**原 core 内置，已拆出**） | 滥用过滤器：条件求值、过滤器管理 / 历史 / 测试（`特殊:滥用过滤器`）、命中日志（`特殊:滥用日志`）、编辑 / 移动 / 注册 / 上传的拦截与警告 |
| `moderation` | [Moderation](https://www.mediawiki.org/wiki/Extension:Moderation)（**原 core 内置，已拆出**） | 审核：编辑 / 创建、上传（先暂存）、删除进队列，`特殊:审核` 通过 / 拒绝 + `/api/moderation/*`、待审提示条与「待审创建」页 |
| `ai-agent` | **WikiKernel 自研**（MediaWiki 无对应扩展；可参考 [AI 类扩展分类](https://www.mediawiki.org/wiki/Category:Artificial_intelligence_extensions)）（**原 core 内置，已拆出**） | AI 代理：多厂商对话（OpenAI 兼容 / Anthropic / Gemini / Mock）+ 只读工具（read / search / list / info / history）+ 待确认提案（预览 / 差异 / 可改正文），`特殊:AI代理` + `/api/ai-agent/*` + `/api/ai-keys/*`、参数设置里的「AI 代理（我的密钥）」分区与 `特殊:AI代理?keys=1`（站点密钥 + 放开普通用户） |
| `rename-user` | [Renameuser](https://www.mediawiki.org/wiki/Extension:Renameuser)（**原 core 内置，已拆出**） | 重命名用户账号（可一并移动用户页 / 用户讨论页及子页面）：`特殊:重命名用户` + `POST /api/user/rename`，需「重命名用户」权限组，写 `renameuser` 日志 |
| `user-merge` | [UserMerge](https://www.mediawiki.org/wiki/Extension:UserMerge)（**原 core 内置，已拆出**） | 核查员专用：把源账号数据（编辑历史 / 日志 / 权限 / 文件）归入目标账号、可选删除源账号：`特殊:用户合并` + `POST /api/user-merge`，写 `usermerge` 日志 |

> **改名说明**：该插件原名 `user-interaction`（模块目录 `plugin/user-interaction/`），现已改名为 **`social-profile`**——
> 模块目录 `plugin/social-profile/`、静态资源前缀 `/plugins/social-profile/`、样式改名 `social-profile-base.css` / `social-profile-mobile.css`、
> 消息键前缀 `plugin.social-profile.*`。**旧配置无需改动**：开关 `wikikernel.features.user-interaction` 仍然生效
>（新键 `features.social-profile` 优先，未设置时回落旧键），`LocalSettings.yaml` 里的旧键可继续使用；
> 数据表名未变（`wiki_user_board` / `wiki_user_relation` / `wiki_user_gift` / `wiki_user_stats` / `wiki_user_award`），**数据无需迁移**。

### 与 MediaWiki SocialProfile 的差异

| 项 | MW SocialProfile | 本插件（social-profile） |
| --- | --- | --- |
| 资料页 | `Special:EditProfile` + `user_profile` 表 | `特殊:编辑资料`；字段存用户偏好（`socialProfile.*`），无独立表、无 full name / 自定义字段 |
| 留言板 | 嵌套回复、板主置顶、附件 | 单层回复（父留言 + 一层回复）、软删除占位；无附件 / 置顶 |
| 好友 | 请求 → 接受，`user_relationship` 表 | 一致：FRIEND / FOE、PENDING / ACCEPTED、双向两行；`?tab=friends\|foes\|requests` |
| 礼物 | `user_gift` + `gift` 表（管理员可加礼物） | 8 种内置礼物（名称走 i18n）；管理员不能加自定义礼物；附言为纯文本 |
| 积分等级 | `user_stats` + `user_level`，有管理维护页 | `wiki_user_stats`；等级由阈值表换算并在给分时落库；无批量维护页 |
| 给分入口 | 编辑保存等事件 | core 目前没有「编辑保存成功」事件（`EditGuard` 只在保存前拦截），故**用 `PageViewHook` 浏览词条代偿**（同页同人默认 300 秒一次）；社交动作精确给分 |
| 奖励 | `Special:Awards`（管理员授予，`user_award`） | 一致：6 种内置奖励、管理员授予/撤销、同一种不重复授予 |
| 随机用户 | `Special:RandomUsersWithAvatars` | 一致：`特殊:随机用户`（默认 12 个，上限 48） |
| 用户页版式 | DIY 版式 / `UserPage` 皮肤 | 无；资料卡仍由 core 渲染，插件只填头像/链接 + 正文下方面板 |
| 通知 | Echo 集成（新留言 / 好友请求 / 礼物） | 仅顶栏徽标：私信(N) / 好友(N)（待处理请求）/ 礼物(N)（未查看的新礼物） |
| 隐私 | 逐字段可见性 | 5 个展示开关（生日 / 现居地 / 礼物 / 勋章 / 留言板） |
| 头像 / 表情 | 头像多尺寸 + 表情符号 | 沿用 core 已有头像能力（上传/替换/文件服务），无表情系统 |

「对应扩展」列的外链指向 mediawiki.org 上该扩展的介绍页（`ai-agent` 是 WikiKernel 自研，给的是 AI 类扩展分类页）。
**`特殊:版本` 的「已安装的扩展」表列**：扩展 / 插件 ID / 版本 / 说明 / JAR 包名——「说明」列写的是**原版 MediaWiki 扩展的用途**
（`version.extension.<插件 id>.description`，中英各一份），末尾的「（原插件）」链接指向该扩展官方页（`version.extension.<插件 id>.url`）；
扩展名本身是纯文本，不再外链；自研插件（如 `fingerprint-login`）与皮肤没有「原插件」，只显示自身说明、不带链接。

每个插件都有 `plugin/<id>/README.md`，写清移植范围、配置键与**与 MediaWiki 原版的差异**。

> **演示 / 测试插件 `plugin/dep-demo`**（**不是** MediaWiki 扩展移植）：用来演示并回归上面三项平台能力
> （`plugin.requiresCore` + `plugin.clientRequires` + `CoreAopHook`，见「插件依赖声明」与「插件对 core / common 做 AOP」），
> 它同样只依赖 `common`（core 的类名以字符串出现）。`installPlugins` **不会**把 `*-demo-*.jar` 复制进 `plugins/`，
> 因此真实部署不会加载它；想手动试玩时把 `plugin\dep-demo\build\libs\*.jar` 复制到 `plugins\` 后重启即可。

### 插件化进度（哪些还留在 core）

**已完成（渲染 / 展示层，全部构建与测试通过：core 573 用例 + 17 个插件 285 用例）**：
[NoTitle](https://www.mediawiki.org/wiki/Extension:NoTitle)、[Variables](https://www.mediawiki.org/wiki/Extension:Variables)、[HitCounters](https://www.mediawiki.org/wiki/Extension:HitCounters)、[WikiSEO](https://www.mediawiki.org/wiki/Extension:WikiSEO)、[TemplateData](https://www.mediawiki.org/wiki/Extension:TemplateData)、[CategoryTree](https://www.mediawiki.org/wiki/Extension:CategoryTree)、
[InputBox](https://www.mediawiki.org/wiki/Extension:InputBox)、[EasyTimeline](https://www.mediawiki.org/wiki/Extension:EasyTimeline)、[Math](https://www.mediawiki.org/wiki/Extension:Math)、[Flash 播放器](https://www.mediawiki.org/wiki/Extension:FlashPlayer)、[Popups](https://www.mediawiki.org/wiki/Extension:Popups)、
**[Graph](https://www.mediawiki.org/wiki/Extension:Graph)**、**多媒体预览（[TimedMediaHandler](https://www.mediawiki.org/wiki/Extension:TimedMediaHandler) + [MultimediaViewer](https://www.mediawiki.org/wiki/Extension:MultimediaViewer)）**、**[Widgets](https://www.mediawiki.org/wiki/Extension:Widgets)**、
**[Gadgets](https://www.mediawiki.org/wiki/Extension:Gadgets)**、**[WikidataPageBanner](https://www.mediawiki.org/wiki/Extension:WikidataPageBanner)**。

**已完成（治理 / 交互层，本批：「编辑 / 注册钩子」平台能力 + 三个插件；`.\gradlew.bat build`
全量通过：core 588 用例 + 插件用例 335，全部 0 失败）**：
**ConfirmEdit（编辑验证码，插件 `confirm-edit`）**、**注册问答验证（插件 `registration-quiz`）**、
**评论（插件 `comments`：`<comment />` 标签 + 正文下方评论区 + `/api/comments` + 脚本 / 样式）**、**多文件批量上传（插件 `multi-upload`，仿 MsUpload）**
（confirm-edit / registration-quiz 各 18 个用例，comments 7 个；扩展点平台本身由 core 的
`PluginEditHookTest` 10 个用例覆盖）。

**已完成（用户交互层，本批插件 `social-profile`：用户页资料卡钩子 + 个人工具区钩子；`.\gradlew.bat :core:test`
与 `.\gradlew.bat :plugin:social-profile:build` 全量通过：core 592 用例 + 插件 59 用例，全部 0 失败）**：
**用户交互（头像上传 + 用户间私信，插件 `social-profile`）**——头像 API / `/avatars/<文件名>` / `特殊:上传头像` /
`特殊:私信` / `/api/messages/*` / 资料卡头像与「上传头像 / 私信」链接 / 顶栏「私信（N）」/ 脚本 / 样式 / 文案全部迁出，
core 只留站点设置开关与两个渲染槽位钩子（`UserProfileHook` / `PersonalToolbarHook`，
由 core 的 `PluginUserHookTest` 7 个用例覆盖接线）。

**已完成（滥用过滤器，插件 `abuse-filter`；`.\gradlew.bat build` 全量通过）**：
**滥用过滤器（AbuseFilter）**——条件求值器（`abuse/*` 1133 行）、过滤器 CRUD 与历史恢复、
`特殊:滥用过滤器` / `特殊:滥用日志` / `/api/abuse-filter/*`、前端脚本与样式、命中日志与变量快照
全部迁入插件；评估入口改成平台钩子（编辑 → `EditGuard`，移动 / 上传 / 删除 / 封禁 → 新的 `ActionGuard`，
注册 → `RegistrationGuard`），core 侧接线由 `PluginActionHookTest` 6 个用例覆盖；
数据仍存 3 张滥用表（经 `common.service.AbuseFilterStore`），系统账号「滥用过滤器」的创建走
`common.service.UserWriter`；core 只保留站点设置开关（`features.abuse-filter`）。
插件测试 41 个用例（表达式 8 / 服务 14 / 钩子 10 / 插件 9）。

**已完成（审核，插件 `moderation`；`.\gradlew.bat :core:test` + `.\gradlew.bat :plugin:moderation:build`
全量通过：core 594 用例 + 插件 30 用例，全部 0 失败）**：
**审核（Moderation）**——队列服务（原 `ModerationServiceImpl` 357 行）、`特殊:审核`（含状态 / 类型 / 标题过滤、
页签计数、行级差异、上传预览）、`/api/moderation/*`、前端脚本与样式、待审提示条与「待审创建」页、
全部文案与两个日志类型（`moderation` / `moderationreject`）全部迁入插件；入口改成平台钩子
（编辑 / 创建 → `EditGuard.HANDLED` + 跳转，上传 / 删除 → 新的 `ActionGuard.HANDLED` +
`ActionContext.payload()`，编辑页提示 → `EditFormField.notice`，提示条 / 待审创建页 → 新的
`PageDisplayHook.beforeContent` / `renderMissingPage`）；数据仍是 `wiki_pending_change` 表
（`common.service.ModerationStore`）与 `{run-path}/pending/` 暂存（`PendingFileStore`），
通过待审编辑走 `PageWriter`（以原提交人身份 + AI 水印）、文件写入 / 删除走新的 `FileWriter` 桥，
差异走新的 `WikiDiff` 桥；core 只保留站点设置开关（`features.moderation`）与数据表；
插件测试 30 个用例（服务 11 / 钩子 10 / 装配与特殊页 9），平台接线由 core 的 `PluginActionHookTest`
（HANDLED + 载荷 + api.php 上传）、`PluginPageDisplayHookTest`（提示条 / 待审创建页）、
`PluginDataBridgeTest`（`FileWriter` / `WikiDiff`）覆盖。

**已完成（AI 代理，插件 `ai-agent`；`.\gradlew.bat build installPlugins` + `:core:test --tests '*PluginRuntimeLoadTest*'`
全量通过：core 574 用例 + 插件 43 用例，全部 0 失败；25 个插件 jar 全部成功启动）**：
**AI 代理（AI Agent）**——`ai/*` 包（厂商适配器、会话编排、工具循环、提案）、`特殊:AI代理` + 模板、
`/api/ai-agent/*`、密钥接口（`/api/ai-provider-keys` → 插件的 `/api/ai-keys`）、参数设置里的
「AI 代理（我的密钥）」标签页（改由 `PreferenceSection` 贡献，保存走 `POST /api/ai-keys`）、
站点密钥与「允许普通用户使用」（`特殊:AI代理?keys=1`，原在 core 站点设置 / `/init` 的「配置项」）、
前端脚本与样式（`/plugins/ai-agent/…`，原 `head.html` + `base.css`）、`MarkdownRenderer`、
`aiAgent.*` / `preferences.ai.*` / `preferences.tab.ai` / `special.aiAgent` / `settings.aiVendors*` /
`settings.aiAgentAllowNormal*` 文案全部迁入插件；**确认提交改走 `common.service.EditSubmitter`**
（`Request.aiAssisted(...)`：先跑平台保存前钩子——插件版滥用过滤器等，来源 `source=ai`；再按乐观锁保存
并打 AI 水印），页面 / 版本读取走 `WikiPageStore` + 新的 `PageQueryStore`，渲染 / 差异 / 搜索 / 链接 / 分类 /
权限 / 用户名 / 限流分别走 `WikiText` / `WikiDiff` / `SearchStore` / `PageLinkStore` / `CategoryStore` /
`AccessCheck` + `CurrentUser` / `UserStore` / `RateLimit`；
core 只保留站点设置开关 `features.ai-agent` 与 AI 版本的隐藏水印渲染（`aiAgent.watermarkTitle` / `.mw-ai-watermark`）；
`wikikernel.ai.*` 配置、`ai.provider.<id>.*` / `ai-agent.allow-normal-users` 站点设置键与 `wiki_user_ai_key` 表
**现在都归插件自己**（插件内 `AiProperties` + `AiConfig`/`AiConfigService`；表由 `WikiPlugin.schemaEntities()`
声明、core 自动建表，数据访问走 `PluginDatabase`）；
插件测试 43 个用例（服务与提案 17 / 密钥 4 / 接口 6 / 装配与界面 9 / Markdown 4 / SOCKS5 桥 3），
core 侧「插件未加载」语义由重写后的 `AiAgentTest` 用例覆盖，平台桥由 `PluginDataBridgeTest` 覆盖。

**已完成（用户账号管理，本批插件 `rename-user` / `user-merge`；`.\gradlew.bat :plugin:rename-user:build` 与
`.\gradlew.bat :plugin:user-merge:build` 全量通过，插件测试全部 0 失败）**：
**重命名用户（插件 `rename-user`）** 与 **用户合并（插件 `user-merge`）**——`特殊:重命名用户` / `Special:RenameUser` +
`POST /api/user/rename`、`特殊:用户合并` / `Special:UserMerge` + `POST /api/user-merge`、前端脚本
（`/plugins/rename-user/`、`/plugins/user-merge/`）、`renameUser.*` / `special.renameUser` / `userMerge.*` /
`special.userMerge` / `log.typeName.renameuser` / `log.typeName.usermerge` / `log.renameuser.*` / `log.usermerge.*`
文案全部迁入插件；core 只保留「重命名用户」（`GROUP_RENAME_USER`）与「核查员」（`GROUP_CHECKUSER`）两个用户组、
`common.service.UserAdminStore` / `UserMergeStore` 两个桥（改名 / 合并的数据与规则逻辑仍在 core，内部调
`UserService.rename` / `UserMergeServiceImpl.merge`）与用户页「工具」侧栏的 `tools.userMerge` 入口；
**core 未加载插件时 `特殊:重命名用户` / `特殊:用户合并` 与 `/api/user/rename` / `/api/user-merge` 都不存在（404）、
core 不写 `renameuser` / `usermerge` 日志**；core 侧「插件未加载」语义由重写后的 `RenameUserTest` / `UserMergeTest`
各 2 个用例覆盖，插件的完整行为（表单 / 接口 / 结果映射 / 权限拒绝 / 日志类型）由插件测试覆盖
（rename-user 9 个用例、user-merge 8 个用例）。

**core 里已没有未迁出的内置功能**：`plugin/` 下的 26 个功能插件（外加皮肤插件 `plain-skin`）覆盖了
原先 core 内置的全部可选能力。core 仍然保留（**按设计留下，不属于「未迁出」**）：

| core 保留的东西 | 为什么 |
| --- | --- |
| `features.*` 功能开关（站点设置 / `/init` / LocalSettings.yaml）与各插件的配置项 | 插件通过 `common.service.SiteConfig` / `PluginContext.config()` 读同一个键，开关语义在迁移前后一致 |
| 数据表与 Auto-DDL（`wiki_pending_change` / `wiki_comment` / `wiki_message` / `wiki_abuse_filter*` / `wiki_user_ai_key` …） | 插件经 `common.service.*` 桥读写，表结构与历史数据不动 |
| 页面元数据（AI 水印、评论标记、分类索引、页面属性…） | 属于页面 / 版本的自身字段，渲染与索引由 core 统一负责 |
| 平台桥（`common.service.*`）与扩展点（`common.extension.*`） | 插件的唯一依赖面；新增能力时在这里加桥而不是把 core 服务搬进插件 |

> 说明：`features.*` 功能开关的**归属**分两类：
> - **插件开关**（`features.<插件id>`，共 19 个：comments / multi-upload / widgets / gadgets / abuse-filter /
>   media-viewer / moderation / input-box / popups / flash-player / math / timeline / graph / ai-agent /
>   fingerprint-login / social-profile / page-banner / confirm-edit / registration-quiz）由插件在自己的
>   `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），core 自动在
>   「站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存，插件用 `common.service.SiteConfig` 读同一个键——
>   **core 里没有这些开关的代码**（未加载插件时开关与分区都不存在）；
> - **core 自己的开关**只剩 `features.register` 与 `features.typescript` / `features.typescript-compile-on-save`
>   （注册与 TypeScript 编译是 core 功能，见 `FeatureService`）。
> `LocalSettings.yaml` 的 `wikikernel.features.*` 语义不变：写了的值每次启动导入站点设置（插件开关由插件导入）。

### 插件扩展点（本批移植新增，接口定义在 `common/extension`）

| 扩展点 | 用途 | core 消费位置 |
| --- | --- | --- |
| `ParserFunction` | `{{#name:参数}}` 解析器函数（参数原文 + `expand()` 按当前 frame 展开 + `trustedHtml()` 登记可信块）；不带 `#` 的 `{{name:参数}}`（如 `{{PAGEBANNER:…}}`）同样按名字派发 | `WikitextRenderer.parserFunction` / `pluginColonFunction`（内置 #if/#ifeq/#switch/#invoke 优先，未注册的名字仍按模板解析） |
| `MagicWord` | `{{NAME}}`（含本地化别名） | `WikitextRenderer.magicWord`（内置魔术字优先） |
| `BehaviorSwitch` | `__NAME__` 行为开关，可写页面属性 | `WikitextRenderer.renderWithMeta` |
| `ParserTag` | `<tag>内容</tag>`；`expandBody()=false` 时内容不参与模板展开（如 `<templatedata>` 的 JSON） | `WikitextRenderer.protectPreBlocks` / `extractPluginTags` |
| `HtmlHeadProvider` | `<head>` 注入（meta / link）与 `<title>` 覆盖；`siteWide()=true` 时注入**所有**页面（脚本片段在 core 站点级配置之后，可直接用 `window.WIKI_CONFIG` / `WIKI_MESSAGES`） | `WikiPageController.applyHeadExtensions`（阅读视图，只取**页面级**实现，带本次渲染属性）+ `PluginHeadAdvice`（所有页面，只取**站点级**实现；两边各管一半、同一片段不会注入两遍） |
| `PageViewHook` | 词条阅读（非 diff / 旧版本）时的事件（用户 / 机器人 / 用户组） | `WikiPageController.firePageView` |
| `PageDisplayHook` | 页面显示：`renderBody()` 认领整页正文（`图表:` / `微件:` 这类非 wikitext 页）、`renderMissingPage()` 认领**不存在的标题**（审核的「待审创建」页）、`beforeTitle()` 标题上方（页面横幅）、`beforeContent()` 正文之前（待审提示条）、`afterContent()` 正文下方（评论区）、`headItems()` 该页额外 head | `PageDisplayHookService`（core 的服务，统一按 `order()` 升序 + 异常容错）+ `WikiPageController`（阅读视图；皮肤布局与 `wiki/view.html` 有对应槽位；缺失页用通用外壳 `templates/wiki/plugin-missing-page.html`） |
| `FileEmbedRenderer` | `[[文件:X]]` 嵌入与文件页预览的渲染（多媒体播放器） | `WikitextRenderer.renderFileEmbedding`、`WikiPageController.renderFilePage` |
| `PreferenceSection` | 参数设置里由插件贡献的标签页（core 渲染标签 + 面板，表单由插件自己提交） | `PreferencesSpecialPage.populate` |
| `SiteSettingsSection` | **站点设置页里由插件贡献的分区**：插件声明标题与字段（键 = 站点设置键，类型 `text` / `textarea` / `checkbox` / `select`），core 渲染成表单并在保存时按声明的键写站点设置（checkbox 未勾选 = false）——core 不认识这些设置属于哪个功能 | `AccessControlSpecialPage`（模型键 `pluginSettings`，模板 `special/access-control.html` 的「插件设置」区）+ `SiteSettingsApiController.save`（表单里的隐藏 `plugin-section` 标记） |
| `plugin.featureKey`（描述文件） | **插件功能开关**：插件在 `META-INF/wiki-plugin.properties` 里写 `plugin.featureKey=features.<插件id>` 与 `plugin.featureDefault=true|false`，core 自动在「站点设置 → 插件设置」渲染一个复选框（键 = 声明的开关，标签 = 插件的本地化名），安装向导的插件 tab 也自动带上它（写 `wikikernel.<开关键>`）；插件自己用 `SiteConfig` 读同一个键——**core 里没有该开关的代码**，插件没加载时开关也不出现 | `PluginFeatureSections`（分区与开关合并，`/api/plugins` 也返回 `featureKey` / `featureDefault`）+ `SetupService.pluginTabs` / `applyPlugin` |
| `SetupStep` | 安装向导里插件的设置入口（一个插件一个 tab；`SetupField` 支持 `text` / `password` / `checkbox` / `select` / `textarea`，`apply()` 经 `SetupContext.putGlobal` 写 `wikikernel.*`） | `SetupService.steps()` / `SetupController`；插件未加载时 tab 不存在 |
| `ActionApiModule` | 插件自己的 `/api.php?action=…` 模块（含页面 / 属性 / 正文查询上下文） | `ActionApiService.pluginAction` |
| `EditGuard` | **保存前钩子**：网页编辑表单与 `api.php?action=edit` 写入正文前按 `order()` 升序调用；`ALLOW` 放行 / `BLOCK(message)` 拒绝（网页回到编辑表单并保留已填内容与摘要，API 返回错误）/ `WARN(message)` 放行但保存后提示一次 / `HANDLED(redirect)` 钩子已自行处理（core 不再保存） | `EditHookService.beforeSave`（`WikiPageController.submitEdit` = `source=web`；`ActionApiService.edit` = `source=api`） |
| `EditFormField` | **编辑页表单字段**：编辑框下方（摘要栏之后、保存按钮之前）插入 HTML（验证码的题目 + 输入框），`notice()` 在保存按钮附近显示提示 | `WikiPageController.applyEditFormExtensions` + `templates/wiki/edit.html` 的 `editFormFields` / `editFormNotices` 槽位 |
| `RegistrationGuard` | **注册前钩子**：创建账号前按 `order()` 升序调用；`BLOCK(message)` = `POST /api/user/register` 返回 400 + 说明 | `RegistrationHookService.beforeRegister`（`ApiController.register`） |
| `RegistrationFormField` | **注册页表单字段**：注册表单里（电子邮件之后、提交按钮之前）插入 HTML（注册问答的题目 + 隐藏下标 + 答案输入框） | `RegisterSpecialPage.populate` + `templates/special/register.html` 的 `registerFormFields` 槽位 |
| `UserProfileHook` | **用户页资料卡槽位**（仿 `SkinTemplateNavigation` / SocialProfile）：`avatarUrl()` 给资料卡头像（null / 空 = 默认头像）、`linksHtml()` 给「贡献 \| …」一行里的额外链接（如「私信」「上传头像」）；`order()` 升序，头像取第一个非空 | `UserProfileHookService` + `web/UserProfileAdvice`（从请求 URI 的 `用户:X` / `User:X` 解析资料卡用户，模型键 `userPageName` / `userAvatarUrl` / `userProfileLinks`；模板 `fragments/user-profile.html`） |
| `PersonalToolbarHook` | **顶栏个人工具区槽位**（仿 `PersonalUrls`）：`itemsHtml()` 给 `<li>…</li>` 级别的额外条目（如「私信（N）」未读计数）；`order()` 升序、null / 空 = 不插入 | `PersonalToolbarHookService` + `web/PersonalToolbarAdvice`（模型键 `pluginPersonalTools`；模板 `fragments/personal-tools.html` 在最后一项之后渲染） |
| `ActionGuard` | **通用动作钩子**（编辑以外的动作）：`beforeAction(ActionContext)` 按 `order()` 升序执行，`BLOCK(message)` 拒绝 / `WARN(message)` 放行并提示 / `HANDLED(message, data)` 钩子已自行处理（core **不执行该动作**，当作成功返回并把 `data` 并入响应，审核的上传 / 删除排队用它）；动作名（move / upload / delete / protect / block）、目标、动作相关数据、来源（web / api）在上下文里，上传动作的 `payload()` 是文件内容（钩子可自行暂存） | `core/service/ActionHookService`，接在 `PageApiController`（移动 / 保护）、`FileApiController`（上传 / 删除）、`BlockApiController`（封禁）、`ActionApiService`（`api.php?action=upload`） |
| `LogType.detailText()` | **日志详情文案**（可选）：core 把存库的 `detail` 原样交给类型自己（如审核的 `edit\|审核意见`），插件可本地化 / 结构化解析（返回 null / 空 = 按 core 通用规则展示原始 detail） | `core/log/LogFormatter.detailText`（先问注册的日志类型，再走 core 的内置规则） |
| `CoreAopHook` | **对 core / common 的 Bean 加切面**：`advisors()` 返回 Spring AOP `Advisor`（`DefaultPointcutAdvisor` 等），core 把它们套到匹配的 core Bean 上；插件自己的 Bean 也生效。插件在 `ApplicationReadyEvent` 之后才加载，core 用「桥接代理 + 活点切面」保证**已经注入到其它 Bean 的引用**同样被切中 | `core/plugin/aop/PluginAopRegistry`（汇总 / 容错 / 卸载撤销）+ `PluginAopBridge`（`SmartInstantiationAwareBeanPostProcessor`，Bean 创建阶段建代理）+ `PluginSpringContextFactory.registerAopBridge`（插件子容器） |

渲染上下文与插件可注入的 core 服务：

- `WikiRenderContext`（`ParserFunctionCall.context()` / `ParserTagCall.context()`）：
  `store()` 渲染内变量（跨模板、页面级）、`setProperty()` 页面属性（渲染结束后持久化到新表 `wiki_page_property`）、
  `deferWikitext()` 解析结束后的延迟占位（Variables 的 `#var_final` 用它）；
- `common.service.PagePropertyStore`（core 实现，插件直接 `@Autowired` 注入）：
  页面属性读写、`addToInt` 计数、`sumInt`、`listByIntDesc`、`registerDurable`（登记「持久属性」，页面重新解析保存时不覆盖，浏览计数用它）；
- `common.service.WikiPageStore`：按标题 / ID 查页面、读最新正文、`visibleByTitle`（按查看权限过滤）、
  `latestRevisionId`（按修订号缓存）、列出内容命名空间页面；
- `common.service.CategoryStore`：分类成员（分类 / 页面 / 文件，带排序键与命名空间编号）、页面所属分类、成员统计（CategoryTree 用）；
- `common.service.FileStore`：按文件名查媒体文件（文件名 / 直链 / 说明页 / 是否图片 / 媒体类型；Flash 播放器、页面横幅等用）；
- `common.service.UserSettings`：用户偏好读写（`gadgets` / `skin` / `language` 等走 `wiki_user` 列，
  其它键落 `wiki_user_preference` 键值表）——Gadgets 的「参数设置 → 小工具」用它；
- `common.service.CurrentUser`：当前请求用户与用户组（`Viewer`：id / 名字 / 匿名 / 组集合 / 机器人）——
  Gadgets 的 `rights=` 过滤、评论区与私信判断登录状态都用它；
- `common.service.UserStore`：按 ID / 用户名查用户、批量取用户名与头像、用户组集合（评论作者、私信收件人、
  审核提交人等用）；`UserRef.avatarUrl(defaultUrl)` 直接给头像地址；
- `common.service.WikiText`：用**同一套**渲染器渲染 wikitext 片段（`toHtml` / `toHtmlWithCategories`）——
  评论内容、待审正文预览、AI 提案预览都用它，模板 / 解析器函数 / 白名单全部生效；
- `common.service.LogWriter`：写操作日志（类型 id 用 `common.extension.LogType` 注册，展示文案由 core 本地化）。
- `common.service.PageWriter`：**绕过网页表单**直接保存 / 删除页面（`save(PageWriter.Request)`，可带乐观锁 `expectedRevisionId`、机器人 / 小编辑标记、AI 水印 `aiAssisted`）——审核通过待审更改、AI 代理确认提交用它；表单流程里的东西（验证码、审核排队）不在这一层，调用方自己负责权限与业务校验。
- `common.service.AccessCheck`：按站点访问规则判断动作（`can` / `canRead`）与当前封禁提示（`blockNotice`）——插件在自己的接口 / 页面里做权限判断时用它，不要复制规则逻辑。
- `common.service.FileWriter`：**文件写入桥**（core 的 `FileService` 仍是唯一写入方）——`store(actorUserId, name, description, bytes)` 写入一个文件版本、`delete(actorUserId, fileId, hard)` 删除；审核插件通过待审上传 / 待审删除用它；
- `common.service.WikiDiff`：**行级差异桥**（与差异对比页共用 core 的 `DiffRows` 实现与模板行模型）——`rows(oldContent, newContent)`；审核队列的「当前版本 → 待审内容」用它；
- `common.service.LoginSession`：**登录会话建立桥**（`establish(userId, remember)` / `logout()`）——替代登录方式的插件（指纹登录等）用它建立与 core 密码登录**完全一致**的会话（属性 / 有效期 / 脱敏副本都在 core 实现，插件不抄约定）；
- `common.service.PluginDatabase`：**插件自有表的数据访问桥**（JDBC：`update` / `insert`（回填自增主键）/ `query` / `queryOne` / `transaction`；列名小写、时间 / 布尔 / 数字类型差异由 `DbRow` 抹平）——
  插件用 `WikiPlugin.schemaEntities()` 声明自己的 `@TableName` 实体，core 在 `onStart` 前按 core 的 Auto-DDL 规则建表 / 补列（SQLite / MySQL / H2，只增不减），
  插件专属的实体与数据访问都留在插件里（不再往 `common.data` / `common.service` 塞）；参考实现：`plugin/abuse-filter`（`AbuseFilterStoreJdbc`）。

**子扩展数据层现状**（这一批已全部迁完：实体 / 桥接口 / 数据访问都在插件里，表名 / 列名 / 键名与旧数据完全兼容，core 在插件 `onStart` 前自动建表 / 补列）：

| 插件 | 表（旧数据沿用） | 插件内实现 |
| --- | --- | --- |
| `abuse-filter` | `wiki_abuse_filter` / `_revision` / `wiki_abuse_log` | `AbuseFilterStoreJdbc` |
| `comments` | `wiki_comment` | `CommentStoreJdbc` |
| `moderation` | `wiki_pending_change` + `{run-path}/pending/` 暂存 | `ModerationStoreJdbc` / `PendingFileStoreImpl` |
| `social-profile` | `wiki_user_board` / `_gift` / `_award` / `_stats` / `_relation` / `wiki_message` + 头像文件 | 6 个 `*StoreJdbc` + `AvatarStoreJdbc`（`/avatars/**` 也由插件服务） |
| `fingerprint-login` | `wiki_fingerprint` | `FingerprintStoreJdbc`（登录会话走平台桥 `LoginSession`） |
| `rename-user` | core 的 `wiki_user` / `wiki_log` 等（改名 / 日志） | `UserAdminStoreJdbc` |
| `user-merge` | core 的用户 / 编辑 / 文件表（合并） | `UserMergeStoreJdbc` |
| `ai-agent` | `wiki_user_ai_key` | `AiConfigService`（+ `PluginDatabase`；站点键走 `SiteConfig` 读写，`wikikernel.ai.*` 由插件自己的 `AiProperties` 绑定） |

对应的 core / common 侧实现（`*StoreImpl`、Mapper、`common.data` 实体、`common.service` 桥接口）已经**全部删除**；插件里的每个 JDBC store 都有 H2 真实 SQL 测试（建表语句、列名映射、过滤 / 计数 / 事务路径），core 侧只保留「插件未加载时」的边界测试与仍属平台的桥（本列表外的 `common.service.*`）。
- `common.service.RecentChangesStore`：**最近编辑 / 编辑者概况的只读桥**（`recentEdits(limit)`：版本号 / 页面 / 作者 / 时间 / 摘要 / 正文与上一版正文；`editorStats(userId)`：编辑数 / 注册时间）——滥用过滤器的「测试过滤器」、活动流类扩展用；
- `common.service.UserWriter`：插件创建 / 更新系统账号（不存在才建 + 授予用户组）——滥用过滤器的系统账号「滥用过滤器」用它。
- `common.service.EditSubmitter`：**像一次编辑那样提交**（先跑平台保存前钩子 `EditGuard`，再按乐观锁保存）——`blocked` 拒绝 / `handled` 钩子接管（如审核进队列）/ `warn` 保存并回传提示；AI 代理确认提交这类「没有表单的写入路径」用它，不需要自己拼 `EditContext`。
- `common.service.SiteConfig`：站点设置读写桥（`get` / `getBoolean` / `getInt` / `set`）——**写入与 core 的 `/init`、站点设置页同一个存储**，插件写自己的键（空串 = 清空）；ai-agent 用它维护 `ai.provider.<id>.*` 与 `ai-agent.allow-normal-users`。
- `common.service.KvStore`：**给插件用的 Key-Value 存储**（表 `wiki_kv`，抽象结构 `(id, name, type, value)`，`type` = `string` / `number` / `json`）——
  `get` / `getString` / `getNumber` / `json` / `putString` / `putNumber` / `putJson` / `delete` / `exists` / `list(前缀, limit)` / `increment`；
  适合插件自己的小配置与小状态，**不要**当业务表用（物理列名是 `kv_value`：`value` 在 H2 是保留字）；
- `common.service.PageCache`：页面正文缓存（`enabled` / `html` / `put` / `invalidate` / `invalidateAll` / `invalidateTranscluding`）——插件改内容后可主动失效；
- `common.service.PageQueryStore`：**页面列举 + 版本历史桥**——`namespaces()`（命名空间稳定 id）、`detail(title)`（页面 ID / 最新版本 / 保护状态 / 创建与更新时间）、`list(prefix, namespace, limit)`（按前缀与命名空间列页面，命名空间语义与 `特殊:全部页面` 一致）、`latestRevision(pageId)` / `history(pageId, limit)`（版本元信息：作者 / 摘要 / 大小 / 小编辑 / 机器人 / AI 水印 / 是否删除）——AI 代理的 `read` / `list` / `info` / `history` 工具用它。
- `common.service.PageLinkStore`：**链接索引桥**——`templatesUsedBy(title)` / `pagesLinkingTo(title, limit)` / `pagesTranscluding(title, limit)`（core 的链接索引，只回传标题）——AI 代理的 `info` 工具用它。
- `common.service.SearchStore`：**搜索桥**——`search(keyword, limit)` 走 core 的搜索引擎（内置提供方 + 插件 `SearchProvider`），权限过滤由调用方用 `AccessCheck` 自己做——AI 代理的 `search` 工具用它。
- `common.service.RateLimit`：**限流桥**——`check(category, key)` / `hit(category, key)` / `success(category, key)`，与 core 的登录 / 安装向导 / 密码重置共用同一套「滑动窗口 + 锁定」实现，规则仍是 `wikikernel.rate-limit.<分类>`——AI 代理的对话限流（`ai-agent`，默认 30 次/小时）用它。

页面属性在页面保存 / 删除时由 `PagePropertyEventListener` 重新解析并覆盖保存（对应 MediaWiki 保存 ParserOutput 的
`page_props`）；`HtmlHeadProvider` 读到的是**本次渲染**的属性，模板里的 SEO 改动立即生效。
皮肤侧新增：`hideTitle`（隐藏 `h1#firstHeading`）与 `fragments/head.html` 的 `headItems` / `htmlTitle` 槽位。

### 插件平台能力（core 提供，插件可直接用）

- **插件消息（i18n）**：注册 `common.extension.MessageBundle`（语言 → 键 → 文案），core 的 `messageSource`
  会先查 `messages*.properties` 再查插件消息（语言回退 `zh-CN → zh → en`），因此模板 `#{键}`、
  控制器与插件里的 `MessageSource` 都能用；
- **站点设置读取**：`common.service.SiteConfig`（`get` / `getBoolean` / `getInt`）——插件可读
  `features.*`（功能开关）、`site.name` 等 core 设置，保证迁移前后的开关语义一致；
- **编辑 / 注册钩子（表单字段 + 保存前校验）**：`EditFormField` / `EditGuard`（编辑页与
  `api.php?action=edit`）与 `RegistrationFormField` / `RegistrationGuard`（注册页与
  `POST /api/user/register`）——插件既能在表单里加字段，也能在写入前放行 / 拒绝 / 警告 / 自行处理；
  钩子按 `order()` 升序执行、**抛异常只记警告并跳过**（页面不 500），来源用 `EditContext.source()` /
  `RegistrationContext.source()` 区分（`web` / `api`），会话状态放 `SessionAttributes`（不依赖 Servlet API）；
  没有任何插件注册时编辑页与注册页与迁移前完全一致；
- **用户界面槽位钩子（本批新增）**：`UserProfileHook`（用户页资料卡的头像与「贡献 | …」额外链接）与
  `PersonalToolbarHook`（顶栏个人工具区的额外条目）——core 用 `web/UserProfileAdvice` /
  `web/PersonalToolbarAdvice` 收集（从请求 URI 的 `用户:X` / `User:X` 解析资料卡用户），
  结果放模型键 `userAvatarUrl` / `userProfileLinks` / `pluginPersonalTools`，
  模板槽位在 `fragments/user-profile.html` / `fragments/personal-tools.html`；
  同样按 `order()` 升序、**抛异常只记警告并跳过**，API 请求不询问这两类钩子，
  没有任何插件时资料卡用默认头像且个人工具区与迁移前完全一致；
- **动作「自行处理」与上传载荷（本批新增）**：`ActionGuard.Outcome.HANDLED(message, data)` 表示
  「钩子已经处理完这个动作」（如审核把上传写进队列而不是落盘）——core 不执行该动作、把结果当作成功返回，
  `data` 并入网页响应的 `data` / API 的动作对象（审核上传回 `pending=true` / `moderated=true`）；
  同时 `ActionContext.payload()` 在上传动作里给出文件内容（钩子可自行暂存），
  移动 / 删除 / 封禁没有载荷；`api.php?action=upload` 现在也走同一条钩子链（此前只有网页上传走）；
- **插件静态资源端点**：把文件放在插件 jar 的 `static/plugins/<插件id>/…`，浏览器访问
  `/plugins/<插件id>/…` 即可（一天公共缓存、ETag、防目录穿越；仅服务已加载插件）；
- **提交 / 查询 / 限流桥（本批新增）**：`common.service.EditSubmitter`（**像一次编辑那样提交**：先跑
  `EditGuard`，再按乐观锁保存——AI 代理的「确认提交」用它，`source=ai`，钩子登记的变更标签随版本写入）、
  `PageQueryStore`（页面列举 + 版本历史）、`PageLinkStore`（模板 / 链入 / 被嵌入）、`SearchStore`（搜索）、
  `RateLimit`（按分类限流，规则仍在 `wikikernel.rate-limit.*`）——AI 代理插件是第一个用户，
  core 侧接线由 `PluginDataBridgeTest` 覆盖；
- **插件管理自己的站点级表单**：AI 代理的站点密钥与「允许普通用户使用」原来在
  `特殊:权限配置 → 站点设置`（和 `/init` 的配置项），迁移后由插件自己的管理员入口
  （`特殊:AI代理?keys=1`）渲染，写的是同一批设置键（`ai.provider.<id>.*` /
  `ai-agent.allow-normal-users`）——插件写站点设置走 `common.service.SiteConfig.set`（与 core 的
  `/init`、站点设置页同一个存储），不要依赖 core 的 `SiteSettingsService`；
- **插件里的 `@RestController` 依赖必须是 Bean**：插件子容器会扫描插件包的 `@Component` / `@Service` / `@RestController`
  （`PluginSpringContextFactory.scanPluginPackage`），所以被接口 / 特殊页构造器依赖的支撑对象**不能只在 `onStart` 里 `new`**
  ——要么标 `@Component`（构造器注入 core 提供的桥），要么在入口类里 `@Autowired` 后再注册为扩展点；
  否则插件在真实实例上会以 `NoSuchBeanDefinitionException` 启动失败（单元测试发现不了）；
- **运行时装载回归**：`core` 的 `PluginRuntimeLoadTest` 用根目录 `plugins/` 里的真实 jar 启动一次应用，
  断言每个 jar 都成功启动（数量一致 + 关键插件在列）。先跑 `.\gradlew.bat installPlugins`；
  `plugins/` 为空时该测试自动跳过，不影响普通 `gradlew build`；
- **坏插件容错**：插件 jar 描述文件缺失 / 入口类不存在时只记警告并跳过，不再拖垮应用启动；
- **插件可注入的 core Bean**：除 common 桥接服务（`WikiPageStore` / `PagePropertyStore` / `CategoryStore` /
  `SiteConfig`）外，Spring 类型（如 `MessageSource`、`DataSource`）也可直接 `@Autowired`；
- **插件依赖插件 / 插件依赖 core（版本约束）**：描述文件的 `plugin.requires`（运行期依赖别的插件）、
  `plugin.requiresCore`（core 版本约束）、`plugin.clientRequires`（前端资源依赖）——
  加载顺序按依赖拓扑排序，缺失 / 环 / 版本不匹配只跳过并记日志，**不会让应用启动失败**，
  细节见上文「插件依赖声明」；core 侧回归由 `PluginDependencyResolutionTest` 覆盖
  （现场生成带各种依赖关系的插件 jar）；
- **插件对 core / common 做 AOP**：注册 `CoreAopHook`（`common/extension`）即可对 core 的 Bean
  与插件自己的 Bean 加切面（`PluginAopRegistry` + `PluginAopBridge`），
  切面对**已经注入到其它 Bean 的引用**生效、插件卸载后立即失效；开关 `wikikernel.plugins.aop-bridge`
  （默认 `auto` = 装了插件才启用）；core 侧回归由 `PluginAopTest` 覆盖
  （代理、注入引用、撤销、坏 Advisor 容错、基础设施不代理），
  真实 jar 端到端由 `DepDemoPluginTest` 覆盖（读插件里的切面计数器）；
- **客户端注入顺序**：`PluginManager.clientLoadOrder()`（`plugin.clientRequires` 的拓扑顺序，
  被依赖者的 `<head>` 片段在前）与 `clientDependencyWarnings()`（缺失客户端依赖时的 HTML 注释警告）；
  `PluginHeadAdvice` / `WikiPageController` 的 head 收集都已改成走这两个入口；
- **示例 / 演示插件**：`plugin/dep-demo`（**不是** MediaWiki 扩展移植）用来演示上面三项能力：
  `plugin.requiresCore=>=0.0.1` + `plugin.clientRequires=comments` + `CoreAopHook`（统计 core 调用 + 插件自己的 Bean），
  同时是 core 侧端到端测试的输入。它会被 `.\gradlew.bat build` / `:plugin:dep-demo:build` 构建，
  但 **`installPlugins` 不会把 `*-demo-*.jar` 复制进 `plugins/`**，因此不影响真实部署；
  想在真实实例上试玩时手动 `copy plugin\dep-demo\build\libs\*.jar plugins\` 再重启即可；
- **插件可以用 core 运行时已有的第三方库**（compileOnly 声明、运行时由 core 的类加载器提供）：
  AI 代理插件就用了 Jackson 3（`tools.jackson.core:jackson-databind`，解析厂商响应）与
  CommonMark（`org.commonmark:*`，渲染 AI 回复的 Markdown）——插件 jar 不打包它们。

### 各插件的配置（`LocalSettings.yaml`，也可在 `/init` 的插件 tab 里填）

```yaml
wikikernel:
  plugins:
    settings:
      hit-counters:
        disabled: false               # 关闭浏览计数
        footer: true                  # 正文末尾显示「本页面已被访问 N 次」
        enable-add-page-id: false     # 特殊:最多浏览 显示页面 ID
        enable-add-text-length: false # 特殊:最多浏览 显示字节数
        exempt-groups: ""             # 豁免计数的用户组（逗号分隔；机器人始终不计数）
      wiki-seo:
        metadata-generators: OpenGraph,Twitter,SchemaOrg
        twitter-card-type: summary_large_image
        # 站点验证 key、default-image、noindex-page-titles、auto-description 等见 plugin/wiki-seo/README.md
      category-tree:
        allow-tag: true                # 允许 <categorytree> 标签
        max-children: 200              # 每个节点最多列出的子节点数
        max-depth-categories: 2        # categories 模式的最大深度
        max-depth-pages: 1             # pages 模式的最大深度
        max-depth-all: 1               # all 模式的最大深度
        default-mode: categories
        default-hideprefix: categories
        default-showcount: false
      widget:                          # 微件（Widgets）：命名空间前缀（逗号分隔）
        prefixes: 微件:,Widget:
```

> 功能开关（`features.*`）仍集中在 core 的站点设置里：拆出去的插件通过 `common.service.SiteConfig`
> 读同一个键，所以 `/init`、`特殊:权限配置 → 站点设置`、`LocalSettings.yaml` 的开关照旧生效。

### 与 MediaWiki 原版的差异（摘要）

- NoTitle：MediaWiki 版用 CSS 隐藏 `h1.firstHeading`，WikiKernel 里行为开关写页面属性 `notitle`，皮肤布局隐藏标题（效果一致）；
- Variables：`{{{参数}}}` 已按 frame 展开，`#var_final` 的延迟占位在解析收尾时求值（语义与 MediaWiki 测试用例一致）；
- HitCounters：`$wgHitcounterUpdateFreq` 的批量合并未实现（每次浏览立即 +1）；页脚计数显示在正文末尾；`特殊:统计` 的浏览区块未加；
- WikiSEO：未移植社交分享图片生成（GD/Imagick）、REST 社交图片路由、Scribunto `mw.ext.seo`、`PageImages` 覆盖、
  InfoAction 钩子与自定义生成器类；自动描述改为在 head 阶段按正文现算（不落库）；robots 只在设置或命中
  noindex 规则时输出；安装插件后**已存在的页面需重新保存一次** SEO 参数才写入页面属性表；其余差异见 `plugin/wiki-seo/README.md`；
- TemplateData：未移植编辑页 GUI（OOUI/`ext.templateDataGenerator.*`）、保存时拦截（非法 JSON 仍可保存，只在渲染时显示错误框）、
  MySQL gzip 压缩与 `pageids=`/generator 等 API 细节；文档表格用内联样式替代 OOUI 图标，消息只有 zh/en，其余见 `plugin/template-data/README.md`；
- CategoryTree：未移植侧栏 portlet、页面底部分类链接劫持（`CategoryTreeHijackPageCategories`）、分类页的树按钮、
  Translate 的 `notranslations` 与分类搜索建议，其余见 `plugin/category-tree/README.md`；
- InputBox（**从 core 拆出**）：core 不再内置 `<inputbox>` 解析（插件未加载时标签按原文显示）；
  前端脚本 URL 从 `/js/inputbox.js` 变为 `/plugins/input-box/inputbox.js`；开关 `features.input-box` 与表单行为不变；
- EasyTimeline（**从 core 拆出**）：core 不再内置 `<timeline>` 解析（插件未加载时标签按原文显示）；
  SVG 输出与迁移前一致，开关 `features.timeline` 不变；
- Math（**从 core 拆出**）：core 不再内置 `<math>` / `<chem>` 解析（插件未加载时标签按原文显示）；
  占位 HTML 与迁移前一致，脚本 URL 变为 `/plugins/math/math.js`（KaTeX 仍从 core 的 `/vendor/katex/…` 加载），
  开关 `features.math` 不变；
- Flash 播放器（**从 core 拆出**）：core 不再内置 `<flash-player>` 解析（插件未加载时标签按原文显示）；
  占位属性与迁移前一致，脚本 URL 变为 `/plugins/flash-player/flash-player.js`
  （Ruffle 仍从 core 的 `/vendor/ruffle/…` 加载），开关 `features.flash-player` 不变；
- Popups（**从 core 拆出**）：脚本与浮层样式迁入插件（`/plugins/popups/popups.js`、`popups.css`）；
  通用预览 API `/api/page-preview` 与 `WIKI_CONFIG.popups` 保留在 core，开关 `features.popups` 不变；
- Graph（**从 core 拆出**）：core 不再内置 `<graph>` 解析与 `图表:` 整页图（插件未加载时标签按原文转义、
  该命名空间按普通页面渲染）；占位 HTML 与渲染结果不变，脚本 URL 变为 `/plugins/graph/graph.js`
  （Vega / Vega-Lite / vega-embed 仍从 core 的 `/vendor/vega/…` 加载），开关 `features.graph` 不变；
  未移植 `<graph>` 的地图类图形与 Graph 命名空间的编辑工具，详见 `plugin/graph/README.md`；
- 多媒体预览（**从 core 拆出**）：core 不再渲染播放器（`MediaPlayerHtml` 只保留「图标 + 文件名」占位框，
  供没有插件处理时使用）；插件 `media-viewer` 提供音视频播放器与图片放大查看，
  脚本 / 样式迁到 `/plugins/media-viewer/…`（播放器与查看器样式原本在 core 的 `base.css`，现已迁出），
  `WIKI_CONFIG.mediaViewer` 与 `WIKI_MESSAGES` 的播放器文案改由插件注入，开关 `features.media-viewer` 不变；
- Widgets（**从 core 拆出**）：core 不再内置 `{{#widget:…}}`（插件未加载时按原文显示），
  微件渲染（Smarty 子集）与编译缓存迁入插件；命名空间前缀改由插件配置
  （`wikikernel.plugins.settings.widget.prefixes`），旧配置 `wikikernel.widgets.enabled: false` 仍兼容
  （core 启动时写进站点设置 `features.widgets`）；「微件:」页面仍是 core 的 HTML 代码页与编辑限制，
  开关 `features.widgets` 不变；
- Gadgets（**从 core 拆出**）：定义页解析、按用户加载、`特殊:Gadgets` 与参数设置里的 Gadgets 区块
  全部迁入插件（改由 `PreferenceSection` 贡献「小工具」标签页，保存走 `POST /api/gadgets/preferences`）；
  个人偏好仍存 `wiki_user.gadgets`；定义页**不再自动创建**（`特殊:Gadgets` 里给「创建定义页」入口），
  开关 `features.gadgets` 不变；
- WikidataPageBanner（**从 core 拆出**）：`{{PAGEBANNER:…}}` / `<exclude-banner-page />` 指令与横幅 HTML
  生成迁入插件（指令改写页面属性，横幅经 `PageDisplayHook.beforeTitle` 插到标题上方）；
  白名单 / 逐页配置 / 轮换机制等设置与开关**现在也归插件自己**：插件注册「站点设置分区」
  （`PageBannerSettingsSection`，core 只按声明的键渲染 / 保存）与安装向导步骤（`PageBannerSetupStep`），
  键名沿用 `features.page-banner.*`，`wikikernel.features.page-banner.*` 的启动导入也在插件里做
  （`PageBannerYamlSettings` + `SiteConfig.set`）；core 里的 `PageBannerService` / `PageBannerServiceImpl`
  已删除；
  core 不再抽取 `{{PAGEBANNER:…}}`（`RenderResult` 的 `bannerFile` / `bannerCaption` / `bannerExcluded` 与
  `PageBannerService.htmlFor` 已删除）：不带 `#` 的 `{{名字:参数}}` 现在按**插件解析器函数**派发
  （名字命中已注册函数时交给插件，未注册仍按模板解析），插件未加载时指令不再被剥离；
  横幅样式从 `base.css` 迁到 `/plugins/page-banner/page-banner.css`（`#page-banner` 容器 / 外边距仍在 core），
  `pageBanner.rotation.*` 下拉文案随插件走（键名不变）。
- ConfirmEdit（**从 core 拆出**）：题目生成 / 会话状态 / 免验证组判断与保存前校验全部迁入插件
  （`EditFormField` 渲染编辑框下方的题目 + 输入框，`EditGuard` 在 `source=web` 时校验）；
  开关与免验证组的设置也归插件自己（`ConfirmEditSettingsSection` + `ConfirmEditSetupStep`，
  键名沿用 `features.confirm-edit.*`，`wikikernel.features.confirm-edit.*` 的导入在插件里），
  core 里的 `ConfirmEditService` / `ConfirmEditServiceImpl` 已删除；
  `api.php` 不受影响（`source=api` 直接放行，与迁移前一致），core 不再硬编码 `captchaAnswer` 参数，
  `confirmEdit.*` 文案随插件走（键名不变），开关 `features.confirm-edit` 不变；
- 注册问答验证（**从 core 拆出**）：抽题 / 下标校验 / 旧格式兼容整体迁入插件
  （`RegistrationFormField` 渲染题目 + 隐藏下标 + 答案输入框，`RegistrationGuard` 在
  `POST /api/user/register` 校验并返回 400 + 说明）；设置也归插件自己
  （`RegistrationQuizSettingsSection` + `RegistrationQuizSetupStep`，键名沿用
  `registration.questions` / `answers` / `question` 与 `features.registration-quiz`），
  core 里的 `RegistrationQuizKeys` 与 `js/registration-quiz.js` 行编辑器已删除，
  注册页继续用 core 的 `js/auth.js` 提交（`FormData` 全量提交，插件不需要自带脚本），
  开关 `features.registration-quiz` 不变；
- 评论（**从 core 拆出**）：`<comment />` / `<comments />` 的剥离与评论区渲染整体迁入插件
  （`CommentTag` 剥离标签并写页面属性 `comments=1`，`CommentSectionHook` 经 `PageDisplayHook.afterContent`
  在正文下方注入评论区，`/api/comments` 由插件的 `@RestController` 提供）；core 不再内置评论
  （`COMMENT_TAG`、`RenderResult.comments`、`CommentApiController`、`CommentService`、`view.html` 的评论区、
  `base.css` 的评论区样式、`comment.*` 文案全部移除），**插件未加载时 `<comment />` 按原文转义显示、页面没有评论区**；
  数据仍在 `wiki_comment` 表（经 `common.service.CommentStore`），头像 / 用户名 / 渲染 / 权限分别走
  `AvatarStore` / `UserStore` / `WikiText` / `AccessCheck` 桥；评论日志类型（`comment` / `commentdelete`）
  由插件用 `common.extension.LogType` 注册；开关 `features.comments` 不变；
- 多文件批量上传（**从 core 拆出**）：上传页的多选列表与批量编排、编辑器上传对话框的多选批量插入
  整体迁入插件 `multi-upload`（core 的上传页只保留单文件表单，`edit-toolbar.js` 只保留单文件流程）；
  上传仍走 core 的 `POST /api/files/upload`（权限 / 审核 / 上限不变），插件负责前端编排与文案，
  `window.WIKI_CONFIG.multiUpload` 改由插件注入，开关 `features.multi-upload` 不变。
- 用户交互（头像 + 私信，**从 core 拆出**）：用户页头像上传与用户间私信整体迁入插件 `social-profile`
  （`POST /api/user/avatar`、`GET /avatars/<文件名>`、`特殊:上传头像`、`特殊:私信`、`/api/messages/*`、
  脚本 `/plugins/social-profile/{avatar,messages}.js`、样式 `social-profile-base.css`、
  `avatar.*` / `messages.*` / `special.messages` / `special.uploadAvatar` / `tools.sendMessage` 文案全部随插件走）；
  core 不再内置头像与私信（`AvatarService(+Impl)`、`MessageService(+Impl)`、`AvatarController`、
  `UserAvatarApiController`、`MessageApiController`、`UploadAvatarSpecialPage`、`MessagesSpecialPage`、
  `CurrentUserAdvice.unreadMessages` 全部移除），只保留站点设置开关与两个渲染槽位钩子——
  **插件未加载时资料卡用默认头像、没有「上传头像 / 私信」链接、顶栏没有私信入口、两个特殊页 404、
  两个接口不存在**；数据仍在 `wiki_user.avatar` 与 `wiki_message` 表（经 `AvatarStore` / `MessageStore` 桥），
  头像文件仍存 `{run-path}/avatars/`，开关 `features.social-profile` 不变；
  侧栏「发私信」入口只在插件提供 `特殊:私信` 时显示（插件未加载不留死链）；
  私信时间改由插件按消息键 `format.dateTime` 格式化（服务器时区，不含用户显示时区换算，见插件 README）；
- 滥用过滤器（**从 core 拆出**）：条件求值器（`AbuseCondition` / `AbuseValues` / `AbuseJson`）、
  过滤器 CRUD 与历史恢复、`特殊:滥用过滤器` / `特殊:滥用日志`、`/api/abuse-filter/*`、CodeMirror 条件编辑器
  与样式、命中日志与变量快照全部迁入插件 `abuse-filter`；评估入口改成平台钩子
  （编辑 / 创建走 `EditGuard`——网页与 `api.php` 都评估，与迁移前一致；移动 / 上传 / 删除 / 封禁走新的
  `ActionGuard`；注册走 `RegistrationGuard`）；**core 未加载插件时不评估任何过滤器、不写滥用日志、
  两个特殊页与接口都不存在**；数据仍是 `wiki_abuse_filter*` / `wiki_abuse_log` 三张表（经
  `common.service.AbuseFilterStore`），系统账号「滥用过滤器」的创建走 `common.service.UserWriter`；
  core 只保留站点设置开关 `features.abuse-filter`；未接的动作钩子（若你发现某条动作没被评估）见插件 README。
- 平台能力（本批新增）：编辑 / 注册的**表单字段钩子 + 保存前校验钩子**
  （`EditFormField` / `EditGuard` / `RegistrationFormField` / `RegistrationGuard`）——
  与 MediaWiki 的 `EditPage::showEditForm:fields` / `EditFilterMergedContent` / `AuthChangeFormFields` /
  `AbortNewAccount` 对应，但**一个钩子可以同时拒绝与警告**（`BLOCK` / `WARN`），并且可以用 `HANDLED`
  表示「已自行处理、core 不再保存」（MediaWiki 需要钩子自己接管保存流程）；
  钩子按 `order()` 升序、抛异常只跳过该插件，会话状态经 `SessionAttributes` 传递（插件不依赖 Servlet API）。
- 平台能力（本批新增）：**用户界面槽位钩子** `UserProfileHook`（用户页资料卡的头像与「贡献 | …」额外链接）
  与 `PersonalToolbarHook`（顶栏个人工具区的额外条目）——与 MediaWiki 的
  `SkinTemplateNavigation` / `PersonalUrls` / SocialProfile 的用户页钩子对应；core 用
  `UserProfileAdvice` / `PersonalToolbarAdvice`（`@ControllerAdvice`）从请求 URI 的标题解析资料卡用户并收集结果，
  放进模型键 `userAvatarUrl` / `userProfileLinks` / `pluginPersonalTools`，模板槽位在
  `fragments/user-profile.html` 与 `fragments/personal-tools.html`；钩子按 `order()` 升序、返回 HTML 由 core
  原样插入（插件自行转义）、抛异常只记警告并跳过（页面不 500），没有任何插件时资料卡与个人工具区与迁移前一致。
- 审核（**从 core 拆出**）：队列服务（原 `ModerationServiceImpl`）、`特殊:审核`（状态 / 类型 / 标题过滤 +
  页签计数 + 行级差异 + 上传预览）、`/api/moderation/*`、前端脚本与样式、待审提示条 / 「待审创建」页、
  `moderation.*` / `files.uploadPending` / `files.deletePending` / `log.*moderation*` 文案全部迁入插件
  `moderation`；入口改成平台钩子——编辑 / 创建走已有的 `EditGuard.HANDLED`（core 不保存并按 redirect 跳转
  `?moderation=pending`），上传 / 删除走 **新的 `ActionGuard.HANDLED`**（core 不执行动作，当作成功返回 +
  合并插件给的响应数据），提示条 / 待审创建页走 **新的 `PageDisplayHook.beforeContent` / `renderMissingPage`**，
  编辑页提示走 `EditFormField.notice`；**core 未加载插件时编辑 / 上传 / 删除立即生效（没有队列）、
  `特殊:审核` 与 `/api/moderation/*` 不存在、页面没有待审提示条、编辑页没有「进入审核队列」提示**；
  数据仍是 `wiki_pending_change` 表（`common.service.ModerationStore`）与 `{run-path}/pending/` 暂存
  （`PendingFileStore`，历史待审文件仍可读），通过待审编辑走 `PageWriter`（以原提交人身份 + AI 水印保留）、
  文件写入 / 删除走新的 `common.service.FileWriter`（core 的 `FileService` 仍是唯一写入方）、
  队列差异走新的 `common.service.WikiDiff`（与差异对比页同一套行模型），日志类型
  `moderation` / `moderationreject` 由插件用 `LogType` 注册（`特殊:日志` 过滤器与最近更改照常显示），
  详情文案用新的 `LogType.detailText` 在插件里本地化；core 只保留站点设置开关 `features.moderation`；
  没有 move 审核 / 待审移动、没有通知（差异见插件 README）。
  平台接线由 core 的 `PluginActionHookTest`（`HANDLED` / `payload()` / `api.php?action=upload`）、
  `PluginPageDisplayHookTest`（`beforeContent` / `renderMissingPage`）、`PluginDataBridgeTest`
  （`FileWriter` / `WikiDiff`）覆盖；插件测试 30 个用例。
- 重命名用户（**从 core 拆出**）：`特殊:重命名用户` / `POST /api/user/rename`、前端脚本与
  `renameUser.*` / `special.renameUser` / `log.typeName.renameuser` / `log.entry.renameuser` /
  `log.renameuser.*` 文案全部迁入插件 `rename-user`；core 不再内置
  `RenameUserApiController` / `RenameUserSpecialPage` / `rename-user.html` / `rename-user.js`，
  只保留「重命名用户」用户组（`GROUP_RENAME_USER`）与 `common.service.UserAdminStore` 桥
  （改名 / 用户名规则 / 移动用户页 / 写日志仍在 core，内部调 `UserService.rename`）；
  **插件未加载时 `特殊:重命名用户` 与 `/api/user/rename` 不存在（404）、core 不写 `renameuser` 日志**；
  数据与历史日志不动（`wiki_user.username` 与 `wiki_log` 的 `renameuser` 类型照常）。
- 用户合并（**从 core 拆出**）：`特殊:用户合并` / `POST /api/user-merge`、前端脚本与
  `userMerge.*` / `special.userMerge` / `log.typeName.usermerge` / `log.entry.usermerge` /
  `log.usermerge.*` 文案全部迁入插件 `user-merge`；core 不再内置 `UserMergeApiController` /
  `UserMergeSpecialPage` / `user-merge.html` / `user-merge.js`，只保留「核查员」用户组
  （`GROUP_CHECKUSER`）与 `common.service.UserMergeStore` 桥（数据与规则逻辑仍在
  `UserMergeServiceImpl`，事务内跨表重写 + 可选删除源账号 + 写 `usermerge` 日志）；
  用户页（`用户:源`）**不会被自动移动**（与 MediaWiki UserMerge 一致，迁移前后都不移）；
  **插件未加载时 `特殊:用户合并` 与 `/api/user-merge` 不存在（404）、core 不写 `usermerge` 日志**；
  数据与历史日志不动（`wiki_revision` / `wiki_log` / `wiki_user_permission` / `wiki_file*` 照常）。
- 平台能力（本批新增）：**动作「自行处理」+ 上传载荷 + 页面正文前片段 / 缺失页认领 + 日志详情本地化**——
  `ActionGuard.Outcome.HANDLED(message, data)`（core 不执行动作、当成功返回并把 `data` 并入响应）、
  `ActionContext.payload()`（上传动作的文件内容，钩子可暂存）、
  `PageDisplayHook.beforeContent()`（正文之前的提示条）与 `PageDisplayHook.renderMissingPage()`
  （页面不存在时认领显示，core 用通用外壳 `templates/wiki/plugin-missing-page.html` 渲染，
  没有插件认领时照常 404）、`LogType.detailText()`（插件本地化日志详情）；
  这些钩子同样按 `order()` 升序、抛异常只跳过该插件——没有任何插件时行为与迁移前完全一致。
- AI 代理（**从 core 拆出**）：厂商适配器与目录、会话编排 / 工具循环 / 提案、`特殊:AI代理` + 模板、
  `/api/ai-agent/*`、密钥接口（旧 `/api/ai-provider-keys` → 插件 `/api/ai-keys`，含
  `POST /api/ai-keys/allow-normal`）、参数设置里的「AI 代理（我的密钥）」标签页（改由 `PreferenceSection`
  贡献）、站点密钥与「允许普通用户使用」（旧：`特殊:权限配置 → 站点设置` 的表单与 `/init` 的「配置项」；
  新：`特殊:AI代理?keys=1`）、前端脚本与样式（`/plugins/ai-agent/…`，原 `head.html` 两行 + `base.css` 样式）、
  `MarkdownRenderer` 与全部 AI 文案迁入插件 `ai-agent`；**确认提交改走 `common.service.EditSubmitter`**
  （`Request.aiAssisted(...)`：先跑平台保存前钩子——插件版滥用过滤器等，来源 `source=ai`；再按乐观锁保存并打
  AI 水印；`blocked` / `handled` 拒绝、`warn` 保存并回传提示），读页面 / 版本 / 搜索 / 链接 / 分类 / 权限 /
  用户名 / 限流分别走 `WikiPageStore` + **新的** `PageQueryStore` / `SearchStore` / `PageLinkStore` /
  `CategoryStore` / `AccessCheck` + `CurrentUser` / `UserStore` / **新的** `RateLimit` 桥；
  **core 未加载插件时 `特殊:AI代理` 与 `/api/ai-agent/*`、`/api/ai-keys/*` 都不存在、参数设置里没有 AI 标签页、
  站点设置里没有 AI 厂商密钥表单、页面不注入 `/plugins/ai-agent/…`**；core 只保留站点设置开关
  `features.ai-agent`（默认关闭，`/init` 与站点设置照旧）与 AI 版本的隐藏水印渲染（`aiAgent.watermarkTitle` /
  `.mw-ai-watermark` 仍在 core）；`wikikernel.ai.*` 配置、`wiki_user_ai_key` 表与站点密钥键现在都归插件
  （插件内 `AiProperties` + `AiConfigService`，表由 `schemaEntities()` 声明、core 自动建表）；
  「AI代理用户」组、限流规则 `wikikernel.rate-limit.ai-agent` 不变；
  站点密钥接口路径改为 `/api/ai-keys`、静态资源改为 `/plugins/ai-agent/…`、`/init` 不再问「允许普通用户使用」
  （改由插件站点入口维护同一个设置键）；差异见 `plugin/ai-agent/README.md`。
  平台接线由 core 的 `AiAgentTest`（「插件未加载」语义：特殊页 / 接口不存在、参数设置与站点设置里没有 AI 表单、
  开关仍在、水印仍在）与 `PluginDataBridgeTest`（`PageQueryStore` / `PageLinkStore` / `SearchStore` /
  `RateLimit` / `SiteConfig.set`）覆盖；插件测试 43 个用例。
- 平台能力（本批新增）：**提交 / 查询 / 限流桥 + 插件自己的站点级表单**——`common.service.EditSubmitter`
  （「像一次编辑那样提交」：跑 `EditGuard` + 乐观锁 + AI 水印，钩子登记的变更标签随版本写入）、
  `PageQueryStore`（页面列举按命名空间语义 + 版本历史元信息）、`PageLinkStore`（模板 / 链入 / 被嵌入）、
  `SearchStore`（core 搜索引擎）、`RateLimit`（与登录 / 安装向导共用实现，规则仍是
  `wikikernel.rate-limit.*`）、`SiteConfig.set`（插件写站点设置，与 core 的 /init 同一个存储）；
  插件的站点级表单（AI 站点密钥 + 放开普通用户）由插件特殊页自己渲染，写同一批设置键——
  插件不再需要 core 的 `SiteSettingsService`。
