# EasyTimeline（插件）

EasyTimeline 扩展的 WikiKernel 移植（对应 MediaWiki 的 [EasyTimeline](https://www.mediawiki.org/wiki/Extension:EasyTimeline)），
从 core 内置功能拆出为独立插件（`plugin/easy-timeline`）。

## 功能

- `<timeline>…</timeline>`：把 EasyTimeline 文本定义解析成**内联 SVG**（服务端渲染，不依赖 Ploticus / Perl / 外部服务）；
- 支持常用命令：`ImageSize` / `PlotArea` / `AlignBars` / `DateFormat` / `Period` / `TimeAxis` /
  `ScaleMajor` / `ScaleMinor` / `Colors` / `BackgroundColors` / `PlotData`（含 `barset`）/ `TextData`；
- 所有文字做 XML 转义、颜色白名单校验，输出直接作为可信块插入页面。

## 兼容性

- 功能开关沿用 **`features.timeline`**（`特殊:权限配置 → 站点设置` 或 `LocalSettings.yaml`）：
  关闭时标签按原文转义显示（与迁移前一致）；
- 解析失败提示沿用 core 消息键 `timeline.invalid`；
- 生成的 SVG（`class="mw-timeline"` / `mw-timeline-svg`）与迁移前逐字节一致（渲染器代码原样搬迁）。

## 与迁移前的差异

- core 不再内置 `<timeline>` 解析：卸载 / 禁用本插件时标签按原文显示（不再生成 SVG）；
- 可视化编辑器仍把 `<timeline>` 识别为整块占位（core 的 tokenizer 保留该标签）；
- `WikiSections` 的分节逻辑仍把 `timeline` 当块级标签（行为不变）。

## 测试

`TimelineRendererTest`（原 core 测试，已随代码迁入插件）覆盖复杂定义的主要分支。

> **功能开关**：`features.timeline`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
