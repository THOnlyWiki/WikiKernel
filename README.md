# WikiKernel

**一个用 Java 21 + Spring Boot 4 写的 Wiki 引擎**：仿 MediaWiki 的页面 / 版本 / 命名空间模型、Wikitext 渲染与模板系统、
特殊页面注册中心、用户与权限、MediaWiki 兼容的 `/api.php` 与 `mw` JS 兼容层；皮肤、扩展语法与治理功能（审核、滥用过滤器、
评论、私信、AI 代理…）全部做成**可选插件**，core 编译期不依赖任何插件。

多模块骨架：

| 模块 | 说明 |
| --- | --- |
| `common` | 通用代码：插件 SPI、扩展点、**共享模型（`common.data` 实体类）**、工具类。不依赖 Spring / core / 插件 |
| `core` | 核心代码：Web 服务 + 插件加载器 + 插件 Spring 子容器。**编译期不依赖任何插件** |
| `plugin/*` | 可选插件，放在 `plugin/` 目录下即可，**每个插件都能独立编译**，互不依赖 |

构建约定：所有 Java 模块（`common` / `core` / `plugin/*`）由根构建脚本统一启用 Lombok
（`compileOnly` + `annotationProcessor`，测试作用域同样），版本在 `gradle.properties` 的
`lombokVersion` 中（与 Spring Boot BOM 保持一致）；新增插件自动生效，无需在每个模块重复声明。

配置来自根目录的 **`LocalSettings.yaml`**（相当于 MediaWiki 的 `LocalSettings.php`）：**它不用手写**——
首次启动时文件不存在，应用进入安装模式，访问 `/init` 向导生成（可参考仓库里的
[`LocalSettings.example.yaml`](LocalSettings.example.yaml)）；`application.yaml` 只放框架级默认值。
怎么跑起来、有哪些坑见下面「[快速开始](#快速开始)」。

## 核心特性

- **页面与命名空间**：条目 / 模板 / 分类 / 文件 / 用户 / 特殊 等命名空间，标题归一化、繁简与别名重定向、`/wiki/<标题>` 路由。
- **版本与差异**：每次保存落一个修订版本，历史 / 行级版本对比 / 回退，页面保护与移动，`#REDIRECT` 重定向。
- **Wikitext 渲染**：模板与参数（`{{{参数}}}`）、解析器函数（`#if` / `#switch` / `#invoke`…）、魔术字、行为开关、模板反查与分类索引，渲染结果可缓存。
- **特殊页面**：注册中心式实现，名字可本地化（`特殊:` 与 `Special:` 都认）——搜索、全部页面、最近更改、统计、日志、版本、参数设置、权限配置…（见 [功能详述](docs/FEATURES.md)）。
- **用户与权限**：注册 / 登录 / 记住我 / 忘记密码（`特殊:重置密码`）、用户组与权限（管理员、行政员、机器人、核查员…）、封禁用户与 IP、请求速率限制。
- **MediaWiki 兼容 API**：`/api.php` 的 `action=query` / `edit` / `parse` / `upload` 等模块与错误格式，以及 `mw` / `mw.loader` / `mw.Api` 的 JS 兼容层——见 [接口与兼容 API](docs/API.md)。
- **内容扩展**：模板文档（TemplateData）、微件（Widgets）、Gadgets 用户脚本、Lua 模块（Scribunto 子集）、数学公式、时间轴、图形、跨 Wiki 链接表——见 [Wikitext 与内容标记](docs/WIKITEXT.md)。
- **文件与多媒体**：上传 / 图片预览 / 说明页 / 版本，音视频播放器与图片放大查看（插件 `media-viewer`），批量上传（插件 `multi-upload`）。
- **皮肤与前端界面**：Vector / Fallback / plain 皮肤、页面自定义 `Wiki.css` / `Wiki.js` 与 `皮肤名称.css/js`、TypeScript 代码页面、OOUI 风格对齐——见 [皮肤与前端界面](docs/SKIN.md)。
- **配置与数据**：`application.yaml` + `LocalSettings.yaml` + 站点设置页三处同源；Auto-DDL（SQLite / H2 / MySQL）、MyBatis-Plus 数据访问、可选的 Redis 页面正文缓存——见 [配置与数据](docs/CONFIG.md)。
- **国际化**：core 与插件各自带 `messages*.properties`（zh / en），界面文案逐条本地化，语言回退 `zh-CN → zh → en`。
- **插件体系**：`common` 定义 SPI 与扩展点（解析器函数 / 标签 / 页面显示 / 编辑与注册钩子 / 站点设置分区 / AOP…），插件跑在 core 的 Spring 子容器里，可在运行时装卸、互不依赖——见 [插件开发与运行](docs/PLUGIN-DEV.md)。
- **安装与运维**：`/init` 安装向导（核心 + 每个插件一个 tab，合并写回配置）、`run.bat` / `stop.bat`、文件日志、管理员触发的插件热重载。
- **AI 代理**（自研插件 `ai-agent`）：多厂商（OpenAI 兼容 / Anthropic / Gemini）对话 + 只读工具 + 待确认的编辑提案。

## 内置插件一览表

`plugin/` 下共 **29 个**插件目录：**26 个 MediaWiki 扩展移植**（含皮肤插件 `plain-skin`）、**2 个自研插件**
（`ai-agent`、`fingerprint-login`）与 **1 个演示插件** `dep-demo`（`installPlugins` 不会收集它）。
每个插件 id 都指向它自己的 `README.md`（移植范围、配置键与与 MediaWiki 原版的差异）。

> 旧版 README 在「内置插件」一节里写的是「28 个」：那处漏算了自研的 `ai-agent`，又把皮肤插件 `plain-skin` 算进了
> 「26 个移植」里还额外计了一次。以本表为准（29 个目录），旧表述原样保留在 [插件开发与运行](docs/PLUGIN-DEV.md) 里。

| 插件 id | 对应 MediaWiki 扩展 | 一句话能力 |
| --- | --- | --- |
| [`no-title`](plugin/no-title/README.md) | [NoTitle](https://www.mediawiki.org/wiki/Extension:NoTitle) | `__NOTITLE__` / `__无标题__` 行为开关隐藏页面大标题 |
| [`variables`](plugin/variables/README.md) | [Variables](https://www.mediawiki.org/wiki/Extension:Variables) | 页面级变量解析器函数：`#vardefine` / `#vardefineecho` / `#var` / `#varexists` / `#var_final` |
| [`hit-counters`](plugin/hit-counters/README.md) | [HitCounters](https://www.mediawiki.org/wiki/Extension:HitCounters) | 浏览计数：`{{NUMBEROFVIEWS}}` / `{{NUMBEROFPAGEVIEWS}}`、`特殊:最多浏览` 与页脚计数 |
| [`wiki-seo`](plugin/wiki-seo/README.md) | [WikiSEO](https://www.mediawiki.org/wiki/Extension:WikiSEO) | `{{#seo:}}` / `<seo>`：标题改写、description、OpenGraph / SchemaOrg 等元信息 |
| [`template-data`](plugin/template-data/README.md) | [TemplateData](https://www.mediawiki.org/wiki/Extension:TemplateData) | `<templatedata>` 模板参数文档 + `action=templatedata` API |
| [`category-tree`](plugin/category-tree/README.md) | [CategoryTree](https://www.mediawiki.org/wiki/Extension:CategoryTree) | `<categorytree>` 分类树、`特殊:分类树`、`action=categorytree` API |
| [`input-box`](plugin/input-box/README.md) | [InputBox](https://www.mediawiki.org/wiki/Extension:InputBox) | `<inputbox>` 创建 / 搜索表单 + 前端组装脚本 |
| [`easy-timeline`](plugin/easy-timeline/README.md) | [EasyTimeline](https://www.mediawiki.org/wiki/Extension:EasyTimeline) | `<timeline>` 服务端渲染成内联 SVG |
| [`math`](plugin/math/README.md) | [Math](https://www.mediawiki.org/wiki/Extension:Math) | `<math>` / `<chem>` 公式占位（KaTeX 前端渲染） |
| [`flash-player`](plugin/flash-player/README.md) | [FlashPlayer](https://www.mediawiki.org/wiki/Extension:FlashPlayer) | `<flash-player>` 占位（Ruffle WASM 前端播放） |
| [`popups`](plugin/popups/README.md) | [Popups](https://www.mediawiki.org/wiki/Extension:Popups) | 悬停链接显示摘要浮层（预览 API 仍在 core） |
| [`graph`](plugin/graph/README.md) | [Graph](https://www.mediawiki.org/wiki/Extension:Graph) | `<graph>` 标签与「图表:」命名空间整页图（Vega / Vega-Lite） |
| [`media-viewer`](plugin/media-viewer/README.md) | [TimedMediaHandler](https://www.mediawiki.org/wiki/Extension:TimedMediaHandler) + [MultimediaViewer](https://www.mediawiki.org/wiki/Extension:MultimediaViewer) | 音视频播放器（词条嵌入 + 文件页）与图片放大查看 |
| [`widget`](plugin/widget/README.md) | [Widgets](https://www.mediawiki.org/wiki/Extension:Widgets) | `{{#widget:名称\|参数=值}}` 渲染「微件:」页面（HTML + 迷你 Smarty） |
| [`gadgets`](plugin/gadgets/README.md) | [Gadgets](https://www.mediawiki.org/wiki/Extension:Gadgets) | `WikiKernel:Gadgets-definition` 定义、按用户加载 Gadget JS / CSS、`特殊:Gadgets` |
| [`page-banner`](plugin/page-banner/README.md) | [WikidataPageBanner](https://www.mediawiki.org/wiki/Extension:WikidataPageBanner) | `{{PAGEBANNER:文件\|caption=说明}}` 标题上方横幅（白名单 / 逐页 / 轮换） |
| [`confirm-edit`](plugin/confirm-edit/README.md) | [ConfirmEdit](https://www.mediawiki.org/wiki/Extension:ConfirmEdit) | 编辑 / 创建页面的算术验证码 |
| [`registration-quiz`](plugin/registration-quiz/README.md) | [QuestyCaptcha](https://www.mediawiki.org/wiki/Extension:QuestyCaptcha) | 注册问答验证：两个等长数组随机抽题 |
| [`comments`](plugin/comments/README.md) | [Comments](https://www.mediawiki.org/wiki/Extension:Comments) | `<comment />` / `<comments />` 页面评论区 + `/api/comments` |
| [`multi-upload`](plugin/multi-upload/README.md) | [MsUpload](https://www.mediawiki.org/wiki/Extension:MsUpload) | 上传页与编辑器上传对话框的多文件批量上传 |
| [`social-profile`](plugin/social-profile/README.md) | [SocialProfile](https://www.mediawiki.org/wiki/Extension:SocialProfile) | 用户页资料卡、头像上传、`特殊:私信`（留言板 / 好友 / 礼物 / 勋章 / 随机用户） |
| [`moderation`](plugin/moderation/README.md) | [Moderation](https://www.mediawiki.org/wiki/Extension:Moderation) | 编辑 / 上传 / 删除先进审核队列，`特殊:审核` 通过或拒绝 |
| [`abuse-filter`](plugin/abuse-filter/README.md) | [AbuseFilter](https://www.mediawiki.org/wiki/Extension:AbuseFilter) | 滥用过滤器：条件求值、过滤器管理 / 历史 / 测试、命中日志与拦截 |
| [`rename-user`](plugin/rename-user/README.md) | [Renameuser](https://www.mediawiki.org/wiki/Extension:Renameuser) | `特殊:重命名用户`（可一并移动用户页 / 用户讨论页及子页面） |
| [`user-merge`](plugin/user-merge/README.md) | [UserMerge](https://www.mediawiki.org/wiki/Extension:UserMerge) | `特殊:用户合并`：把源账号数据归入目标账号，可选删除源账号 |
| [`plain-skin`](plugin/plain-skin/README.md) | [Manual:Skins](https://www.mediawiki.org/wiki/Manual:Skins)（皮肤机制，无独立扩展页） | 额外皮肤 `plain`：居中卡片、无侧栏，布局与样式都在插件 jar 内 |
| [`ai-agent`](plugin/ai-agent/README.md) | **WikiKernel 自研**（无对应扩展；参见 [AI 类扩展分类](https://www.mediawiki.org/wiki/Category:Artificial_intelligence_extensions)） | 多厂商 AI 对话 + 只读工具 + 待确认提案：`特殊:AI代理` + `/api/ai-agent/*` |
| [`fingerprint-login`](plugin/fingerprint-login/README.md) | **WikiKernel 自研**（无对应扩展） | 浏览器指纹登录，经 `common.service.LoginSession` 建立与密码登录一致的会话 |
| [`dep-demo`](plugin/dep-demo/README.md) | **演示插件**（不是 MediaWiki 扩展移植，不随发行） | 演示 `plugin.requiresCore` / `plugin.clientRequires` / `CoreAopHook` 三项平台能力 |

`.\gradlew.bat installPlugins` 会把上表里除 `dep-demo` 以外的插件 jar 收集到根目录 `plugins/` 供 core 加载。

## 快速开始

需要 JDK 21+（仓库自带 Gradle Wrapper，无需安装 Gradle）：

```powershell
# 全量构建
.\gradlew.bat build

# 校验依赖方向
.\gradlew.bat checkPluginDependencies
```

### 独立编译单个插件（不会构建 core）

插件放在 `plugin/<名字>/` 后，只构建这一个插件（Gradle 只编译它和 `common`，不会碰 core 或其他插件）：

```powershell
.\gradlew.bat :plugin:your-plugin:build
```

### 运行 core（加载插件）

```powershell
# 1. 构建插件并把 jar 收集到根目录 plugins/
.\gradlew.bat installPlugins

# 2. 启动 core
.\gradlew.bat :core:bootRun          # 方式一：Gradle（控制台就是当前终端）
.\run.bat                            # 方式二（推荐）：打包并打开一个可见的 cmd 窗口显示日志
.\gradlew.bat :core:bootJar
java -jar core\build\libs\core-0.0.1-SNAPSHOT.jar   # 方式三：直接运行
```

- **`run.bat [端口]`**（默认 18080）：打开一个标题为 `WikiKernel - 端口 18080` 的 cmd 窗口，
  控制台实时输出日志（UTF-8），退出后窗口保留（可直接关闭）；**每次启动都会看到这个窗口和日志**；
  启动时会先把 `core\build\libs` 下的 jar **复制到 `run\.launch\wikikernel-<端口>.jar` 再运行副本**——
  这样之后随时 `gradlew bootJar` 重建都不会影响正在运行的实例；
- **`stop.bat [端口] [nopause]`**：结束监听该端口的进程（`stop.bat 18080 nopause` 供脚本调用不暂停）。

> **为什么不直接 `java -jar core\build\libs\...`？**
> 运行中的 JVM 是按需从 jar 里读 class 的（例如 logback 的 `ThrowableProxy` 只在「记录一条异常日志」时
> 才第一次加载）。如果期间重新构建，jar 被原地替换，再遇到浏览器中途断开等让 Tomcat 记录错误的情况，
> 就会弹出 `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy` 这类随机报错（无害但很吵，
> 重启即可恢复）。用 `run.bat` 启动走副本；用 IDE（IntelliJ）直接运行时，运行目录是
> `core\build\classes`，同样不要在运行中执行 `gradlew` 构建——需要边改边测时建议用 `run.bat` 或先停服。

### 动态装卸插件（服务器所有者）

插件 jar **不会**被运行中的 JVM 锁定：启动时会把 `plugins/` 里的每个 jar 按内容哈希复制到
`run/.plugins/<名字>-<哈希>.jar`（配置项 `wikikernel.plugins.runtime-dir`）后**加载副本**，
源目录里的文件随时可以替换、删除、新增（Windows 上不会提示「文件被另一个程序占用」）。

改完 `/wiki/` 里的文件后，用管理员账号触发一次重新扫描即可生效，**不需要重启应用**：

```powershell
# 管理员登录状态下（浏览器 / 带会话的请求）
curl -X POST http://localhost:18080/api/plugins/reload
```

- 返回 `{"code":200,"data":{"started":["wiki-seo"],"unloaded":["math"],"unavailable":{},"total":11}}`：
  本次启动 / 卸载的插件 id、没起来的插件 → 原因、当前已加载插件总数；
- 判断依据是**源文件内容哈希**：源 jar 被删除或替换 → 自动卸载（旧副本尽力清理）；
  源目录里出现新 jar → 按 `plugin.requires` 依赖顺序启动；只改动文件时间戳不会触发重启；
- 权限：`POST /api/plugins/reload` 只有**管理员 / 行政员**能调用（匿名 401、普通用户 403）；
- 单个插件启动失败只记日志并跳过，不影响其它插件与应用（与启动时一致）；
- IDE 里以 `classpath-scan` 方式加载的插件不参与动态装卸（那是调试用）。

### 日志

- **控制台**：`时:分:秒.毫秒 级别 [线程] logger : message`（支持 ANSI 的终端自动上色，Windows 10+ 的 cmd 默认可用）；
- **文件**：`run/logs/wikikernel.log`（相对启动目录；UTF-8，完整日期，10MB 滚动、保留 7 份、总量 200MB）；
- 降噪：HikariCP / MyBatis / DispatcherServlet 默认只记 warn；需要排查时在 `application.yaml` 或
  `LocalSettings.yaml` 里把 `logging.level.<包名>` 调成 `debug`。

### 首次部署：`/init` 安装向导

没有配置文件（`LocalSettings.yaml` 不存在）时，应用启动后自动进入**安装模式**：
页面请求都会跳到 `/init`（`/api/**` 返回 409；静态资源与 `/api/version` 放行）。向导按 tab 组织：

- **核心** tab（必填）：**站点名称**（浏览器标题栏 / 页眉 / 页脚 / 搜索框 / API 的显示名，默认 `WikiKernel`，
  写入 `wikikernel.site.name`）、数据库引擎（SQLite / H2 / MySQL——MySQL 可填主机 / 库名 / 账号密码）、
  初始**账号**（必填，**同时获得「管理员 + 行政员」权限**）、邮件发送 SMTP（可选，含端口与站点地址）、
  **功能开关**（是否启用**用户交互**（头像上传 + 私信）、**评论**、**多文件批量上传**、**微件**、**注册**与**滥用过滤器**，写入 `wikikernel.features`，
  之后也可在 `特殊:权限配置 → 站点设置` 修改）；
  初始密码以 **MediaWiki 格式哈希**（`password-hash`，`:pbkdf2:…`）写入配置，登录后请尽快修改；
- **初始化之后再次进入 `/init`**（用密钥解锁）：数据库与初始账号不再显示（改它们请直接编辑 `LocalSettings.yaml`，
  避免误切库 / 覆盖账号），**邮件与功能开关仍然可以修改**（表单会预填当前值，密码留空 = 保持不变），
  保存后点「写入配置并重启」合并写回；插件 tab 照常可以重新初始化；
- 初始账号只是**一次性的引导种子**（`wikikernel.setup.initial-accounts`）：启动时账号不存在才创建，
  已存在则跳过，**不会覆盖数据库里的密码 / 邮箱 / 权限**。管理员在数据库里建好之后，这段可以安全删除；
  唯一影响是「只删数据库、保留 LocalSettings.yaml」时无法自动重建管理员——整库重装请把 `LocalSettings.yaml`
  一并删除再访问 `/init`（向导会重新写入初始账号）。
- **插件** tab：**每个已加载插件都有入口**；注册了 `SetupStep` 扩展点的插件带表单，
  未注册的只展示插件信息；**核心未完成前插件 tab 锁定**（接口返回 409 `setup.coreRequired`）；
- **完成初始化并重启**：把核心与各插件设置**合并**写入 `LocalSettings.yaml`（插件之间互不覆盖），
  然后自动重启（`java -jar` 启动时；IDE 里运行会提示手动重启）；重启后按初始账号创建用户并授予用户组，
  并**仅在当前数据库尚未种过内容时**写入**示例首页**（`首页`）、**MediaWiki 风格默认侧边栏**
  （`WikiKernel:Sidebar`：导航 / 工具）与 `帮助:编辑`（编辑工具栏「帮助」的落地页）——之后可自由修改；
  页面自动回到首页。
- **`/init` 密钥（可重复进入）**：首次完成安装时会**随机生成密钥**写入 `wikikernel.setup.key`，
  完成页会显示一次（请保存）；已安装后再访问 `/init` 需要它——直接打开会看到密钥输入表单，
  也可以 `/init?key=密钥` 一键解锁（会话内有效）。解锁后：
  - 核心 tab 变为只读说明（改核心请直接编辑 `LocalSettings.yaml`）；
  - 插件 tab 可继续初始化 / **重新初始化**，再次点「写入配置并重启」只**合并**插件设置与密钥，
    **不会覆盖**核心配置与其它插件已写的内容。

```yaml
wikikernel:
  setup:
    mode: auto              # auto（默认：没有配置文件时进入安装模式）| off | force
    file: LocalSettings.yaml
    restart-enabled: true   # 测试 / IDE 环境可关，完成后手动重启
    key: "…"                # 由向导生成：再次访问 /init 的密钥（绝对唯一密码）
```

> 插件开发者：实现 `common` 的 `SetupStep`（`id` / `displayName` / `fields` / `apply`）并在 `onStart` 里
> `context.register(SetupStep.class, …)`，即可在安装向导里获得一个 tab；
> `SetupContext.putGlobal("wikikernel.xxx", …)` 与 `putPluginSetting(key, …)` 写入的配置会与其它插件合并。

验证接口：

| 接口 | 说明 |
| --- | --- |
| `GET /api/version` | core 版本 + 已加载插件数量 |
| `GET /api/plugins` | 当前加载的插件列表 |
| `POST /api/plugins/reload` | 重新扫描插件目录：卸载源 jar 已删除 / 替换的插件、启动新插件（仅管理员 / 行政员） |
| `GET /api/render?slug=home` | 演示 `PageRenderHook` 扩展点（无插件时输出原始 html） |
| `GET /api/search?q=WikiKernel` | 搜索（core 数据库搜索 + 插件 `SearchProvider` 聚合） |
| `GET /wiki/特殊:搜索?q=...` | 搜索页面（Vector 顶栏搜索框落地页） |
| `GET /wiki/{title}` | Wiki 页面浏览（Thymeleaf 模板 `templates/wiki/view.html`）；页面不存在时返回 **404 + 自定义 404 页**（`WikiKernel:404` 内容，含「创建本页面」链接），隐藏页同样 404 |
| `GET /wiki/特殊:特殊页面` | 特殊页注册表列表页 |

### 单独运行插件（不启动 core）

插件在自己的 `build.gradle` 里配置好 `application.mainClass` 后，可以直接运行插件入口：

```powershell
.\gradlew.bat :plugin:your-plugin:run
```

独立运行**不启动 Spring**（走纯 Java 的 `SimplePluginContext`），用来快速调试插件自身的逻辑；
它和 core 加载插件使用同一套 SPI 与扩展点。

## 文档索引

README 只保留「是什么 + 核心特性 + 插件一览 + 启动指南」，其余正文已按主题迁到 `docs/`：

| 文档 | 内容 |
| --- | --- |
| [docs/API.md](docs/API.md) | MediaWiki 兼容 API（`/api.php`）、示例请求、`mw` / `mw.loader` JS 兼容层、跨 Wiki（Interwiki）、页面导出与导入、页面预览 |
| [docs/PLUGIN-DEV.md](docs/PLUGIN-DEV.md) | 依赖方向、构建与运行、插件里使用 Spring、新增一个插件、插件加载原理、依赖声明与 `CoreAopHook`、**29 个内置插件清单**（扩展点 / 平台能力 / 各插件配置 / 与 MediaWiki 原版的差异） |
| [docs/CONFIG.md](docs/CONFIG.md) | `application.yaml` 与 `LocalSettings.yaml`、Auto-DDL、MyBatis-Plus 数据访问、Redis 页面正文缓存、站点名称、访问控制、界面本地化（i18n） |
| [docs/WIKITEXT.md](docs/WIKITEXT.md) | Wikitext 渲染与模板系统、微件（Widgets）、Gadgets、Lua 模块、数学公式、时间轴、图形、分类与模板反查、`#REDIRECT` |
| [docs/FEATURES.md](docs/FEATURES.md) | 功能详述：特殊页面、用户与登录、用户组与权限、参数设置、AI 代理、文件与多媒体、邮件、封禁、评论 / 私信 / 头像、操作日志、页面保护与移动、禁用标题名单、滥用过滤器、错误页、扩展功能等 |
| [docs/SKIN.md](docs/SKIN.md) | 皮肤机制与内置皮肤、页面自定义 CSS / JS 与代码页面（含 TypeScript）、`mw.wikiImport` 用户脚本、与 MediaWiki / OOUI 的样式对齐、插件皮肤、`WikiKernel:Sidebar` |

每个插件的细节（移植范围、配置键、与 MediaWiki 原版的差异）在各插件的 `plugin/<id>/README.md`，入口见上面的
[内置插件一览表](#内置插件一览表)。界面文案与配置键的约定见 [docs/CONFIG.md](docs/CONFIG.md) 与
[docs/PLUGIN-DEV.md](docs/PLUGIN-DEV.md)。

## 许可证

本项目以 **GNU General Public License v3.0** 发布，许可全文见 [LICENSE](LICENSE)。

这意味着你可以自由地使用、修改、分发本项目（包括商用），但**衍生作品必须同样以 GPL-3.0 开源**，
并保留版权声明与许可全文。分发时请一并附带 [LICENSE](LICENSE)。

> 说明：本项目在行为上移植/参考了大量 MediaWiki 及其扩展的设计与文档描述；仓库内内置的第三方资源
> （例如 TypeScript 编译器、Ruffle、OOUI 等前端库）**各自保留其原始许可**，详见相应目录下的许可文件。