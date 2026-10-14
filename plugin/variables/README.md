# variables（MediaWiki Variables 扩展移植）

页面级变量的解析器函数 —— 对应 MediaWiki 的
[Variables](https://www.mediawiki.org/wiki/Extension:Variables) 扩展（第三方扩展）。

- 插件 id：`variables`；模块：`:plugin:variables`；入口类 `VariablesPlugin`；
- 本地化文案：`VariablesMessages` + `i18n/messages.properties` / `messages_en.properties`
  （`plugin.variables.name` / `plugin.variables.description`）；
- 构建：`.\gradlew.bat :plugin:variables:build`。

## 功能

`VariablesFunctions` 注册以下解析器函数（`common.extension.ParserFunction`）：

| 函数 | 说明 |
| --- | --- |
| `#vardefine` | 定义 / 覆盖一个页面级变量（无输出） |
| `#vardefineecho` | 定义变量并输出它的值 |
| `#var` | 读取变量的值（未定义时按空串或给定默认值） |
| `#varexists` | 判断变量是否已定义 |
| `#var_final` | 在**解析收尾**时输出变量的最终值（用 `WikiRenderContext.deferWikitext()` 延迟占位） |

变量存在 `WikiRenderContext.store()`（渲染内变量，跨模板、页面级）；`{{{参数}}}` 在传入前已按当前 frame 展开。

## 兼容性

- 描述文件里**没有**声明 `plugin.featureKey`，所以本插件**没有站点级功能开关**：加载即生效；
- 卸载 / 未加载插件时上述函数名不被识别，按普通模板调用处理。

## 与 MediaWiki 的差异

- `{{{参数}}}` 已按 frame 展开；`#var_final` 的延迟占位在解析收尾时求值 —— 语义与 MediaWiki 的测试用例一致。

## 相关文档

- [Wikitext 与内容标记](../../docs/WIKITEXT.md)（解析器函数、模板与变量）
- [插件开发与运行](../../docs/PLUGIN-DEV.md)（解析器函数扩展点、渲染上下文 `WikiRenderContext`）
