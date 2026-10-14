# Flash Player（Ruffle，插件）

Flash 播放器（对应 core 内置的 `<flash-player>` + Ruffle 方案），从 core 拆出为独立插件（`plugin/flash-player`）。

## 功能

- `<flash-player file="[[文件:X.swf]] 或 URL" … />` 生成播放器占位：
  - `file` 支持外部 URL、`[[文件:X.swf]]` 与裸文件名；
  - 参数：`width` / `height` / `autoplay` / `loop` / `fullscreen` / `letterbox` / `menu` / `bgcolor` /
    `quality` / `scale` / `wmode` / `allowscriptaccess` / `unmute` / `flashvars` / `base`，
    说明文字可用标签内容或 `caption`；
- 前端脚本 `static/plugins/flash-player/flash-player.js`（经 `/plugins/flash-player/flash-player.js` 提供）
  懒加载 **Ruffle**（`/vendor/ruffle/…`，随 core 分发；页面里没有 Flash 时不下载 wasm）；
- 功能开启时由 `HtmlHeadProvider` 注入脚本（词条阅读页）。

## 兼容性

- 功能开关沿用 **`features.flash-player`**（`特殊:权限配置 → 站点设置` 或 `LocalSettings.yaml`）：
  关闭时退化成文件链接（与迁移前一致）；
- 文件解析走 `common.service.FileStore` 桥接（等价于 core 的 `FileService.findByName/urlOf/pageUrlOf`）；
- 提示文案沿用 core 消息键（`flashPlayer.missingFile` / `flashPlayer.unavailable` / `flashPlayer.missing` /
  `flashPlayer.disabled`）；
- 播放器占位属性（`data-flash-*` / 回退链接 / `flash-player-caption`）与迁移前逐字节一致；
  `WIKI_CONFIG.flashPlayer` 开关仍由 core 注入。

## 与迁移前的差异

- core 不再内置 `<flash-player>` 解析：卸载 / 禁用本插件时标签按原文显示；
- 前端脚本 URL 从 `/js/flash-player.js` 变为 `/plugins/flash-player/flash-player.js`；
  Ruffle 运行时仍从 core 的 `/vendor/ruffle/…` 加载；
- 可视化编辑器仍把 `<flash-player>` 识别为块级标签（core 的 tokenizer 保留）。

## 测试

`FlashPlayerTagTest` 覆盖外链 / 本地文件解析、参数与回退链接、缺参数错误、非法宽度回退与开关关闭；
core 的 `FlashPlayerTest` 只验证「插件未加载时标签按原文显示」与 Ruffle 静态资源可访问。

> **功能开关**：`features.flash-player`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
