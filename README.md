# WikiKernel

WikiKernel 是一个用 Java 21 和 Spring Boot 4 写的 wiki 引擎。它按 MediaWiki 的
方式来组织内容：页面与修订版本、命名空间、Wikitext 与模板、特殊页面，以及兼容
MediaWiki 的 `/api.php` 和 `mw` JS API。皮肤、扩展语法和治理功能都做成插件，core
编译期不依赖任何插件。

WikiKernel 是：

- 面向 MediaWiki 使用习惯的：标题、命名空间、模板、特殊页的规则尽量与 MediaWiki 一致；
- 可扩展的：core 只提供 SPI 和扩展点，插件单独编译、可运行时装卸；
- 能直接跑起来的：一个可执行 jar 加一个配置文件，SQLite 即可起步；
- 中英双语的：界面文案在 core 与各插件自己的 `messages*.properties` 里。

需要 JDK 21 或更高，仓库自带 Gradle Wrapper，不用另外安装 Gradle。

```powershell
.\gradlew.bat build                     # 全量构建
.\gradlew.bat installPlugins            # 收集插件 jar 到 plugins/
.\gradlew.bat :core:bootJar             # 构建 core
.\run.bat                               # 启动，默认端口 18080
```

`run.bat` 会在一个 cmd 窗口里启动并实时打印日志。第一次启动时 `LocalSettings.yaml`
还不存在，应用进入安装模式，打开 <http://localhost:18080/init> 按向导完成初始化
（站点名称、数据库、初始账号，可选 SMTP 与功能开关）。安装细节、配置项、日志开关、
插件热重载见 [配置与部署](docs/CONFIG.md)；在 IDE 里运行、依赖 Maven 缓存等开发细节
见 [插件开发](docs/PLUGIN-DEV.md)。

## 扩展插件

`plugin/` 下有 39 个插件：30 个 MediaWiki 扩展或皮肤的移植，8 个自研插件，1 个演示插件。
`installPlugins` 会收集除 `dep-demo` 以外的 38 个。按用途分：

- 内容与标记：`math`、`easy-timeline`、`graph`、`widget`、`gadgets`、`template-data`、
  `input-box`、`category-tree`、`popups`、`page-banner`、`no-title`、`variables`、
  `hit-counters`、`wiki-seo`、`cite`（`<ref>` 引用）、`echo`（通知）、
  `dynamic-page-list`（`{{#dpl:}}`）、`vue-components`（`*.vue` 单文件组件）
- 编辑与治理：`moderation`、`abuse-filter`、`confirm-edit`、`registration-quiz`、`ai-crawler-block`
- 用户与社交：`social-profile`、`comments`、`rename-user`、`user-merge`、
  `fingerprint-login`、`external-auth-github`、`external-auth-qq`
- 文件与存储：`media-viewer`、`multi-upload`、`flash-player`、`oss-extension`（原图与历史版本同步到 OSS）
- 皮肤：`plain-skin`、`kawaii-timeless`
- 站点定制：`thonly-wiki-extensions`（活动日程、日历、地图）
- AI：`ai-agent`
- 演示：`dep-demo`（不随发行）

每个插件的移植范围、配置键和与 MediaWiki 原版的差异写在它自己的
`plugin/<id>/README.md` 里；完整清单、扩展点和各插件配置见
[插件开发](docs/PLUGIN-DEV.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [配置与部署](docs/CONFIG.md) | `application.yaml` 与 `LocalSettings.yaml`、Auto-DDL、数据访问、Redis 页面缓存、访问控制、i18n |
| [插件开发](docs/PLUGIN-DEV.md) | 依赖方向、插件里的 Spring、加载原理、扩展点、各插件配置 |
| [接口与 API](docs/API.md) | `/api.php`、`mw` / `mw.loader` 兼容层、跨 Wiki、导出与导入、页面预览 |
| [Wikitext](docs/WIKITEXT.md) | 模板与解析器函数、Widgets、Gadgets、Lua、数学公式、时间轴、图形、分类 |
| [功能详述](docs/FEATURES.md) | 特殊页面、用户与权限、参数设置、AI 代理、文件、邮件、封禁、日志、保护与移动 |
| [皮肤](docs/SKIN.md) | 皮肤机制、页面级 CSS / JS、TypeScript 代码页面、与 OOUI 的样式对齐 |
| [第三方登录](docs/THIRD-PARTY-LOGIN.md) | GitHub / QQ OAuth、回调地址、绑定与解绑 |
| [Mixin](docs/MIXIN.md) | 插件 Mixin 与 MixinExtras、启动期 Agent、热加载限制 |

## 参与

欢迎提交 issue 和 pull request。改完插件或 core 后，提交前至少跑一次
`.\gradlew.bat build` 和 `.\gradlew.bat checkPluginDependencies`；界面文案的约定见
[配置与部署](docs/CONFIG.md)，新增插件的步骤见 [插件开发](docs/PLUGIN-DEV.md)。

## 许可证

WikiKernel 以 GNU General Public License v3.0 发布，全文见 [LICENSE](LICENSE)。
仓库里附带的第三方前端资源（TypeScript、Ruffle、OOUI 等）各按自己的许可分发，
详见对应目录里的许可文件。
