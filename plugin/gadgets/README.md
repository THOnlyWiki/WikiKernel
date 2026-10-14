# gadgets（MediaWiki Gadgets 扩展移植）

定义页 `WikiKernel:Gadgets-definition`，资源页在 `Gadget:` / `小部件:` 命名空间：

```text
* QuickEdit | 快速编辑工具 | QuickEdit.js | QuickEdit.css | default
* DarkMode | 深色模式 | DarkMode.js
* AdminTool | 仅在组内可见 | AdminTool.js | rights=管理员 | default
* Internal | 隐藏：不出现在个人设置里 | Internal.js | hidden
* WithDeps | 声明模块依赖 | WithDeps.js | dependencies=oojs-ui-core,mediawiki.api
```

- `<pre>` 里的示例行不会当成真实 Gadget 定义（只统计 `pre` 外的顶层 `*` 行）；
- 资源名可以写相对名（依次尝试 `Gadget:` / `小部件:`）或完整标题；资源页不存在则跳过（不注入 404）；
- `.ts` / `.tsx` 资源只在 core 的 TypeScript 服务端编译开启时收作脚本（`?action=raw&ctype=text/javascript` 返回编译产物）；
- **加载规则**：`default` 对所有用户（含匿名）生效；其余登录用户在 **参数设置 → 小工具** 里勾选
  （存 `wiki_user.gadgets`，逗号分隔；**一个都没勾 = `!`**，表示明确关闭全部，含默认启用的）；
  `rights=组1,组2` 只对组内用户可见 / 加载；
- **开关**：`features.gadgets`（默认开启）：关闭后不注入脚本 / 样式、不显示个人设置，但**不覆盖**已保存的偏好；
- **管理**：`特殊:Gadgets`（`特殊:小部件管理`）列出定义、资源与启用状态；定义页 / 资源页的编辑权由 core 的
  访问控制规则决定（「小部件管理员」可编辑，普通「小部件编辑者」只管 Widgets）。

## 扩展点

| 扩展点 | 用途 |
| --- | --- |
| `PreferenceSection` | 参数设置里的「小工具」标签页（core 动态渲染标签 + 面板） |
| `HtmlHeadProvider`（`siteWide()`） | 注入 Gadget 的 CSS / JS 与 `WIKI_CONFIG.gadgetDependencies` |
| `SpecialPage` | `特殊:Gadgets`（插件自带模板 `templates/special/gadgets.html`） |
| `@RestController` | `POST /api/gadgets/preferences`（保存个人偏好） |
| `common.service.UserSettings` | 读写 `wiki_user.gadgets`（core 的用户偏好桥） |
| `common.service.CurrentUser` | 当前用户与用户组（`rights=` 过滤、匿名只加载默认 Gadget） |
| `MessageBundle` | `gadget.*` / `gadgets.*` 文案（core 里已删除同名键） |

## 与 MediaWiki 原版的差异

- 没有 ResourceLoader：Gadget 脚本以经典 `<script src>` 加载（不是模块），`dependencies=…` 只用于
  「未内置模块」告警（`js-compat.js`），不会真的按依赖顺序加载模块；
- 定义页**不会自动创建**：登录后在 `特殊:Gadgets` 或参数设置 → 小工具里点「创建定义页」即可
  （迁移前 core 会在启动时写入一个示例定义页，现在改为按需创建，避免插件偷偷改内容）；
- `hidden` 语义与迁移前 core 保持一致：隐藏的 Gadget 不出现在个人设置里，也**不会**被加载
  （MediaWiki 里 hidden 是「对所有人生效但不列出」，这一点不同）；
- 没有 MediaWiki 的 `Gadget definition` 版本历史界面、`Gadget-*.css` 的 LESS 编译与 `targets=desktop/mobile` 区分。

> **功能开关**：`features.gadgets`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
