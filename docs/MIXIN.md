# 插件 Mixin + MixinExtras（SpongePowered Mixin）

WikiKernel 用 **启动期 Java Agent + Spring Boot `PropertiesLauncher`** 接入
[SpongePowered Mixin](https://github.com/SpongePowered/Mixin)（运行依赖为 Fabric 维护的
Maven Central 构建 `net.fabricmc:sponge-mixin`，Mixin 版本 0.8.7），同时内置
[MixinExtras](https://github.com/LlamaLad7/MixinExtras) 0.5.5。

它让插件可以像 Minecraft 模组一样写 `@Mixin`，在类加载时改写 **core、common、第三方库或其它插件**
的字节码。Mixin 是最高权限的扩展方式，只应安装受信任的插件。

## 启动链路

发行包使用一个**自包含启动 jar**：Mixin Agent 被嵌入 boot jar 根目录，通过 JDK 的
`Launcher-Agent-Class` 在 `main` 之前自动加载；Agent 再把 Mixin 插件写入 `loader.path`，
最后由 `PropertiesLauncher` 启动 Spring。

```
java -jar build/dist/wikikernel-server.jar
  └─ Launcher-Agent-Class: WikiKernelMixinAgent.agentmain
       └─ mixin-agent premain
            ├─ 扫描 plugins/，暂存所有插件 jar
            ├─ 读取带 plugin.mixins 的配置
            ├─ 初始化 Mixin、注册配置
            ├─ 安装首次类加载 transformer
            └─ 把带 Mixin 的插件写入 loader.path
  └─ PropertiesLauncher（jar manifest 的 Main-Class）
       └─ WikiKernelLauncher（Spring Boot Start-Class）
            └─ Spring / core / PluginManager
```

- **core 自己的类在 Spring 创建前就会被 Mixin 转换**，所以可以修改 core controller、service、静态工具类等；
- 带 Mixin 的插件 jar 会通过 `loader.path` 进入应用类加载器，Mixin 注入的代码能直接引用插件 helper 类；
- 普通插件仍然走原来的隔离 `PluginClassLoader`，并保留动态装卸；
- 非 Mixin 插件 jar 只被暂存一份，用于 Mixin 在目标插件尚未加载时读取目标类字节码。

> 推荐用 `gradlew dist` 生成 `build/dist/wikikernel-server.jar`，然后直接
> `java -jar build/dist/wikikernel-server.jar`；`build/dist/run.bat` 只是带日志窗口和
> 启动副本保护的便捷脚本。
>
> Agent 默认扫描工作目录下的 `plugins/`，暂存到 `run/.mixin-plugins/`。如果修改了
> `wikikernel.plugins.dir`，请同时设置 `-Dwikikernel.mixin.pluginDir=<目录>`；
> 暂存目录可用 `-Dwikikernel.mixin.stageDir=<目录>` 覆盖。

## 插件声明

在 `META-INF/wiki-plugin.properties` 中声明配置（逗号分隔）：

```properties
plugin.id=my-plugin
plugin.name=My Plugin
plugin.version=1.0.0
plugin.mainClass=cc.thonly.wikikernel.plugin.myplugin.MyPlugin
plugin.mixins=mixins.my-plugin.json
```

也可以不写 `plugin.mixins`：启动器会自动发现插件 jar 里的
`*.mixins.json` 或根目录 / 子目录下的 `mixins*.json`。多插件使用**不同的配置文件名**，
配置名冲突时后加载的插件会被忽略并打印 WARN。

标准 Mixin 配置示例（`src/main/resources/mixins.my-plugin.json`）：

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "cc.thonly.wikikernel.plugin.myplugin.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "WikiTitlesMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

插件代码可以直接使用 Mixin 和 MixinExtras 注解。仓库根构建脚本已经给所有 Java 子模块加了：

```groovy
compileOnly "net.fabricmc:sponge-mixin:${mixinVersion}"
compileOnly "io.github.llamalad7:mixinextras-common:${mixinExtrasVersion}"
```

插件不需要重复声明；独立构建插件时自行加同样的 `compileOnly` 即可。
MixinExtras 的运行时类由 `mixin-agent` 提供，不要打进插件 jar。

## 示例

目标 core 方法：

```java
public static String capitalizeFirst(String value) {
    // ...
}
```

插件中的 Mixin：

```java
package cc.thonly.wikikernel.plugin.myplugin.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "cc.thonly.wikikernel.web.WikiTitles")
public final class WikiTitlesMixin {

    @Inject(method = "capitalizeFirst", at = @At("HEAD"), cancellable = true)
    private static void myplugin$capitalize(String value, CallbackInfoReturnable<String> cir) {
        if ("probe".equals(value)) {
            cir.setReturnValue("patched");
        }
    }
}
```

`@Mixin(targets = "...")` 可以避免插件编译期依赖 core；如果要直接引用目标类，也可以使用
`@Mixin(SomeCoreClass.class)`，但插件仍应只 `compileOnly` 依赖 core 类型。

MixinExtras 提供更简洁的常见能力，例如 `@ModifyReturnValue` / `@WrapOperation` /
`@WrapWithCondition` / `@Local` / `@Share`：

```java
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

@Mixin(targets = "cc.thonly.wikikernel.web.WikiTitles")
public final class WikiTitlesExtrasMixin {

    @ModifyReturnValue(method = "capitalizeFirst", at = @At("RETURN"))
    private static String probe(String original) {
        return "probe".equals(original) ? "patched" : original;
    }
}
```

## 规则与限制

- **只支持启动期生效**：Mixin 配置和 Mixin 类在 JVM 启动时注册；带 Mixin 的插件不能热替换。
  修改这类插件后必须重启。普通插件仍可通过 `POST /api/plugins/reload` 动态装卸。
- **Mixin 插件入口类和普通 helper 类不要放在 Mixin 包的子目录里**。Mixin 会把配置里的
  `package` 视为保留包：入口类 / 普通类被直接加载时可能抛
  `IllegalClassLoadError`。入口类和 helper 放在插件自己的普通包，只有 `@Mixin`
  类单独放 `...mixin` 包。
- **目标类必须是应用类加载器能加载的类**：core、common、Spring / 第三方库、插件类都可以；
  JDK / `java.*` / `jdk.*` / `sun.*` 以及 Spring Boot loader 自身默认不转换，避免 bootstrap
  类加载器解析不到插件 helper 类，也避免破坏 JVM 启动。
- **MixinExtras 0.5.5 已内置**：`@ModifyReturnValue`、`@WrapOperation`、`@WrapWithCondition`、
  `@ModifyReceiver`、`@ModifyExpressionValue`、`@Local`、`@Share`、表达式注入等直接可用；
  如果初始化失败，启动日志会明确打印 `[WikiKernel/MixinExtras] 初始化失败`，普通 Mixin 仍会继续工作。
- **侧为 SERVER**：Mixin 配置里的 `mixins` / `server` 列表会生效；`client` 列表不会。
  服务端项目没有 obfuscation / refmap，启动器已关闭 refmap，直接写普通 Java 类名即可。
- **运行时为 Java 21+**（项目 toolchain 为 21）；如果目标类由更高版本 JDK 编译，Mixin 可能打印
  class version 警告，建议插件自身也按 Java 21 编译，或在配置里设置合适的
  `compatibilityLevel`。
- **安全**：Mixin 可以执行任意字节码，权限等同于修改整个服务端。`plugins/` 目录只应放可信插件；
  不要为了兼容不可信插件而授予写插件目录的权限。
- **与 `CoreAopHook` 的分工**：普通 Spring Bean 方法拦截优先用 `CoreAopHook`（轻量、可动态卸载）；
  需要修改 final 方法、构造器、静态方法、第三方库或 core 内部实现时再用 Mixin。

## 故障排查

- 启动日志出现 `[WikiKernel/Mixin] 未发现插件 Mixin 配置`：插件没有声明、配置文件名不匹配，或插件不在
  `plugins/` 目录；检查 `plugin.mixins` / `*.mixins.json`。
- 出现 `Mixin agent 未启用`：运行的是旧版 core jar（manifest 没有 `Launcher-Agent-Class`），
  或手动启动时跳过了内嵌 Agent；请用 `gradlew dist` 重新生成并运行 `build/dist/wikikernel-server.jar`。
- 出现 `Mixin agent 已加载；未发现插件 Mixin 配置`：Agent 正常，但本次没有插件 Mixin。
- 出现 `Mixin 插件 ... 只能在启动时加载`：运行期新增的 Mixin 插件必须在重启后才会注册，启动器已跳过它。
- 需要详细日志：在启动参数里加 `-Dwikikernel.mixin.debug=true`，可看到每个类的转换结果和 Mixin
  配置准备信息。
