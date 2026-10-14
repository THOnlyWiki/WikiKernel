# widget（MediaWiki Widgets 扩展移植）

微件 = `微件:` / `Widget:` 命名空间下的页面，内容是 **HTML + 迷你 Smarty**；在任意页面用
`{{#widget:名称|参数=值}}` 调用，输出**原样插入**（可信块，不经过 HTML 白名单）。

```text
{{#widget:示例|id=abc|width=420}}
{{#widget:微件:示例|items=一|items=二}}   ← 重复键成列表
{{#widget:示例|a.b.c=嵌套}}               ← 点号路径 <!--{$a.b.c}-->
{{#widget:示例|flag}}                     ← 无值 = true；flag=false = 布尔假
```

Smarty 子集（与迁移前 core 一致）：`<!--{$var}-->`、修饰符 `escape:'html'|'url'|'js'`、`urlencode`、
`default:'x'`、`lower` / `upper` / `replace:'a':'b'` / `strip_tags` / `trim`（可链式）、
`<!--{if}--> / elseif / else / /if`、`<!--{foreach from=$x item=y}-->`、`<!--{* 注释 *}-->`。
与 Smarty 相同：**不自动转义**，引用参数请自己 `|escape`。

- 开关：`features.widgets`（默认开启；旧配置 `wikikernel.widgets.enabled: false` 仍兼容——core 启动时
  会把它写进站点设置 `features.widgets`）；
- 命名空间前缀可用插件配置覆盖（默认 `微件:` / `Widget:`）：

```yaml
wikikernel:
  plugins:
    settings:
      widget:
        prefixes: 微件:,Widget:
```

- 找不到微件 / 模板语法错误 / 功能关闭时显示本地化错误提示，不影响页面其余内容；
- 微件页仍是 core 的 **HTML 代码页**（只读高亮预览 + HTML 编辑器），编辑权默认只有「小部件编辑者」
  （对应 MediaWiki 的 `editwidgets`，管理员不豁免）——这部分属于 core 的命名空间与权限规则，未迁入插件。

## 扩展点

| 扩展点 | 用途 |
| --- | --- |
| `ParserFunction`（`widget`） | `{{#widget:…}}`；参数用 `call.expand(...)` 按当前 frame 展开，输出用 `call.trustedHtml(...)` 登记为可信块 |
| `WikiPageStore.visibleByTitle` | 解析微件页（按查看权限过滤，避免把无权页面内容带进正文） |
| `MessageBundle` | `widget.error.*` 文案（键与迁移前 core 一致） |

## 与 MediaWiki 原版的差异

- Smarty 只实现了常用子集：没有 `{include}` / `{literal}` / 自定义函数 / 过滤器链里的函数调用；
- 微件内容不缓存到解析器输出（按内容 key 缓存编译结果，最多 256 条），编辑后下次渲染立即生效；
- MediaWiki 的 `Widgets` 用 `Smarty` 的完整实现并支持 `{{#widget}}` 以外的 `#widget` 调用形式，这里一致；
- 安全模型相同：微件输出可以包含任意 HTML/JS，所以微件页的编辑权等同于全站脚本权。

> **功能开关**：`features.widgets`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
