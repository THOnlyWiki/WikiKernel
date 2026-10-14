# InputBox（插件）

InputBox 扩展的 WikiKernel 移植（对应 MediaWiki 的 [InputBox](https://www.mediawiki.org/wiki/Extension:InputBox)），
从 core 内置功能拆出为独立插件（`plugin/input-box`）。

## 功能

- `<inputbox>…</inputbox>`（标签内容为 `key=value` 行）→ 创建 / 搜索表单：
  - `type=create`（默认）/ `type=search` / `type=fulltext`；
  - `default` / `placeholder` / `id` / `buttonlabel` / `width`；
  - `prefix` / `preload` / `editintro`（前端脚本组装目标标题）；
- 前端脚本：`static/plugins/input-box/inputbox.js`，经 core 的插件资源端点 `/plugins/input-box/inputbox.js` 提供，
  仅在功能开启时由 `HtmlHeadProvider` 注入词条阅读页。

## 兼容性

- 功能开关沿用 **`features.input-box`**（`特殊:权限配置 → 站点设置` 或 `LocalSettings.yaml`）；
  关闭时标签按原文转义显示（与迁移前一致）。
- 按钮缺省文案沿用 core 消息键 `common.search` / `special.createPage`（跟随界面语言）。
- 表单结构与提交目标（`特殊:创建页面` / `特殊:搜索`）与迁移前完全一致。

## 与迁移前的差异

- core 不再内置 `<inputbox>` 解析：卸载 / 禁用本插件时标签按原文显示（不再生成表单）。
- 前端脚本 URL 从 `/js/inputbox.js` 变为 `/plugins/input-box/inputbox.js`（插件资源端点，缓存一天）。
- 可视化编辑器仍把 `<inputbox>` 识别为整块占位（core 的 tokenizer 保留该标签）。

## 测试

`plugin/input-box` 的单元测试覆盖创建 / 搜索表单、选项、转义与开关关闭行为；
core 的 `InputBoxTest` 只验证「插件未加载时标签按原文显示」。

> **功能开关**：`features.input-box`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
