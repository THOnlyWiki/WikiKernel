# Wikitext 与内容标记

页面正文的语法与渲染：Wikitext 与模板系统、微件（Widgets）、Gadgets、Lua 模块、数学公式（Math）、
时间轴（EasyTimeline）、图形（Graph），以及分类、模板反查与 `#REDIRECT` 重定向。
解析器函数 / 标签的**插件侧扩展点**见 [插件开发与运行](./PLUGIN-DEV.md)。

其它文档：[README](../README.md) ｜ [接口与兼容 API](./API.md) ｜ [配置与数据](./CONFIG.md) ｜ [功能详述](./FEATURES.md) ｜ [皮肤与前端界面](./SKIN.md) ｜ [插件开发与运行](./PLUGIN-DEV.md)

## Wikitext 渲染与模板系统

页面正文按 MediaWiki 风格渲染（`core` 的 `WikitextRenderer`）：

- **模板**：`{{abc模板|a=1|b=2}}`（命名参数）、`{{abc模板|第一个}}`（位置参数）、
  `{{{a|默认值}}}`（参数默认值）；模板就是普通页面，标题用 `模板:` 命名空间（可配置为 `Template:` 等）；
  支持 `<noinclude>` / `<includeonly>` / `<onlyinclude>`，带递归深度与循环保护；
- **解析器函数**：`#if` / `#ifeq` / `#switch`；魔术字 `{{PAGENAME}}` / `{{FULLPAGENAME}}` / `{{SUBPAGENAME}}` /
  `{{BASEPAGENAME}}` / `{{ROOTPAGENAME}}` / `{{NAMESPACE}}` / `{{NAMESPACEE}}` / `{{PAGENAMEE}}` / `{{FULLPAGENAMEE}}` /
  `{{!}}` 与时间类 `{{CURRENTYEAR}}` / `{{CURRENTMONTH}}` / `{{CURRENTMONTH1}}` / `{{CURRENTMONTHNAME}}` /
  `{{CURRENTDAY}}` / `{{CURRENTDAY2}}` / `{{CURRENTDOW}}` / `{{CURRENTTIME}}` / `{{CURRENTHOUR}}` / `{{CURRENTMINUTE}}` /
  `{{CURRENTWEEK}}` / `{{CURRENTTIMESTAMP}}`（`NAMESPACE` 不含冒号，与 MediaWiki 一致）；
  统计类 `{{NUMBEROFPAGES}}` / `{{NUMBEROFARTICLES}}` / `{{NUMBEROFEDITS}}` / `{{NUMBEROFFILES}}` /
  `{{NUMBEROFUSERS}}` / `{{NUMBEROFADMINS}}`（30 秒缓存）；`{{DISPLAYTITLE:标题}}` 改写页面显示标题（从正文剥离）；
- **块级**：`== 标题 ==`、`----`、`*` / `#` 列表、`{| 表格 |}`、`<pre>`、`<nowiki>`、
  `<syntaxhighlight lang="java">` / `<source lang="java">`（**服务端语法高亮**：java / kotlin / js / ts / css / json / xml / python / bash / sql / properties / lua，见下）、
  `<poem>`（Poem 扩展：保留换行、内部支持行内 wikitext）、`<inputbox>`（InputBox 扩展：创建 / 搜索表单，见下）；
- **目录（TOC）**（仿 MediaWiki）：`== X ==` / `=== X ===` / `==== X ====` 生成带锚点的标题；
  **标题 ≥4 个**时自动在第一个标题前插入**竖排编号目录**（1 / 1.1 / 1.1.1…，带 `[隐藏] / [显示]` 折叠）；
  `__TOC__` / `__FORCETOC__` 强制显示、`__NOTOC__` 关闭、`__NOEDITSECTION__` 隐藏段落编辑链接（魔术字会从正文剥离）；
  重复标题锚点自动加 `_2` / `_3`，中文标题可直接作为 `#锚点`；
- **脚注 / 引用**（仿 MediaWiki Cite 扩展）：
  - `<ref>来源</ref>` 在正文显示为上标 `[1]`，`<references/>`（也支持 `<reference/>` 单数写法与
    `<references>…</references>`）处生成脚注列表；列表只在 `<references/>` 的位置出现；
  - `<ref name="x">来源</ref>` 具名，之后 `<ref name="x"/>` 复用同一编号，支持**先使用后定义**，列表带 `↑` 回链；
  - `<ref/>` 无名称复用、名称未定义 / 重复定义（内容不同）/ 空引用都会显示红色本地化的「引用错误」；
  - 脚注内容照常解析行内语法（链接、粗斜体、模板已展开）；`<nowiki>` 与 `<pre>` 里的 `<ref>` 不解析；
  - 标题里的脚注不会污染锚点与目录文本；
- **评论**（仿 MediaWiki Comments 扩展）：正文里写 `<comment />`（也支持 `<comments />`）时，
  页面底部显示评论区（支持回复、wikitext、删除），标签本身从正文剥离；不写标签的页面没有评论区，详见下文「评论、私信与用户头像」；
- **行内**：`'''粗体'''`、`''斜体''`、`[[页面|文字]]`、`[https://example.com 外链]`；
  **未知页面红链**（仿 MediaWiki）：`[[目标]]` 的目标页面不存在（或已删除 / 已隐藏）时输出
  `<a class="new">`（红色，`a.new { color: #ba0000 }`）；`特殊:` / `Special:` 链接与页内锚点 `[[#章节]]` 不标红；
  正文、模板展开、行内预览、`action=parse`、Lua / 微件输出等所有走渲染器的路径统一生效；
- **内嵌 HTML**：经白名单过滤（含 `<br>`、`<hr>`、`<iframe>`）；事件属性与 `javascript:` 被拒绝，未知标签按纯文本转义；
- 渲染完成后依次执行插件的 `PageRenderHook` 扩展点。

```yaml
wikikernel:
  wikitext:
    template-prefixes: [ "模板:", "Template:" ]
    max-template-depth: 20
```

说明：目前实现的是常用子集，不追求 100% MediaWiki 兼容（表格属性、复杂解析器函数、Lua 模块等尚未实现；分类与重定向见下文）。

## 微件（Widgets，插件 `widget`）

仿 MediaWiki [Widgets 扩展](https://www.mediawiki.org/wiki/Extension:Widgets)：**微件**是「微件:」命名空间下的页面，
内容是 HTML + 迷你 Smarty 模板；在任意页面用 `{{#widget:名称|参数=值}}` 调用，输出**原样插入**（不经过 HTML 白名单）。
**渲染与 `#widget` 解析器函数已迁到插件 `widget`**（详见 `plugin/widget/README.md`）。

- **调用**：`{{#widget:示例|id=abc|width=420}}`（名称可写完整前缀 `微件:示例`，也可省略，依次尝试 `微件:` / `Widget:`）；
  参数支持 `a.b.c` 点号嵌套（`<!--{$a.b.c}-->`）、重复键成列表（`<!--{$items[0]}-->`）、`false` 转布尔；
- **模板语法（子集）**：
  - 变量：`<!--{$id}-->`；修饰符 `escape:'html' | 'url' | 'js'`、`urlencode`、`default:'420'`、
    `lower` / `upper` / `replace:'a':'b'` / `strip_tags` / `trim`，可链式叠加；
  - 条件：`<!--{if $x}-->…<!--{elseif $y}-->…<!--{else}-->…<!--{/if}-->`（支持 `$x`、`!$x`、`==`、`!=`、`&&`、`||`）；
  - 循环：`<!--{foreach from=$items item=x}-->…<!--{/foreach}-->`；注释：`<!--{* … *}-->`；
  - 与 Smarty 一致**不自动转义**，引用参数请自己加 `|escape`；
- **查看 / 编辑**：微件页按 HTML 代码页对待（只读高亮预览 + HTML 编辑器，带标签 / 属性补全），保存即生效；
- **权限**：微件页默认只有**小部件编辑者**能编辑 / 创建 / 移动（对应 MediaWiki 的 `editwidgets`；
  **管理员不豁免**），普通用户 / 管理员看到「查看源代码」；`特殊:用户权限` 可授予 / 取消「小部件编辑者」，
  想调整默认规则就在 `特殊:权限配置` 加一条 `微件:*` 规则（例如把编辑权给管理员或所有人）；
- **开关**：默认启用；在 `/init` 核心 tab 勾选「启用微件（Widget）」（写入 `wikikernel.features.widgets`），
  也可在 `特殊:权限配置 → 站点设置` 随时开关——关闭后所有页面的 `{{#widget:…}}` 都不生效
  （显示「未启用」提示），微件页本身仍保留；旧配置 `wikikernel.widgets.enabled` 仍兼容
  （微件已拆成插件，core 启动时把它写进站点设置 `features.widgets`）；
- **安全模型**：微件输出可以包含任意 HTML/JS（否则 iframe 类微件无法工作），所以微件页的编辑权等同于全站脚本权，
  默认只给「小部件编辑者」——与 MediaWiki Widgets 的 `editwidgets` 相同；
- 找不到微件或模板语法错误时显示本地化错误提示，不影响页面其余内容；
- **插件未加载时**：`{{#widget:…}}` 按原文显示（未知解析器函数），微件页仍按 HTML 代码页渲染。

```yaml
wikikernel:
  widgets:
    # 启用开关见上面的 features.widgets（这里保留旧键做兼容默认值）
    enabled: true
  plugins:
    settings:
      widget:
        prefixes: 微件:,Widget:     # 命名空间前缀（插件配置；默认 微件: / Widget:）
```

## Gadgets（插件 `gadgets`）

Gadgets 与 Widgets 是两套功能：Gadgets 是可由用户按个人偏好启用的 JavaScript / CSS 小功能，定义页为
`WikiKernel:Gadgets-definition`，资源页使用 `小部件:` / `Gadget:` 命名空间。资源页是纯代码页
（CodeMirror 编辑器 / 原样输出），适合写复杂脚本与样式。
**定义解析、按用户加载、`特殊:Gadgets` 与参数设置分区已迁到插件 `gadgets`**（详见 `plugin/gadgets/README.md`）。

定义格式（每行一个 Gadget，`<pre>` 里的示例只是文档、不会被解析）：

```text
* QuickEdit | 说明=快速编辑工具 | QuickEdit.js | QuickEdit.css | default
* DarkMode | 深色模式 | DarkMode.js | default
* AdminTool | 仅在组内可见 | AdminTool.js | rights=管理员 | default
* Internal | 隐藏：不出现在个人设置里 | Internal.js | hidden
```

- **功能开关**：`/init` 或 `特殊:权限配置 → 站点设置` 的「启用 Gadgets」；配置键为
  `wikikernel.features.gadgets`，默认开启；关闭后不注入 Gadget JS/CSS、不显示个人 Gadget 设置，
  但**不会覆盖**用户已保存的 Gadget 偏好（重新开启即恢复）。
- **管理员组**：「小部件管理员」可以编辑定义页与 `Gadget:` / `小部件:` 资源页；普通「小部件编辑者」
  只负责 Widgets，不自动获得 Gadget 管理权。
- **加载规则**：默认开启的 Gadget 对匿名也生效；没有 `default` 的需登录用户在
  **参数设置 → 小工具**里勾选；`rights=组1,组2` 表示只对组内用户展示 / 加载
  （组名用站点用户组名，如 `管理员`）；资源按 `Gadget:` → `小部件:` 顺序解析，**资源页不存在则跳过**（不注入 404）。
- **个人偏好**：存于用户字段 `gadgets`（逗号分隔名称；`!` = 显式全部关闭，空 = 用定义里的 `default`）；
  插件通过 `common.service.UserSettings` 读写，保存接口是 `POST /api/gadgets/preferences`。
- **管理入口**：`特殊:Gadgets` / `特殊:小部件管理` 查看定义、资源和启用状态；定义页不存在时**不再自动创建**，
  页面上给「创建定义页」入口（插件不偷偷改站内内容）。

## Lua 模块（{{#invoke:}}，Scribunto MVP 子集）

仿 MediaWiki Scribunto 的核心用法：**模块是「模块:」命名空间页面的 Lua 代码**，在任意页面用
`{{#invoke:模块名|函数名|参数…}}` 调用；返回的文本会**继续按 Wikitext 解析**（模板、魔术字、内链都生效）。

模块写法（和 Scribunto 一致）：

```lua
local p = {}

function p.问候(frame)
    return "你好，" .. frame.args[1] .. "！"
end

return p
```

- **入口**：`{{#invoke:问候|问候|世界}}`（模块名可用「模块:」/「Module:」前缀，也可省略）；
  参数里的 `{{{模板参数}}}` 会先按模板参数展开，之后才交给模块；
- **参数**：位置参数**保留首尾空白**、命名参数去首尾空白（与 Scribunto 相同）；
  `frame.args`（位置 1..n + 命名键）、`frame:getParent()`（调用方模板的参数，直接调用时为 nil）、
  `frame:getTitle()`；
- **mw 库**：
  - `mw.text`：`plain`（去 HTML 标签并解码实体）、`trim`、`upper`、`lower`、`len`、`sub`、`format`（同 `string.format`；已修正 LuaJ 忽略 `%.2f` 精度与浮点宽度的问题）；
  - `mw.title`：`new(name)` / `getCurrentTitle()`，返回含 `fullText` / `text` / `nsText` / `namespace` / `fragment` 的对象；
  - `mw.uri`：`encode`（空格 → `%20`）/ `decode`（只解 `%XX`，不把 `+` 当空格）；
  - `mw.html`：Scribunto 的 HTML 构建器（`create / addClass / attr / css / cssText / node / wikitext /
    newline / tag / done / allDone`，`tostring(builder)` 输出 HTML；空元素输出 `<br />` 形式）；
  - `mw.loadData`：读取**数据模块**（只允许字符串 / 数字 / 布尔 / 子表，禁止函数与循环引用），
    按模块修订号缓存；
  - `mw.site`：`siteName` / `currentVersion` / `server` / `scriptPath` / `articlePath`、
    `stats`（pages / articles / edits / files / images / users / admins，30 秒缓存）、
    `namespaces`（id → `{ id, name, canonicalName }`）；
  - `mw.message`：`new(key, ...)`（按本站消息键取值，参数用 `{0}` 占位）/ `newRawMessage(text, ...)`
    （原始消息，`$1` 占位）；消息对象有 `params / text / plain / exists / isBlank`；
  - `mw.getCurrentFrame`、`mw.log`（占位，不产生噪声）；
- **Lua 5.1 兼容层**：`unpack` / `table.getn` / `table.maxn`（Scribunto 用 5.1；不提供
  `setfenv` / `getfenv` / `loadstring`，与沙盒策略一致）；
- **沙盒与安全**：没有 `os` / `io` / `package` / `require` / `dofile` / `loadfile` / `load*` /
  `print` / `debug` / `luajava`；单次调用有**指令数上限**，死循环（哪怕包在 `pcall` 里）
  也会以「运行超时」错误终止，不会卡住页面；
- **缓存与模块依赖追踪**：编译结果按模块修订号缓存（上限 64 个模块），编辑模块后下次渲染立即生效；
  `mw.loadData` 加载的数据模块会记入**依赖**（含链式：数据模块再 loadData 的数据也会被追踪），
  任一依赖的修订号变化 → 自动重新加载，不会读到过期数据；
- **编辑**：模块页按**代码页**处理（CodeMirror Lua 高亮 + 只读预览、无可视化编辑面板），保存即生效；
- **错误**：找不到模块 / 没有该函数 / 返回值不是字符串或数字 / 数据模块不合法 / 运行出错 →
  显示本地化红字错误，页面其余内容照常渲染；语法错误在渲染时会给出模块名与行号
  （例如 `模块:演示Lua:4: '<name>' expected`）；
- **注意**：Lua 标识符只支持 ASCII（与标准 Lua / Scribunto 一致），函数名不要用中文，例如
  `function p.add(frame)`；**中文键名**要写成字符串形式：`data['数值']`（不能写 `data.数值`）。
- **示例词条（可直接打开对照）**：**`Lua扩展演示页`** —— `模块:演示/水果卡`（mw.html 构建表格 +
  mw.loadData 读数据 + mw.site 取站点信息 + mw.message 参数替换 + Lua 5.1 的 unpack / table.getn）
  + `模板:Lua扩展卡片`（模板包装）+ `模块:演示/站内消息`；
  编辑 `模块:演示/水果数据` 后刷新该页可以看到依赖追踪即时生效。
- **测试**：`LuaModuleTest` / `LuaExtensionTest`（mw.html / loadData 依赖 / mw.site / mw.message /
  5.1 shim / 复杂组合页面）；解析器类功能的测试都按「复杂组合页面」规格写。

## 条目重定向（#REDIRECT）

- **语法**（仿 MediaWiki）：页面正文以 `#REDIRECT [[目标]]` 或 `#重定向 [[目标]]` 开头即视为重定向页；
  大小写不敏感、冒号可省略，支持 `[[目标|显示文字]]`（`WikiRedirect` 解析）；
- **跳转**：访问重定向页会 302 到目标（URL 带 `?redirectfrom=来源`，目标页标题下显示「重定向自 X」，
  点击可带 `?redirect=no` 查看重定向页本身，便于编辑）；
- **链与循环**：服务端解析多级重定向（最多 5 跳，一次跳到底）；检测到循环或链过长时按普通页面渲染，不会死循环；
  目标不存在时照常跳到目标（落地为自定义 404 页）；
- **移动页面**：勾选「留下重定向」后旧标题自动生成 `#REDIRECT`（见上一节），因此移动后旧链接依然可用。

## 分类（分类:X）与模板反查

### 分类

- **语法**（仿 MediaWiki）：正文任意位置写 `[[分类:X]]` 或 `[[分类:X|排序键]]`（`Category:X` 别名等价）；
  分类链接**不再内联显示**，页面底部显示分类栏（`分类 甲 乙 …`，说明页不存在时为红链）；
  分类栏位于**内容容器最底部**（内容框纵向 flex + 撑开块：短页面贴底、长页面与正文保持 1em 间距）；
  文件页的分类栏在「文件历史」之后，与 MediaWiki 一致；
  `[[:分类:X]]`（前导冒号）只是普通链接，不归类；
- **分类页** `分类:X`（`Category:X` 会 302 到规范名）：
  - 顶部是可选**分类说明**（`分类:X` 页面本身的正文，可直接编辑；不存在时提示创建）；
  - 「子分类」列出归入本分类的分类页；「页面」列出成员，按 **排序键 → 标题** 排序并显示排序键；
- **模板里的分类也生效**：页面保存时按“模板展开后的渲染结果”重建索引
  （表 `wiki_page_category`，类 MediaWiki 的 categorylinks）；模板页修改后会自动重建使用它的页面；
  服务启动时全量重建一次（旧数据、模板改动都能对齐）；
- `特殊:分类列表` / `Special:Categories`：全部分类与成员数。

### 模板反查（哪些页面用了什么模板 / 模板被谁用）

- **正查**：`特殊:页面信息?page=某页`（或页面上的 `?action=info`）显示「使用的模板（N）：模板:A、模板:B…」与「所属分类」；
- **反查**：`特殊:链入页面?page=模板:X`（模板页工具的「链入页面」）把链入与嵌入合并成一个列表，
  使用模板的页面带「**（嵌入）**」标记——`{{X}}`、`{{模板:X}}`、`{{Template:X}}` 都能匹配；
- **模板使用索引**（表 `wiki_page_template`，类 MediaWiki 的 templatelinks）：
  - **实时维护**：页面保存 / 删除时通过 `WikiPageChangedEvent` → `PageTemplateEventListener` 同步重建该页的索引行；
  - **启动回填**：服务启动时全量重建一次（`@Order(5)`，上限 2000 页，覆盖旧数据 / 索引表刚创建的情况）；
  - **查询时仍做实时扫描**：`PageTemplateService.pagesUsing` 先用索引找候选页面，
    再核对每个候选页面的**最新可见版本正文**——索引里的过期行不会出现在结果里；
  - 模板调用是**源码级直接使用**（不做模板展开，模板套模板不会把外层页算进来）；
  - `PageLinkService.pagesTranscluding` 已改为走该索引（`特殊:链入页面`、模板改动后重建依赖分类都用它）；
- 实现：`WikiLinkScanner` 解析 `{{...}}`（跳过 `{{{参数}}}`、`#解析器函数` 与魔术字）并规范化标题；
  反查键 `templateIndexKeys` 会把查询标题展开成 `模板:X / X / Template:X` 等候选，覆盖各种写法。

## 数学公式（Math）

用 `<math>…</math>` 在词条里写 TeX 公式（仿 MediaWiki 的 Math 扩展），化学式用 `<chem>…</chem>`（mhchem 语法）。
渲染走**前端 KaTeX 0.19**（本地内置在 `/vendor/katex/`：katex.min.js/css + mhchem 扩展 + 20 个 woff2 字体，
共约 560KB）——**懒加载**：页面里没有公式时不下载任何 KaTeX 资源，有公式才加载。

- **写法**：

  ```text
  行内：<math>E = mc^2</math>
  块级：<math display="block">\int_0^1 x^2 \, dx = \frac{1}{3}</math>
  化学：<chem>2H_2 + O_2 -> 2H_2O</chem>
  ```
- 标签内容**原样当 TeX**（不解析 wikitext，与 MediaWiki 一致）：`\frac`、`\sum`、`\begin{cases}` 等 KaTeX 支持的语法都能用；
  XML 特殊字符（`<`、`&`）自动转义；空内容显示本地化错误；
- **标题 / 正文里只是「提到」标签名时必须用 `<nowiki>` 包住**（如 `== 化学式（<nowiki><chem></nowiki>） ==`）：
  裸写 `<chem>` 会被当成真标签，一直吞到下一个 `</chem>`（与 MediaWiki 一致，演示页的标题就是这么处理的）；
- `display="block"` 生成独立居中的块级公式（`display="inline"` 或缺省为行内）；属性名大小写不敏感；
- **渲染结果**是 KaTeX 的 HTML + **MathML**（`output: 'htmlAndMathml'`，无障碍与复制友好）；
  公式写错时 `throwOnError:false` 就地显示红色错误（同 MediaWiki 的错误提示风格）；
- 脚本未加载 / 加载失败时显示 TeX 源码回退（`.mw-math-fallback`，等宽字体）；
- **开关**：`features.math`（**默认开启**），可在 `/init` 安装向导或 `特殊:权限配置 → 站点设置` 关闭；
  关闭后标签按原文转义显示、也不再加载前端脚本；
- 动态内容（评论等异步插入的公式）由 `mw.hook('wikipage.content')` 自动渲染；
- 回归测试 `MathTest` / `WikitextRendererTest`；演示页 **`数学公式演示页`**（行内、块级、化学式与出错误示例）。

> 备注：`<math>` / `<chem>` 是**扩展标签**，与 MediaWiki 一样按「最近的闭合标签」配对：
> 在标题或正文里讨论标签本身时，请写成 `<nowiki><chem></nowiki>`（否则会吞掉后面的内容）。

## 时间轴（EasyTimeline，`<timeline>`）

用 `<timeline>…</timeline>` 写 EasyTimeline 语法（仿 MediaWiki 的 EasyTimeline 扩展），
**服务端直接生成内联 SVG**（纯 Java 实现，不需要 Ploticus / Perl，也不需要外部渲染服务与 CDN）。

- **复杂组合示例**：

  ```text
  <timeline>
  ImageSize = width:640 height:240 barincrement:26
  PlotArea = left:80 right:120 top:20 bottom:50
  AlignBars = late
  Period = from:1990 till:2015
  TimeAxis = orientation:horizontal
  ScaleMajor = unit:year increment:5 start:1990 gridcolor:gray
  ScaleMinor = unit:year increment:1 start:1990
  Colors =
    id:china value:red legend:中国
    id:india value:rgb(0,0,1) legend:印度
  BackgroundColors = canvas:white bars:gray(0.92)
  PlotData =
    width:18 align:center fontsize:M
    bar:中国 from:1990 till:2010 text:"中国" color:china
    bar:印度 from:1992 till:$ text:"印度" color:india
    barset:亚洲
    bar:日本 from:1995 till:2005 text:"日本" color:india
    at:2010 text:"标记" color:china
  TextData =
    pos:(80,20) text:"国内生产总值" fontsize:L
  </timeline>
  ```
- **支持的命令**：`ImageSize`（`width` / `height` / `height:auto` / `barincrement`）、`PlotArea`、
  `AlignBars`（`justify` / `early` / `late`，`late` 贴底堆叠）、`DateFormat`（`yyyy` / `mm/dd/yyyy` / `dd/mm/yyyy`）、
  `Period`、`TimeAxis`、`ScaleMajor` / `ScaleMinor`（`unit` / `increment` / `start` / `gridcolor`）、
  `Colors`（`id:… value:… legend:…`）、`BackgroundColors`（`canvas` / `bars`）、
  `PlotData`（`bar:` / `barset:` / `from` / `till` / `at` / `text` / `color` / `shift` / `width` / `fontsize` / `align` / `textcolor`）、
  `TextData`（`pos:(x,y)` 绝对像素坐标 + `text` / `color` / `fontsize`）、`Legend`（布局 + `id:… text:…` 条目）；
- **时间值**可以是年份（`2000`）、按 `DateFormat` 的日期（`01/01/2000`），端点用 `$`
  （`from:$` = 周期起点，`till:$` = 周期终点）；`Colors` / `BackgroundColors` 里的
  `gray(0.9)` 与 0..1 分量的 `rgb(r,g,b)` 会自动换算成浏览器通用的十六进制色；
- **未实现**（按 MediaWiki 文档说明忽略，不会报错）：`LineData` 折线、纵轴刻度，
  以及 `Legend` 的精细定位（图例统一画在画布右上角）、`mark:` 自定义标记、`BackgroundColors` 渐变；
- **安全**：所有文字都做 HTML 转义、颜色走白名单校验，输出只包含 `<svg>` / `<rect>` / `<line>` / `<text>` / `<circle>`，**不含脚本**；
  定义无法解析（如 `Period` 颠倒）时显示本地化错误提示，不影响页面其它部分；
- **开关**：`features.timeline`（**默认开启**），可在 `/init` 或 `特殊:权限配置 → 站点设置` 关闭；
  关闭后按原文转义显示；
- 回归测试 `TimelineRendererTest` / `GraphTimelineTest`；演示页 **`时间轴演示页`**。

## 图形（Graph / Vega，插件 `graph`：`<graph>` 与「图表:」命名空间）

用 `<graph>…</graph>` 在词条里嵌 Vega / Vega-Lite 图形（仿 MediaWiki 的 Graph 扩展），
以及**整页图**：把页面建在 `图表:`（`Graph:`）命名空间，正文直接写 Vega 规范，整页渲染成一张图。
**标签解析与整页图已迁到插件 `graph`**（详见 [`plugin/graph/README.md`](../plugin/graph/README.md)）。

- **绘图库本地内置**（`/vendor/vega/`，不走 CDN）：`vega` 5.33.1 + `vega-lite` 5.23.0 + `vega-embed` 6.29.0
  （共约 800KB；仍由 core 提供，插件按功能开关整页统一加载），规范放在 `application/json` 脚本里，
  由插件脚本 `/plugins/graph/graph.js` 在 DOM 就绪后渲染；
- **写法**（Vega-Lite 与 Vega 都支持，`vega-embed` 按 `$schema` 自动识别）：

  ```text
  <graph title="示例柱状图" width="600" height="400">
  {
    "$schema": "https://vega.github.io/schema/vega-lite/v5.json",
    "data": {"values": [{"x": "A", "y": 3}, {"x": "B", "y": 5}]},
    "mark": "bar",
    "encoding": {"x": {"field": "x", "type": "nominal"}, "y": {"field": "y", "type": "quantitative"}}
  }
  </graph>
  ```
- 支持的属性：`title`（图注 `figcaption`）、`width` / `height`（占位尺寸提示，数字像素）；
  标签内容为空或不像 JSON 时显示本地化错误；规范由服务端做转义（`&` / `<` / `>` → `\uXXXX`），**不能逃出 `<script>` 标签**；
- **渲染失败**（规范非法、`vega-embed` 未加载等）时在占位里显示 `graph：渲染失败：<原因>`（消息键 `graph.runtimeError`），
  不影响页面其余部分；脚本 / JS 未启用时保留「（图形需要 JavaScript）」回退；
- **整页图**：新建 `图表:某图` 页面，正文就是 Vega 规范（首字符需为 `{` 或 `[`），查看时整页渲染成图，
  编辑 / 历史页签照旧；图表命名空间序号 484 / 485，与 MediaWiki 的 Graph 命名空间对齐（`wgNamespaceIds` 里可查到 `图表=484`、`Graph=484`）；
- **开关**：`features.graph`（**默认开启**），可在 `/init` 或 `特殊:权限配置 → 站点设置` 关闭；
  关闭后 `<graph>` 按原文转义显示、`图表:` 页面按普通页面渲染（不再加载 Vega 脚本）；
- `特殊:版本` 的客户端库表格里也列出了 Vega / Vega-Lite / vega-embed 的版本与来源；
- 回归测试：插件侧 `plugin/graph/src/test`（标签渲染 / 属性 / 无效规范 / 命名空间整页图 / head 注入），
  core 侧 `GraphTimelineTest`（插件未加载时按原文转义、Vega 静态资源可访问）；
  演示页 **`图形演示页`** 与 **`图表:Vega演示图`**。
