# media-viewer（MediaWiki TimedMediaHandler + MediaViewer 移植）

音视频播放器与图片放大查看：

| 能力 | 说明 |
| --- | --- |
| 词条内嵌 | `[[文件:X.webm|thumb|300px|说明]]` 渲染原生 `<video controls>`（无 JS 仍可播放），JS 增强成 MediaWiki 风格控制条（大播放按钮 / 进度 / 音量 / 倍速 / 全屏 / 原始文件下载） |
| 文件页 | `文件:X.mp3` 等音视频文件页显示同一个播放器 |
| 图片查看 | 点击词条、图库或文件页里的图片打开查看器。支持双指缩放、拖动、全屏、分享链接、下载和键盘翻图 |

- 开关：`features.media-viewer`（**默认关闭**；`/init`、`特殊:权限配置 → 站点设置`、`LocalSettings.yaml` 都可改）。
  关闭时音视频显示「图标 + 文件名」占位框（core 的默认行为），图片点击不放大；
- 只处理 `audio` / `video`：图片与其它文件返回 `null`，交给 core 的默认渲染（`thumb` 的 `<figure>`、对齐、图注仍由 core 统一处理）；
- 资源：`/plugins/media-viewer/media-viewer.js`（播放器）、`image-viewer.js`（图片查看器）、
  `media-viewer.css`（**从 core 的 base.css 迁出**的播放器与查看器样式）。

## 扩展点

| 扩展点 | 用途 |
| --- | --- |
| `FileEmbedRenderer` | 词条嵌入与文件页预览的播放器 HTML（按 `order()` 询问，返回 `null` = 交给 core） |
| `HtmlHeadProvider`（`siteWide()`） | 注入样式、`WIKI_CONFIG.mediaViewer = true`、按钮文案与两个脚本（所有页面都要） |
| `MessageBundle` | `media.*` / `imageViewer.*` 文案（键与迁移前 core 一致，core 里已删除） |

## 与 MediaWiki 原版的差异

- 没有转码（MediaWiki 用 TimedMediaHandler 的 OGV/WebM 转码队列）：只播放浏览器原生支持的格式；
  不支持的格式由前端给出「可下载原始文件」的提示；
- 没有字幕 / 时间轴章节 / 播放统计（`mw.TMH…` 相关功能）；
- 图片查看器从当前页面收集可查看的图片。图注和文件入口在底部，作者和许可信息可通过「更多详情」进入文件页查看。
  `noviewer`、`metadata` 或自定义了非文件链接的图片会跳过。缩放下载由浏览器生成。

> **功能开关**：`features.media-viewer`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
