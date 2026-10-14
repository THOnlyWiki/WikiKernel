# Math（插件）

Math 扩展的 WikiKernel 移植（对应 MediaWiki 的 [Math](https://www.mediawiki.org/wiki/Extension:Math) 的客户端渲染模式），
从 core 内置功能拆出为独立插件（`plugin/math`）。

## 功能

- `<math>TeX</math>`（`display="block"` 为独立公式）与 `<chem>…</chem>`（mhchem 化学式）：
  服务端生成占位 `<span class="mw-math" data-tex="…">`，标签内容原样作为 TeX（不做 wikitext 解析，与 MediaWiki 一致）；
- 前端脚本 `static/plugins/math/math.js`（经 `/plugins/math/math.js` 提供）懒加载
  **KaTeX**（`/vendor/katex/…`，随 core 分发）渲染，失败时显示 TeX 源码（`mw-math-fallback`）；
- 功能开启时由 `HtmlHeadProvider` 注入脚本（词条阅读页）。

## 兼容性

- 功能开关沿用 **`features.math`**（`特殊:权限配置 → 站点设置` 或 `LocalSettings.yaml`）；
  关闭时标签按原文转义显示（与迁移前一致）；
- 空公式提示沿用 core 消息键 `math.empty`；
- 占位 HTML（`mw-math` / `mw-math-block` / `data-tex` / `data-display` / `data-chem` / `mw-math-fallback`）
  与迁移前逐字节一致；`WIKI_CONFIG.math` 开关仍由 core 注入。

## 与迁移前的差异

- core 不再内置 `<math>` / `<chem>` 解析：卸载 / 禁用本插件时标签按原文显示（不再生成占位）；
- 前端脚本 URL 从 `/js/math.js` 变为 `/plugins/math/math.js`；KaTeX 运行时仍从 core 的 `/vendor/katex/…` 加载；
- 可视化编辑器仍把 `<math>` / `<chem>` 识别为整块占位（core 的 tokenizer 保留这两个标签）。

## 测试

`MathTagTest` 覆盖行内 / 块级 / chem 占位、空白压缩、属性转义、空公式错误与开关关闭行为。

> **功能开关**：`features.math`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
