# plain-skin（额外的 plain 皮肤插件）

一个**皮肤插件**（不是功能扩展移植）：注册精简的 `plain` 皮肤 —— 居中卡片、无侧栏。
MediaWiki 侧没有对应扩展页，皮肤机制见官方手册 [Manual:Skins](https://www.mediawiki.org/wiki/Manual:Skins)。

- 插件 id：`plain-skin`；模块：`:plugin:plain-skin`；入口类 `PlainSkinPlugin`，皮肤实现 `PlainSkin`；
- 布局与样式都在插件 jar 内：`templates/skins/plain/layout.html` + `static/skins/plain/plain.css`
  （不在 core 里，也不改动 core 的静态资源）；
- 本地化文案：`PlainSkinMessages` + `i18n/messages.properties` / `messages_en.properties`
  （`plugin.plain-skin.*`、皮肤名与说明 `skin.plain.name` / `skin.plain.description`）；
- 构建：`.\gradlew.bat :plugin:plain-skin:build`。

## 功能

| 部分 | 说明 |
| --- | --- |
| 皮肤 | `plain`：精简布局（居中卡片、无侧栏），所有链接与页签由 core 的皮肤上下文提供 |
| 默认皮肤 | `PlainSkinSetup`（`SetupStep`）：`/init` 的插件 tab 里可以勾选「将 plain 皮肤设为站点默认皮肤」（写站点设置） |
| 站点设置 | 皮肤在「特殊:权限配置 → 站点设置」的默认皮肤下拉里出现；用户也可以在 `特殊:参数设置 → 外观` 里自选 |

描述文件里**没有**声明 `plugin.featureKey`：皮肤不是功能开关，装了即可选。

## 与 MediaWiki 的差异

- MediaWiki 的皮肤是核心机制（`Manual:Skins`），这里做成插件，方便第三方皮肤照抄结构：
  一个 `WikiPlugin` + 一份 `layout.html` + 一份 CSS 就能提供一个皮肤；
- 皮肤名与说明走消息键（`skin.plain.name` / `skin.plain.description`），core 的内置皮肤
  （Vector 与不可禁用的 Fallback）不通过插件注册。

## 相关文档

- [皮肤与前端界面](../../docs/SKIN.md)（皮肤机制、内置皮肤、插件皮肤）
- [插件开发与运行](../../docs/PLUGIN-DEV.md)（插件清单、`SetupStep`、插件静态资源端点）
