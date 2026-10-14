# Popups（页面预览，插件）

Popups / Page Previews 的 WikiKernel 移植（对应 MediaWiki 的 [Popups](https://www.mediawiki.org/wiki/Extension:Popups)），
从 core 内置功能拆出为独立插件（`plugin/popups`）。

## 功能

- 桌面端（宽度 ≥ 720px）鼠标悬停正文区页面链接 0.5 秒 → 显示该页摘要与缩略图浮层；
- 键盘可达（Tab 聚焦链接同样显示）；红链 / 特殊页 / `?action=`、`?oldid=` 链接与 `.no-preview` 不预览；
- 数据来自 **core 的通用 API** `GET /api/page-preview?title=…`（保留在 core：含读权限校验与 404 语义，
  相当于 MediaWiki 里由 API 提供摘要、Popups 只做界面）；
- 静态资源：`/plugins/popups/popups.js`（脚本）与 `/plugins/popups/popups.css`（浮层样式，原 core `base.css`
  的 `.mw-popup` 区块），功能开启时由 `HtmlHeadProvider` 注入词条阅读页。

## 兼容性

- 功能开关沿用 **`features.popups`**（`特殊:权限配置 → 站点设置` 或 `LocalSettings.yaml`）：
  关闭时不注入脚本与样式（与迁移前一致）；`WIKI_CONFIG.popups` 开关仍由 core 注入；
- 预览 API（`/api/page-preview`）、摘要 / 缩略图生成、负缓存与权限语义不变；
- 浮层类名（`.mw-popup*`）与交互行为不变。

## 与迁移前的差异

- 脚本与样式 URL 从 `/js/popups.js`、`base.css` 内的区块变为 `/plugins/popups/popups.js`、`/plugins/popups/popups.css`；
- core 不再内置 Popups 前端（卸载 / 禁用插件后悬停不再有浮层），但预览 API 仍在。

## 测试

`PopupsHeadProviderTest` 覆盖开关注入、脚本绑定范围（只绑 `#bodyContent`）、资源随插件分发。

> **功能开关**：`features.popups`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
