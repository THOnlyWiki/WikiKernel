# graph（MediaWiki Graph 扩展移植）

`<graph>` 标签与 `图表:` / `Graph:` 命名空间的**整页图**：正文是 Vega / Vega-Lite 规范（JSON），
由**本地内置**的 Vega 渲染（不走 CDN、不需要 Graphoid 服务，也不需要 headless 浏览器）。

| 入口 | 说明 |
| --- | --- |
| `<graph title="柱状图" width="600">…规范…</graph>` | 标签内容原样（`expandBody()=false`，不参与模板展开），服务端只校验 + 转义 |
| `图表:某图` 页面 | 正文本身就是规范，阅读时整页渲染成图（编辑 / 历史照旧） |

- 生成的占位：`<figure class="mw-graph">` + `.mw-graph-plot`（渲染目标）+ `<script type="application/json" class="mw-graph-spec">`（规范原文）；
- 前端脚本 `/plugins/graph/graph.js` 用 core 内置的 `/vendor/vega/vega.min.js`、`vega-lite.min.js`、`vega-embed.min.js` 渲染，
  失败时在占位里显示本地化错误（`graph.runtimeError`），不影响页面其余部分；
- JS 未启用时保留「（图形需要 JavaScript）」回退；
- 开关：`features.graph`（默认开启；`/init`、`特殊:权限配置 → 站点设置`、`LocalSettings.yaml` 的 `wikikernel.features.graph` 都可改）。
  关闭后 `<graph>` 按原文转义显示、`图表:` 页面按普通 wikitext 渲染。

## 扩展点

| 扩展点 | 用途 |
| --- | --- |
| `ParserTag`（`graph`） | `<graph>` 标签；`@@` 关闭时按原文转义 |
| `PageDisplayHook.renderBody` | 认领 `图表:` / `Graph:` 命名空间页面的正文 |
| `HtmlHeadProvider`（`siteWide()`） | 注入 Vega 与 `graph.js`（编辑预览里的 `<graph>` 也能渲染） |
| `MessageBundle` | `graph.invalid` / `graph.runtimeError` / `graph.needJavaScript` / `graph.vegaMissing` 中英文案 |

## 与 MediaWiki 原版的差异

- 不依赖 Graphoid（MediaWiki 的 Vega 渲染服务）：规范直接交给浏览器端的 Vega，服务端只做 JSON 形态校验；
- 未移植：`<graph>` 的 `map` 类地理图形（需要 topojson 数据）、Graph 命名空间的编辑工具、`#graph` 解析器函数；
- Vega / Vega-Lite / vega-embed 仍由 core 的 `/vendor/vega/` 提供（与 Math 的 KaTeX、Flash 的 Ruffle 同一约定），
  这样多个插件可以共用同一份本地库；`特殊:版本` 的「客户端库」表格仍列出它们。

> **功能开关**：`features.graph`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
