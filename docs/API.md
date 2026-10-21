# 接口与兼容 API

WikiKernel 对外的读写接口：MediaWiki 风格的 `api.php`、`mw` / `mw.loader` 前端兼容层、跨 Wiki（Interwiki）链接表、
页面导出 / 导入，以及链接悬停预览（Popups / Page Previews）。示例中的地址默认站点跑在 `http://localhost:18080`。

其它文档：[README](../README.md) ｜ [配置与数据](./CONFIG.md) ｜ [功能详述](./FEATURES.md) ｜ [皮肤与前端界面](./SKIN.md) ｜ [Wikitext 与内容标记](./WIKITEXT.md) ｜ [插件开发与运行](./PLUGIN-DEV.md)

## 跨 Wiki（Interwiki）

仿 MediaWiki 的 [Interwiki](https://www.mediawiki.org/wiki/Manual:Interwiki) 功能：用**前缀**把
`[[前缀:页面]]` 指向另一个 wiki，或在多语言站点之间建立**跨语言链接**。

- **三种前缀语义**（在 `特殊:跨Wiki` 里维护，表 `wiki_interwiki`）：

  | 前缀类型 | 写法 | 效果 |
  | --- | --- | --- |
  | 普通前缀 | `[[w:示例页面]]`、`[[commons:File:X.svg]]` | 渲染成外部链接（`class="external mw-interwiki"`，新窗口打开），显示名默认是「前缀:页面」，可用 `[[w:页面\|显示名]]` 覆盖 |
  | 语言前缀（填了语言码） | `[[en:Interwiki demo]]` | **跨语言链接**：从正文剥离，收集到侧栏「语言」区块（显示语言名，如「英语」）；同一语言只保留第一次出现 |
  | 本地前缀（勾选「本地前缀」） | `[[专题:某某]]` | 生成站内链接 `/wiki/专题:某某`（照常判断红链） |

- **前导冒号**（仿 MediaWiki 的「强制链接」）：`[[:en:Page]]` 不当作跨语言链接，
  `[[:分类:X]]` 只显示链接而不归类；`[[:w:Page]]` 同样渲染成跨 Wiki 外链；
- **URL 模板**：`$1` 会替换成页面名，编码规则**对齐 MediaWiki 的 `wfUrlencode`**
  （空格 → `_`，保留 `: / ' , $ @`），所以 `[[commons:File:Example.svg]]` 得到
  `https://commons.wikimedia.org/wiki/File:Example.svg`；模板里没有 `$1` 时页面名追加到末尾；
- **本地命名空间优先**：`[[帮助:X]]`、`[[分类:X]]` 这类真实命名空间仍按站内处理，
  跨 Wiki 前缀只接管不冲突的前缀（所以前缀别取成已有命名空间名）；
- **默认前缀表**（`InterwikiSeeder`，只在**表为空**时写入，之后不会被启动流程覆盖）：
  `wikipedia` / `w`、`wiktionary` / `wikt`、`wikibooks`、`wikinews`、`wikiquote`、`wikisource`、
  `wikiversity`、`wikispecies`、`commons`、`meta`、`mw`、`wikidata` / `d`，
  以及语言前缀 `zh` / `en` / `ja` / `ko` / `de` / `fr` / `es` / `ru` / `it` / `pt` / `ar` / `vi`
  （默认指向对应语言的维基百科——**自建多语言 wiki 时请改成自己的地址**）；
- **`特殊:跨Wiki`**（`Special:Interwiki`）：前缀 / URL 模板 / 语言 / 标志（本地、可嵌入）列表，
  可新增 / 编辑（`?edit=编号`）/ 删除，并支持**文本导出 / 导入**
  （每行：`前缀 URL [本地 0/1] [可嵌入 0/1] [语言码]`，`#` 开头是注释）；
  表格所有人都能看，增删改需要**管理员 / 行政员**（API：`POST /api/interwiki/save|delete|import`）；
- **未实现**：`可嵌入（iw_trans）`只记录标志，尚不支持 `{{前缀:页面}}` 的**远端模板嵌入**
  （MediaWiki 需要远端 wiki 的 API，本地实现会引入外网依赖与缓存问题）；
- 演示页 **`跨Wiki演示页`**；回归测试 `InterwikiTest`。

```text
[[w:示例页面]]              →  https://zh.wikipedia.org/wiki/示例页面
[[wikt:word|词典]]          →  https://zh.wiktionary.org/wiki/word（显示「词典」）
[[commons:File:X.svg]]      →  https://commons.wikimedia.org/wiki/File:X.svg
[[en:Interwiki demo]]       →  正文不显示；侧栏「语言」出现「英语 → en 版页面」
[[:en:Interwiki demo]]      →  正文里显示成链接（强制链接）
[[专题:某某]]                →  /wiki/专题:某某（本地前缀，站内链接）
```

## 页面导出与导入（仿 MediaWiki Special:Export / Special:Import）

- **特殊:导出页面**（`Special:Export`，所有人可用）：输入页面标题（每行一个），也可以先在下拉里选命名空间点「添加」
  批量带出该命名空间下**你有权查看**的全部页面；勾选「完整历史」「包含所有引用的模板」（模板递归带出）。三种格式：
  - **MediaWiki XML**（`format=mediawiki`，默认）：`export-0.11` 兼容（siteinfo / page / revision / UTC 时间 /
    贡献者 / 正文 sha1（base36）与字节数），可直接被 MediaWiki 的 `Special:Import` 导入；
  - **WikiKernel XML**（`wikikernel-xml`）：额外带保护 / 隐藏 / 删除状态、机器人标记、标签、原站用户 ID、
    带时区偏移的时间戳（换服务器时区也能还原）；
  - **WikiKernel JSON**（`wikikernel-json`）：字段与 WikiKernel XML 一致，缩进美化，适合程序化处理；
  表单提交到 `POST /api/export`（带 `download=1` 直接下载附件）；脚本可用 `GET /api/export?...`（默认内联展示）。
  导出按访问规则过滤：**无权查看的页面不出现、不泄露标题**；限额 `wikikernel.transfer.export-max-pages`（默认 200）、
  `export-max-revisions`（默认 5000），超出截断（WikiKernel 格式带 `truncated="true"`）。
- **特殊:导入页面**（`Special:Import`，只有管理员 / 行政员）：上传 `.xml` / `.json`，**自动识别**三种格式；
  可选择「导入到命名空间」（默认保持原命名空间，也可全部导入主命名空间或指定命名空间）。
  合并规则：页面不存在则新建（WikiKernel 格式保留保护 / 隐藏 / 删除状态与机器人标记 / 标签）；
  已存在则追加缺失版本，**不覆盖本地较新的版本**；版本去重键 = **正文 sha1 + 时间（精确到秒）**，
  所以**重复导入同一文件不会产生重复版本**。版本时间沿用文件里的时间，贡献者按用户名关联本地账号
  （找不到则该版本匿名）；逐页写 **页面导入** 日志（`特殊:日志` 与 `特殊:最近更改` 可见）。
  限额：`wikikernel.transfer.import-max-pages`（默认 2000）、`import-max-revisions`（默认 20000）、
  `import-max-bytes`（默认 10MB；Spring `multipart.max-file-size` 之上再校验一次）；
  导入页面表单由 `static/js/import.js` 提交到 `POST /api/import`，结果提示自动淡出。
- 两个页面都归入 `特殊:特殊页面` 的「数据与工具」分组；XML 解析关闭 DTD / 外部实体（防 XXE）。

```yaml
wikikernel:
  transfer:
    export-max-pages: 200        # 单次导出最多页面数（含模板带出）
    export-max-revisions: 5000   # 单次导出最多版本数
    import-max-pages: 2000       # 单次导入最多页面数
    import-max-revisions: 20000  # 单次导入最多版本数
    import-max-bytes: 10485760   # 导入文件大小上限（字节）
```

## MediaWiki 兼容 API（`/api.php`）

除了自己的 REST API（`/api/*`，见各功能章节），本项目还提供 **MediaWiki Action API 兼容层**：
`GET|POST /api.php?action=...`，让 pywikibot 风格的机器人脚本基本可以直接对接。

- **通用参数**：`format=json`（默认）/`jsonfm`（HTML 预览）/`xml`（简化 XML）、`formatversion=1|2`、
  `callback=name`（JSONP）、`curtimestamp=1`；错误统一为 MediaWiki 风格
  `{"error":{"code":"...","info":"..."}}`（HTTP 200，与 MediaWiki 一致）。
- **认证（token 流程，与 MediaWiki 相同）**：
  1. `action=query&meta=tokens&type=login` 取 `logintoken`；
  2. `action=login&lgname=…&lgpassword=…&lgtoken=…`（缺 token 返回 `NeedToken`，token 错返回 `WrongToken`，
     密码错返回 `{"login":{"result":"Failed","reason":"wrongpassword"}}`）；失败走既有登录限流；
  3. `action=query&meta=tokens&type=csrf` 取 `csrftoken`，写操作都带 `token=`；
  4. `action=logout` 退出。
- **支持的动作**：

| action | 说明 |
| --- | --- |
| `query` | `meta=siteinfo\|userinfo\|tokens`；`prop=info\|revisions\|categories\|imageinfo`；`list=allpages\|search\|recentchanges\|users\|usercontribs\|categorymembers\|allcategories\|logevents\|allimages`；`titles` / `pageids` / `revids`、`indexpageids`、**`redirects=1`（跟随 #REDIRECT，返回 `query.redirects`）** |
| `parse` | `page=` 或 `text=`，`prop=wikitext\|text\|displaytitle\|categories` |
| `edit` | `title` + `text`（或 `appendtext`）、`summary`、`bot=1`（机器人组）、`baserevid`（冲突检测）、`nocreate` / `createonly`、**`section=N` 段落编辑 / `section=new` 追加段落（`sectiontitle=`）**；返回 `newrevid` / `newtimestamp`，内容没变返回 `nochange` |
| `move` | `from` + `to`、`reason`、`noredirect=1`（默认留下重定向）；返回 `move.to` / `redirectcreated` |
| `delete` | `title`、`reason`（软删除，可在回收站恢复） |
| `protect` | `title`、`protections=edit=sysop\|edit=all`（或 `protect=1\|0`） |
| `block` / `unblock` | `block`：`user`、`expiry`（infinite/1h/1d/3d/1w/2w/1m/3m/6m/1y）、`reason`、`noemail` / `nocreate` / `anononly`；`unblock`：`user` |
| `upload` | multipart（字段 `file`，可选 `filename` / `comment` / `text` 作为说明页正文）；返回 `upload.filename` / `size` / `mime` / `url` |
| `compare` | `fromrev`/`torev` 或 `fromtitle`/`totitle`；返回 `compare.body`（差异表格 HTML） |
| `rollback` | `title`（+ 可选 `user` / `summary`）：回退目标用户对本页的连续编辑（管理员 / 行政员） |
| `login` / `logout` | 见上面的 token 流程 |
| `tokens` | 旧式 `action=tokens&type=csrf\|login`（等价于 `meta=tokens`） |
| `opensearch` | 搜索建议（返回 `[关键词, [标题], [描述], [URL]]`） |
| `help` | 列出支持的动作与格式 |

- **常用兼容点**：`formatversion=2` 的数组/布尔形状、v1 内容键 `"*"` 与 v2 的 `content`、
  续传参数（`rvcontinue` / `apcontinue` / `sroffset` / `cmcontinue` / `accontinue` / `uccontinue` /
  `lecontinue` / `aicontinue` / `rccontinue`）、`batchcomplete`、`servedby`。
- **与 MediaWiki 的差异（有意简化）**：多值 `titles` 的 `converttitles` 尚未实现；
  列表续传使用简单游标而非严格的服务端快照；`format=json|jsonfm|xml`（xml 为简化结构，与 MediaWiki 的单数元素名不完全一致）。
  未实现的动作会返回 `unknown_action`，参数缺失返回 `missingparam`。
- **权限**：`query` 按访问规则过滤（无权查看的页面按 `missing` 处理，不泄露标题）；
  `edit` 受封禁、保护、标题黑名单与滥用过滤器约束，与网页编辑完全一致。

```bash
# 示例：查内容（formatversion=2）
curl 'http://localhost:18080/api.php?action=query&prop=revisions&rvprop=content&titles=首页&formatversion=2'

# 示例：机器人登录 + 编辑
curl -c cookies.txt 'http://localhost:18080/api.php?action=query&meta=tokens&type=login'
curl -b cookies.txt -c cookies.txt -d 'action=login&lgname=用户&lgpassword=密码&lgtoken=…' http://localhost:18080/api.php
curl -b cookies.txt -d 'action=edit&title=沙盒&text=内容&token=…' http://localhost:18080/api.php
```

## MediaWiki 页面 / 资源兼容入口

除了 `/api.php`，还提供三个直接从 MediaWiki 继承的入口，方便旧外链、脚本和工具继续工作：

- **`/index.php?title=...`**：302 到本站规范页面 `/wiki/...`；`oldid` / `diff` / `action=edit` 等参数原样保留；
  `action=raw` 直接返回源码，并支持 `ctype`（例如 `ctype=text/javascript`）；`curid` 会先解析成标题。
- **`/load.php?modules=...`**：ResourceLoader 风格的最小兼容层。`site.styles`、`user.styles` 返回本站站点 CSS
  （`Wiki.css` + `WikiKernel:Common.css` + 当前皮肤 CSS），`site`、`user` 返回站点 JS；未知模块返回注释占位，
  不返回 404，避免旧页面因单个模块失败而中断。`only=styles|scripts` 与 `skin=` 会尽量遵循。
- **`/thumb.php?f=文件名&w=宽度`**：按需生成 WebP 缩略图并从磁盘流式输出；宽度会限制为不超过原图（不放大），
  生成结果缓存在 `thumbnail/<storedName>/w<宽度>.webp`。生成队列上限由
  `wikikernel.thumbnail.queue-limit` 控制（默认 2），队列满时请求阻塞等待而不是直接拒绝。
  也接受 `width=`、带 `文件:` 前缀的 `f=`；远端 OSS 原图不可本地读取时会 302 到外部原图。

> `wgScript` 仍使用 `/wiki/`：本站规范页面路径优先；`index.php` 只作为兼容入口，不改变默认前端链接。

## MediaWiki JS API 兼容层（mw / mw.loader …）

全站默认加载 `/js/mediawiki.js`（本地内置、同步加载）：页面自定义 JS、微件与 Gadgets 里可以直接用
MediaWiki 风格的 `mw` / `mw.loader` / `mw.Api` 等，无需任何引入。演示：
**`MediaWiki JS API 演示页`**（微件 `微件:MediaWikiJsApi演示` 实时渲染调用结果）、
URL 模块示例 `小部件:MwJsApiUrl模块.js`。

**mw.config**（服务端 `web/MwConfigAdvice` 按请求注入 `window.RLCONF`，`mw.config` 是 Map）：
`wgPageName` / `wgTitle` / `wgNamespaceNumber` / `wgNamespace` / `wgCanonicalNamespace` / `wgIsArticle` /
`wgAction` / `wgArticlePath`(`/wiki/$1`) / `wgScriptPath` / `wgScript` / `wgServer` / `wgSiteName` /
`wgVersion` / `wgContentLanguage` / `wgPageContentLanguage` / `wgUserLanguage` / `wgCategories` /
`wgRevisionId` / `wgCurRevisionId` / `wgFormattedNamespaces` / `wgNamespaceIds` / `wgContentNamespaces` /
`wgCaseSensitiveNamespaces` / `wgLegalTitleChars` / `wgUserName` / `wgUserId` / `wgUserGroups` / `wgUserTokens`（含 `csrfToken`）。

- **命名空间编号**（与 MediaWiki 对齐，脚本里常按数字判断）：主 0 / 讨论 1 / 用户 2-3 / WikiKernel 4-5 /
  文件 6-7 / 模板 10-11 / 帮助 12-13 / 分类 14-15 / 模块 828-829 / 微件 2300-2301 / 小部件 2302-2303；特殊页 -1；
- **用户组**：`*`（匿名只有它）+ `user`（登录）+ MediaWiki 风格映射——
  管理员→`sysop`、行政员→`bureaucrat`、自动确定用户→`autoconfirmed`、机器人→`bot`、核查员→`checkuser`、
  界面管理员→`interface-admin`、评论管理员→`commentadmin`、小部件管理员→`gadgetmanager`、
  小部件编辑者→`gadgeteditor`、免审查用户→`autoreview`、页面管理员→`pageadmin`（未映射的组保留原名），
  因此 `mw.config.get('wgUserGroups').indexOf('sysop') !== -1` 这类判断可直接用。

**mw.loader**：`register([...])`（支持 `[name, deps?]` 数组与 `{ name, dependencies, url, style, scripts, styles, messages }` 对象）、
`implement(name, scripts, styles, messages)`、`load` / `using(modules[, callback])`（返回 jQuery Promise）、
`require` / `getState` / `getVersion` / `addStyleTag` / `state` / `inspect`。
内置模块（`mediawiki`、`mediawiki.util`、`mediawiki.api`、`mediawiki.Title`、`mediawiki.Uri`、`mediawiki.user`、
`mediawiki.cookie`、`mediawiki.storage`、`mediawiki.notify`、`mediawiki.html`、`mediawiki.hook`、`jquery` 等）
直接是 `ready`，`using(...)` 立刻解析；**带 `url` 的模块**会插入 `<script>` 按需加载并执行（同一模块只执行一次）；
`window.RLQ`（`function` 或 `[模块名, 回调]`）在加载后统一处理（与 MediaWiki 启动队列一致）。

**其它**：
- `mw.util`：`getParamValue` / `getUrl` / `wikiUrl` / `wikiScript('api'|'index')` / `rawurlencode` / `wikiUrlencode` /
  `addPortletLink` / `addPortlet` / `hidePortlet` / `escapeIdForAttribute` / `escapeIdForLink` /
  `isIPv4Address` / `isIPv6Address` / `isIPAddress` / `$content`（= `#bodyContent`）；
- `mw.user`：`getName` / `getId` / `isAnon` / `getGroups` / `tokens.get|set` / `sessionId` / `generateRandomSessionId`；
- `mw.Api`（对接 `/api.php`）：`get` / `post` / `getToken(type)` / `postWithToken(type, params)` / `abort` / `getCategories`，
  成功给 `done(data)`；API 错误按 MediaWiki 语义 `fail(code, response)`（`code` = `error.code`，如 `unknown_action`）；
  token 缓存进 `mw.user.tokens`（页面已注入 csrf/login token，通常不需要再取）；
- `mw.Title`（含 `newFromText`、命名空间解析与 `getTalkPage` / `getSubjectPage` / `getUrl`）、`mw.Uri`（`extend` /
  `setParam` / `getQueryString` …）、`mw.html`（`create` / `element` / `escape`，链式 `attr` / `addClass` / `css` /
  `text` / `append` / `toString`）、`mw.hook`（`add` / `remove` / `fire`）、`mw.message`（`params` / `rawParams` /
  `plaintextParams` / `text` / `plain` / `parse` / `escaped` / `exists`）、`mw.notify`（右上角浮动通知，自动消失）、
  `mw.cookie` / `mw.storage`（含 `mw.storage.session`）/ `mw.log`（含 `deprecate`）/ `mw.errorLogger` /
  `mw.track` + `mw.trackSubscribe` / `mw.requestIdleCallback` / `mw.requestAnimationFrame`；
- 页面就绪时会触发 `mw.hook('wikipage.content')`（传入 `mw.util.$content()`），Gadgets 常用。

**与 MediaWiki 的差异（重要）**：
- 没有完整的 ResourceLoader 模块系统（`load.php` 仅兼容站点样式 / 脚本，见上一节）：模块由 `register` / `implement` 声明；**已知未内置的常见模块会给出明确告警**，
  仍按「空模块」解析（不会卡住 `using`）——详见下一节「未内置模块的告警」；
- `mw.html#wikitext` 不做服务端解析（按纯文本，会打一次警告）；`mw.user.getRights()` 目前返回 `[]`；
- `wgScript` 为 `/wiki/`（规范路径；另有兼容入口 `index.php`，见上一节）；`wgVersion` 是站点版本（不是 MediaWiki 版本号）；
- `mw.loader.store`、package 模块（`module.exports`）、`skipFunction` 等高级特性未实现；
- `mw.Api` 只走 `/api.php`：`edit` / `query` / `parse` / `login` 等都在，个别封装（`loadMessages`、`rollback` 等）未提供。

回归测试：`SkinSystemTest`（页面含 `window.RLCONF`、`/js/mediawiki.js`、命名空间编号正确等）。

### 未内置模块的告警（JS 兼容性）

本站没有 ResourceLoader，也**不内置** OOjs / OOUI / jQuery UI / Codex 这些库。为了不出现「无声失效」，
`mw.loader` 维护了一份**已知未内置清单**（`/js/mediawiki.js` 里的 `UNSUPPORTED_MODULES`，22 项，含前缀匹配）：

| 分类 | 典型模块名 | 说明 |
| --- | --- | --- |
| OOjs | `oojs` | `OO.*` 类库（EventEmitter / Registry / Factory / 继承） |
| OOUI | `oojs-ui`、`oojs-ui-core/-widgets/-toolbars/-windows`、`.styles` / `.icons`、`oojs-ui.styles.icons-*` | OOUI 控件库（Dialog / WindowManager / Toolbar…） |
| jQuery UI | `jquery.ui`、`jquery.ui.*` | MediaWiki 1.29 起已弃用（官方建议 Codex）；只有很老的 gadget 用 |
| Codex（Vue） | `codex`、`codex-search`、`@wikimedia/codex`、`vue` | 现代 MediaWiki 的 UI 库 |
| MW 核心（未提供） | `mediawiki.language`、`mediawiki.widgets` | 语言助手 / 基于 OOUI 的表单控件 |

- **行为**：`using()` / `load()` 到未内置（或任何未注册）模块时，控制台给出**带分类与用途的告警**
  （每个模块每次会话只提示一次），模块解析为空、不阻塞页面；`getState()` 保持 `missing` 便于脚本判断；
- **查询接口**：`mw.loader.unsupported()`（清单）、`getCompat(name)`（单个模块的分类/说明，支持前缀）、
  `missing()`（运行时实际遇到过的）、`provided()`（本兼容层提供的 20 个模块，状态 `ready`）；
- **Gadgets 依赖**：定义页支持 MediaWiki 的 `dependencies=模块名,模块名` 写法（见
  `WikiKernel:Gadgets-definition` 的「依赖演示」Gadget）；已启用 Gadget 依赖未内置模块时，页面加载会
  **点名告警**（「Gadget「X」依赖未内置的模块：…」），`特殊:Gadgets` 的「依赖模块」列会把未内置的标红并显示 ⚠；
  注意：本站**不会**按 `dependencies` 自动加载（没有 ResourceLoader），已提供的模块本来就是 `ready`；
- **演示 / 诊断**：`JS兼容性演示页`（微件 `微件:JS兼容性报告` 渲染清单 + 现场演示 `using()` 的告警原文与
  `getState()`）；前端脚本 `/js/js-compat.js`（只负责展示，清单唯一来源是 `mw.loader`）；
- **回归测试**：`GadgetFeatureTest#dependenciesAreParsedAndInjectedForLoaderCompat`（解析 + 注入 + 依赖列）。

**后续计划（若以后要真正兼容 OOUI）**：按 MediaWiki 的模块名内置即可跑现成 gadget 的 `OO.ui` 界面——
`oojs`（10.6 KB）+ `oojs-ui-core/-widgets/-toolbars/-windows`（≈272 KB JS）+ wikimediaui 主题 CSS（≈160 KB）
+ `oojs-ui.styles.icons-*` 图标包（18 个包、共 462 KB，**按需懒加载**，普通页面零开销）；
验证用「真实 gadget 样例 + 自写演示页」。清单与体积见调研记录（`build/wk-vendor-probe/`）。

## 页面预览（Popups / Page Previews）

鼠标悬停在页面链接上约半秒（或键盘 Tab 聚焦）时，弹出目标页的预览浮层：标题、摘要与缩略图，
底部有「前往页面 →」；移到浮层上不会消失，可以点进去。

- **开关**：`features.popups`（**默认开启**），可在 `/init` 安装向导或 `特殊:权限配置 → 站点设置` 关闭；
  关闭后页面不再加载 `/js/popups.js`（`WIKI_CONFIG.popups` 也会是 `false`，方便页面脚本判断）；
- **数据**：`GET /api/page-preview?title=标题`（`PagePreviewApiController`，只读）——
  摘要取页面**首段**：先做 `{{…}}` 模板展开、去掉 `'''''`/`[[…]]` 等标记与文件嵌入，再渲染成 HTML 去标签、
  压缩空白，最后在标点处截断到约 **350 字**；缩略图取首段里第一张嵌入的图片（没有则看全文）；
  重定向页面跟随一次（显示目标页内容，标题仍是被悬停的页面）；页面不存在 / 不可读 / 特殊页返回 404；
- **前端**（`/js/popups.js`，仿 Popups 扩展）：悬停 500ms 才请求、结果按标题缓存（含「不可预览」负缓存，上限 50 条）；
  浮层默认贴在链接下方，越界时自动上/左翻转；滚轮、Esc、点击、移开都会收起；
  只处理**正文区 `#bodyContent`** 里指向站内页面的链接 —— 侧栏（导航 / 工具）、个人工具、页签、搜索框、
  页脚等界面区域**不会**出现预览（与 MediaWiki Page Previews 一致）；
  **红链（`.new`）、特殊页、`?action=`/`?oldid=` 等地址、`.no-preview` 类的链接不预览**；窄屏（<720px，触摸为主）不启用；
- 回归测试 `PopupsTest`（摘要纯文本 / 只取首段 / 截断 / 缩略图 / 重定向 / 404 / 脚本随开关 / **只绑定正文区**）；
  演示页面 `弹出预览演示页`、`弹出预览缩略图演示页`（首段带图片，预览有缩略图）。
