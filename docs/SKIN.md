# 皮肤与前端界面

皮肤机制与内置皮肤、页面自定义 CSS / JS 与代码页面（含 TypeScript 代码页面的服务端编译）、
用户脚本引用（`mw.wikiImport`）、与 MediaWiki / OOUI 的样式对齐、插件皮肤，以及界面侧边栏 `WikiKernel:Sidebar`。

其它文档：[README](../README.md) ｜ [接口与兼容 API](./API.md) ｜ [配置与数据](./CONFIG.md) ｜ [功能详述](./FEATURES.md) ｜ [Wikitext 与内容标记](./WIKITEXT.md) ｜ [插件开发与运行](./PLUGIN-DEV.md)

## 侧边栏（WikiKernel:Sidebar）

侧边栏由页面 `WikiKernel:Sidebar` 控制（标题可在 `wikikernel.sidebar.page` 配置），语法类似 MediaWiki:Sidebar：

```text
* 导航
** 首页
** 特殊:全部页面|全部页面
** 特殊:特殊页面|特殊页面
* 工具
** 特殊:链入页面?page=$title$|链入页面
** 特殊:相关更改?page=$title$|相关更改
** 特殊:上传文件|上传文件
** 特殊:特殊页面|特殊页面
** $title$?printable=yes|可打印版
** $title$?oldid=|固定链接
** $title$?action=info|页面信息
```

- `*` 开头 = 分组标题；`**` 开头 = 条目；写法 `目标|显示文字`（不写显示文字就用目标）；
- 目标以 `http(s)://` 开头视为外链（新窗口打开），否则链接到 `/wiki/<目标>`；
- **`$title$` 是当前页面完整标题的占位**（大小写不敏感；侧栏里也兼容 MediaWiki 魔术字写法
  `{{FULLPAGENAME}}`（完整标题）与 `{{PAGENAME}}`（去掉命名空间的页面名））：
  只替换 `*` 开头的侧栏定义行（普通说明行原样保留）；没有页面上下文（如错误页取不到标题）时，
  含占位符的条目自动隐藏；MediaWiki 原版侧栏不支持这种按页替换，这是本 wiki 的扩展；
  正文 wikitext 里的 `{{PAGENAME}}` / `{{FULLPAGENAME}}` / `{{NAMESPACE}}` / `{{!}}` 魔术字同样可用（MediaWiki 语义）；
- 页面不存在或为空时使用内置默认侧边栏（同样带完整「工具」箱）；
- 内容页渲染时，工具箱里的标准条目（链入页面 / 相关更改 / 上传文件 / 特殊页面 / 可打印版 / 固定链接 / 页面信息）
  会按**链接目标**识别并替换为当前页面版本（`?page=` / `?printable=yes` / `?oldid=<当前版本>`）——
  与侧栏页面内容的语言无关（中文侧栏 + 英文界面也只有一组工具）；
  识别标记是目标里的 `?page=` / `?printable=yes` / `?oldid=` / `?action=info` 或 `特殊:上传文件` 等标准工具页；
- 特殊页同样生成这套工具条目：链入页面 / 相关更改以该特殊页为查询目标，
  可打印版打开该特殊页的打印版、固定链接指向该特殊页本身、页面信息显示该特殊页的信息（`?action=info`）；
  用户自定义（非托管）条目始终原样保留；
- `SidebarModelAdvice`（`@ControllerAdvice`）会把 `sidebar` 模型自动加给所有 MVC 模板，插件页面模板同样可用。

## 皮肤系统与搜索

### 皮肤机制

- **SPI**：`common.extension.Skin`（`id` / 本地化名称 / 说明 / 版本 / `layout` / `stylesheets` / `scripts` / `logoUrl` / `order`）；
  `SkinRegistry` 汇总 core Bean 与插件注册（`PluginContext.register(Skin.class, ...)`）；
- **模板接入**：所有页面模板统一写 `~{__${skin.layout}__ :: layout(...)}`（`SkinAdvice` 注入当前皮肤的 `layout`），
  皮肤提供 `templates/skins/<id>/layout.html`（`th:fragment="layout(pageTitle, content)"`）；
- **统一正文容器**：布局把页面传入的 `~{:: section}` 统一包进 `#bodyContent class="wiki-content"`
  （`th:insert`）——词条、特殊页、编辑页等所有页面共用同一套 `.wiki-content` 正文排版
  （标题衬线体 / 分隔线、字号与行高），不存在“特殊页没包上 class”的不一致；
- **样式分层**：core 的 `/css/base.css`（设计变量、正文排版、通用组件、各页面控件）先行加载，
  皮肤自己的 `/skins/<id>/<id>.css` 后加载（可覆盖）；head 注入顺序 base → 皮肤 → 页面自定义 CSS；
- **表单控件（下拉框）**：全局 `select` 仿 MediaWiki（OOUI）——白底、1px 灰边（`#a2a9b1`）、2px 圆角、
  右侧灰色三角箭头（`appearance:none` + 内联 SVG 背景），悬停边框加深、聚焦蓝色高亮、禁用置灰；
  覆盖 特殊:日志 / 特殊:全部页面 / 特殊:封禁 / 特殊:参数设置 / 特殊:权限配置 等所有下拉框；
- **皮肤资源**：`GET /skins/{id}/{path}` 从皮肤所属类加载器读取 `static/skins/<id>/...`
  （内置皮肤读 classpath，插件皮肤读插件 jar；带路径穿越防护与长缓存）；
- **配置**（LocalSettings.yaml）：
  ```yaml
  wikikernel:
    skins:
      default-skin: vector   # 默认皮肤 id；不可用时回退 fallback
      disabled: [ ]          # 禁用的皮肤 id（fallback 不可禁用）
  ```
- **临时预览**：任意页面加 `?useskin=<id>`（不改配置、不保存偏好）；
- **用户偏好**：`特殊:参数设置`（`Special:Preferences`）→「外观」标签，或快捷页 `特殊:外观` / `Special:Appearance`——登录用户选择皮肤（存 `wiki_user.skin`），
  支持预览与「站点默认」；保存走 `POST /api/user/skin`，当前会话立即生效；
- **特殊:版本** 动态列出全部已装皮肤（版本 / 说明 / 内置或插件 / 「使用中」）。

### 内置皮肤

- **Vector**（默认，`vector`）：模仿 MediaWiki Vector（经典版 2010）——顶栏品牌 + 搜索、左侧 `WikiKernel:Sidebar`
  导航分组、白底蓝框内容区（`#a7d7f9`）、阅读/编辑/查看历史标签栏、衬线体标题、手机端折叠菜单；
  **垂直几何与 MediaWiki 对齐**（之前内容列整体偏低）：顶部渐变区 5em（80px，白→`#f6f6f6`）、
  页签行探进渐变区下半部（页签顶 40px、底 80px）、**内容框顶边正好在 80px**、
  logo 区块 10em×160px（从页面顶端起，探出渐变区，侧栏因此首个分组从 176px 处开始）、
  侧栏中缝 176px（= MediaWiki 宽屏的 11em）、个人工具 `top: 6px`、内容框内边距 `1.25em 1.5em 1.5em`；
  可打印版（`?printable=yes`）不上移，移动端（≤719px）纵向堆叠时同样取消上移；
- **Fallback**（`fallback`，不可禁用）：极简无装饰布局，Vector 被禁用或配置指向不可用皮肤时兜底，保证站点可开；
  但功能与 Vector 对齐：**用户栏**（登录 / 注册 / 参数设置 / **外观** / 私信 / 贡献 / 退出——保证能切回其它皮肤）、
  搜索框、页面页签（阅读 / 编辑 / 查看历史 / 移动 / 保护）与 `WikiKernel:Sidebar` 侧边导航，
  样式只依赖 `/css/base.css`（`.skin-fallback` 少量补全）；
- 页面自定义 CSS/JS 与代码页面能力对所有皮肤生效：
  - 页面自定义 CSS：全站 `Wiki.css` + `WikiKernel:Common.css` + 页面子页 `{标题}/XXX.css`（`CssSanitizer` 转义 `<`，不会提前闭合标签）；
  - 页面自定义 JS：`WikiKernel:Wiki.js` + `WikiKernel:Common.js` + `{标题}/任意.js`，经 `?action=raw&ctype=text/javascript` 外部加载；
  - **前端基础库（本地内置，不走 CDN）**：所有页面 head 里**同步**加载 **jQuery 3.7.1**（`/vendor/jquery/jquery.min.js`）、
    **axios 1.20**（`/vendor/axios/axios.min.js`，已去掉 sourceMappingURL）与
    **MediaWiki JS API 兼容层**（`/js/mediawiki.js`，见下节）；同步加载是为了让正文里的内联 `<script>`
    也能立刻使用 `mw` / `$` / `axios`（与 MediaWiki 在 head 里加载 jQuery / startup 一致）；
  - **页脚（MediaWiki 风格）**：所有页面底部默认显示「隐私政策 / 关于WikiKernel / 免责声明」三个链接
    （指向 `WikiKernel:隐私政策` / `WikiKernel:关于` / `WikiKernel:免责声明`，未创建时为红链）与「语言：中文 · English」；
    有版本的内容页（词条 / 用户页 / 分类说明 / 文件说明页）在链接上方多一行
    「此页面最后编辑于X年X月XX日 (星期X) XX:X。」（按用户时区换算，英文界面为 "This page was last edited on ..."；
    特殊页没有版本，不显示该行）；页脚分**左右两区**（左区贴左、右区贴右，仿 MediaWiki 的
    footer-places / footer-icons）：`WikiKernel:Footer-Left` / `WikiKernel:Footer-Right` 页面的 wikitext
    渲染后分别替换左区（内置三链接）/ 右区（内置语言链接）；页面不存在或为空时回退内置内容；
  - **代码页面**：`.js` / `.css` / `.ts` / `.tsx` 只读高亮预览 + 带补全的编辑器（CodeMirror 本地内置，不依赖 CDN）；
    **默认只有「界面管理员」能编辑 / 创建 / 移动 CSS / JS 页面**（仿 MediaWiki 的 interface-admin，
    可在 `特殊:权限配置` 用访问规则覆盖，例如给单个页面加精确规则）；
    阅读模式的预览是**可拖选 / Ctrl+A/C 复制的只读代码块**（光标由 CSS 隐藏，右上角另有「复制」按钮兜底）；
    页面顶部显示「清除浏览器缓存」提示（仿 MediaWiki:Clearyourcache，含 Firefox/Safari/Chrome/Edge 快捷键）；
    `.ts` / `.tsx` 还可以在服务端编译成 JavaScript 当脚本执行，见下文「[TypeScript 与 TypeScript 代码页面](#typescript-与-typescript-代码页面)」；
- 统一组件样式在 `/css/base.css`（`:root` 变量 + `.wiki-form` / `.wiki-button` / `.wikitable` / `.wiki-warning` 等），
  所有皮肤共用；
- 静态资源**缓存与自动刷新**：CSS/JS 长缓存，链接带 `?v=` 启动版本号（随服务启动变化），重启后浏览器自动加载新资源；
- 静态 JS/CSS 响应带 `charset=UTF-8`（`StaticResourceCharsetFilter`）：直接打开 `/js/xxx.js` 查看源码时中文注释不会乱码
  （文件本身一直是 UTF-8；浏览器对不带 charset 的 `text/javascript` 会按系统默认编码解码，中文 Windows 下就是 GBK）；
- 表单提示（`.form-message`，评论 / 保存 / 上传 / 设置等所有提交反馈）由 `static/js/feedback.js` 统一处理：
  显示后自动丝滑淡出——**成功消息 3 秒**、错误等其它消息 8 秒，淡出 0.65s 后隐藏；重新显示会重新计时。

### TypeScript 与 TypeScript 代码页面

`XXX.ts` / `XXX.tsx` 页面有两层能力：**代码页**（默认，零依赖）与**服务端编译**（可开关，自包含）。

**第一层：代码内容页（始终可用，新增体积 0）**

查看时是只读高亮预览（可拖选 / 复制），编辑时是带补全（含 `interface` / `readonly` / `satisfies` 等 TS 关键字）的代码编辑器，
`特殊:页面信息` 的内容模型显示 `typescript`，Wikitext 里也能用 `<syntaxhighlight lang="ts">` 高亮 TypeScript 代码块。
**没有新增任何前端资源**：CodeMirror 5 的 JavaScript 模式本就注册了 `text/typescript` MIME，服务端 `SyntaxHighlighter`
也早有 `typescript` 词法（`ts` / `tsx` 别名）。

**第二层：服务端编译成 JavaScript（`features.typescript`，默认关闭）**

开启后，`.ts` / `.tsx` 页面可以像 `.js` 页面一样**被浏览器执行**：

- **编译在哪**：服务端 JVM 内，GraalJS 跑**官方 `typescript.js` 5.9.3**（作为资源内置在 jar 里，8.7 MB）——
  自包含、**不依赖主机安装 Node**；首次编译时懒加载（实测约 4 秒一次性），之后每次转译约几十到几百毫秒；
- **产物怎么用**：
  - `XXX.ts?action=raw&ctype=text/javascript` → **编译后的 JavaScript**（带 `X-Source-Revision` 头），
    页面脚本与 Gadget 资源都走这条通道，浏览器完全不知道源码是 TypeScript；
  - `XXX.ts?action=raw`（普通 raw）→ 仍是 **TypeScript 源码**，供编辑、差异、审计；
  - 页面脚本：`{页面标题}/任意.ts`（与 `{页面标题}/任意.js` 同规则）；Gadget 定义里可直接写 `小部件:Xxx.ts`；
  - 因为页面脚本 / Gadget 是**经典脚本**（不是 `type=module`）加载，脚本里**不要写顶层 `import` / `export`**
    （写 `export` 的 TS 页面适合当模块源码或给打包器用，不适合直接当页面脚本）；
- **编译缓存**：表 `wiki_compiled_script`，键 = `页面 + 版本 + 目标 + 编译器版本` → 每次保存自动失效，
  绝不会把旧版本的编译结果发给浏览器；每个页面只保留最新一条（`cache-limit` 控制总量）；
- **保存时编译（`features.typescript-compile-on-save`，默认关闭）**：开启后保存 `.ts` 页面立刻编译，
  页面顶部直接列出**带行号的语法诊断**（`3:12 Expression expected`），也**不会阻止保存**；
  实现挂在「页面变更事件」上，所以网页表单、`action=edit`、导入、AI 代理等**所有保存路径**都会自动编译；
- **失败与降级**：编译失败 / 未开启时，`ctype=text/javascript` 返回的是一段**说明用注释**（而不是 TS 源码），
  浏览器只会打日志、不会报语法错误；提示里给出页面标题、版本与诊断；
- **能力边界**：用 TS 的 `transpileModule`（逐文件转译）——语法层面完整支持（类型注解、泛型、`enum`、装饰器、
  `as` 断言、TSX），但**不做类型检查**（类型错误需要完整 Program，代价与收益都不合适），也**不做模块打包**
  （多个 `.ts` 互相 `import` 不会被打成一个文件）；
- **权限**：`.ts` / `.tsx` **不**算「可执行代码页」，因此不像 `.js` / `.css` 那样默认收紧到界面管理员
  （它先被当作普通内容；真正执行时同样受页面访问规则约束）。

**安全边界（重要）**

`transpileModule` **只解析 / 转换文本，不执行用户的代码**：用户写的内容始终是交给 tsc 的**数据**（字符串），
永远不会被求值。即便如此，JS 引擎仍按「输入不可信」的默认值收紧（单点定义在
`GraalTypeScriptCompiler.sandboxedBuilder()`，并有测试盯着）：

| 收紧项 | 效果（实测） |
| --- | --- |
| `HostAccess.NONE` + 关闭类查找 + `js.java-package-globals=false` | `Java.type('java.lang.System')` → `TypeError: Access to host class … is not allowed`；`typeof java` = `undefined` |
| `IOAccess.NONE` + `js.load=false` | `load(…)` 函数本体被关掉，文件 / 网络读写不可用 |
| `allowCreateThread(false)` / `allowNativeAccess(false)` / `PolyglotAccess.NONE` / `EnvironmentAccess.NONE` | 不能建线程、不能用原生代码、不能访问其它语言与环境变量（`typeof Polyglot` = `undefined`） |
| 空输出流 + `js.console=false` | 编译器自身的打印不会污染站点日志 |
| `js.ecmascript-version=2022`、`js.foreign-object-prototype=false`、`js.unhandled-rejections=throw` | 版本固定、不挂原型链、未处理 Promise 直接抛错 |

**但这不构成「可安全运行任意不可信 JavaScript」的沙箱**：GraalJS **社区版没有** `sandbox.MaxCPUTime` /
`sandbox.MaxHeapMemory` 这类配额选项（实测 24.2.1 里这些选项**不存在**，属于 Enterprise 沙箱能力）。
资源保护由三件事承担：

1. **墙钟超时**：单次编译 `timeout-seconds`（默认 15 s）、引擎首次装载 `init-timeout-seconds`（默认 120 s）；
   超时立即**中断并丢弃引擎**（下次重建），请求绝不会被无限挂住；
2. **输入大小上限**（**可在「站点设置 → TypeScript 服务端编译」动态改，立即生效、无需重启**）：
   站点设置值优先，其次 `wikikernel.typescript.max-source-kilobytes`（YAML），最后默认 256 KB；
   **`-1` 或 `0` = 不限制**，输入框留空 = 回退到配置文件的值；超限的页面会在页面顶部与脚本请求里给出诊断
   （提示当前上限与调整入口），且**超限不写编译缓存** —— 把上限调大后同一页面立刻可以编译；
3. **并发上限** `pool-size`（默认 1）：同时可用的 GraalJS 上下文数量 —— 这是内存的硬边界，
   每个上下文常驻约 150–400 MB。

> 结论：**适合编译站点自己人写的 TypeScript**（配合功能开关与页面权限）。若要在服务端执行真正不可信的
> 任意 JavaScript，请另建**进程级隔离**（独立进程 / 容器 + OS 级 CPU、内存、网络限制），不要依赖内存内引擎配置。

**并发与吞吐**

- 编译任务跑在**虚拟线程**上（`Executors.newVirtualThreadPerTaskExecutor`）：排队等待几乎不占平台线程，
  不会因为编译排队而耗尽 Tomcat 请求线程池；
- GraalJS 的 `Context` 不是线程安全的 → 每个上下文由独占锁保护；`pool-size`（默认 1）决定**真正并行**的编译数，
  调大就多一份内存；
- **同版本去重**：多个请求同时要同一版本（脚本被多页引用、一屏内重复请求）时**只编译一次**，其余共享结果；
- **启动预热** `warmup: true`：功能开启时启动后台装载引擎，把首次 4 秒级装载从请求路径挪走；
- 实测数字：引擎装载 4.28 s（一次性）、单次转译约 391 ms；`pool-size=1` 时持续吞吐约 2–3 次/秒 ——
  正常 wiki 的负载（保存时编译 + 脚本首次加载）远低于这个量级。

**配置**（开关在 `features.typescript*`，可在 `/init` 或「特殊:权限配置 → 站点设置」改）：

```yaml
wikikernel:
  features:
    typescript: true                 # 开启服务端编译
    typescript-compile-on-save: true # 保存后立刻编译（显示诊断）
  typescript:
    target: ES2020        # 输出语言级别（ScriptTarget 名）
    module: ESNext        # 模块形态（ModuleKind 名）
    jsx: ""               # .tsx 的 JSX 形态（react / preserve）；留空 = 只有 .tsx 时用 react
    timeout-seconds: 15   # 单次编译超时（超时即中断并重建编译器）
    init-timeout-seconds: 120  # 编译器首次装载（读入 8.7 MB tsc）的超时
    pool-size: 1          # 引擎池大小（并行编译数；每个约 150–400 MB）
    warmup: true          # 启动后后台预热
    max-source-kilobytes: 256  # 源码大小上限（-1 / 0 = 不限制）；站点设置里的值优先，可动态改
    cache-limit: 200      # 编译产物缓存行数上限
```

> 源码大小上限是**运行时策略**：在「站点设置 → TypeScript 服务端编译」里改完立即生效（写进站点设置表），
> `-1` / `0` = 不限制，留空 = 用上面 YAML 的值；超限的 `.ts` / `.tsx` 页面会在顶部显示诊断
> （含当前上限与调整入口），并且**不会写编译缓存**——把上限调大后同一页面立刻可以编译。

**代价**（选择 S2 自包含路线的实测值）：jar 从 63 MB → **122.6 MB**（GraalJS 42.75 MB + `typescript.js` 8.69 MB +
依赖），启用后引擎常驻约 150–400 MB 内存；默认关闭，不开启时不加载引擎（只有 jar 体积）。

**演示与测试**：`TypeScript演示.ts`（代码页 / 可编译）、`TypeScript高亮演示页`（`<syntaxhighlight lang="ts">`）、
`TS脚本执行演示页` + `TS脚本执行演示页/演示.ts`（页面脚本真机执行）；回归测试 `CodePageTest`（高亮 / 内容模型）
与 `TypeScriptCompileTest`（转译能力、raw 端点、缓存、失败降级、保存时编译、页面脚本 / Gadget 接入）。

### 引用用户脚本（`mw.wikiImport`）

把 wiki 页面当成 **ES 模块**加载：`.ts` 由服务端编译，`.js` 原样返回，模块之间可以互相引用。

```js
// ① then 链：任何位置都能写，**不需要 async 函数**（经典脚本最常用）
mw.wikiImport('用户:张三/工具.ts').then(function (tool) {
    console.log(tool.greet({ who: '世界', count: 1 }));
});

// ② 预加载 + 同步读取：head 或脚本顶部尽早预加载，之后在点击 / 定时 / 后续脚本里同步取
mw.wikiPreload(['用户:张三/工具.ts', '小部件:我的组件.js']);
button.addEventListener('click', function () {
    const tool = mw.wikiModule('用户:张三/工具.ts');   // 已加载 → 直接拿到；没加载好会返回 null 并告警
    tool && tool.greet({ who: '同步调用', count: 2 });
});

// ③ 页面脚本声明成 <script type="module"> 时，可以用**顶层 await**（不用包 async 函数）
const tool = await mw.wikiImport('用户:张三/工具.ts');
```

> **为什么不能“裸调用”同步拿结果**：动态 `import()` 本质上要发请求 + 解析模块，只能返回 Promise。
> 所以“同一行同步拿到模块”只有两条路：把**消费方**升级成模块脚本用顶层 `await`（写法 ③），
> 或者用**预加载 + 同步读取**（写法 ②，等模块到齐后再同步取）。经典脚本里最省事的是写法 ①。

| API | 说明 |
| --- | --- |
| `mw.wikiImport(标题)` | 加载并返回模块命名空间（Promise）；同一标题只请求一次，**模块单例** |
| `mw.wikiImportAll([标题…])` | 并行加载多个，返回命名空间数组（顺序与入参一致） |
| `mw.wikiPreload([标题…])` | `wikiImportAll` 的语义化别名，建议在脚本顶部尽早调用 |
| `mw.wikiModule(标题)` | **同步**读取已加载模块；未加载返回 `null` 并在控制台说明原因 |
| `mw.wikiModules()` | 已加载模块的快照（调试用） |
| `mw.wikiModuleUrl(标题)` | 标题对应的模块 URL（调试用） |

**服务端语义**（这条通道复用了 `?action=raw`，没有新路由）：

- 模块 URL：`/wiki/{标题}?action=raw&ctype=text/javascript&module=1`，标题逐段编码、保留子页面斜杠；
- **版本化 ETag + `Cache-Control: no-cache`**（源码 / 编译产物 / 说明注释都有）：模块 URL 固定不变，
  浏览器每次校验 → 页面一改立刻生效、没改走 304；**304 时服务端不重新编译**（有测试盯着）；
- `&module=1`（模块加载器加的）：编译失败 / 未启用时返回 **500 + 诊断文本**，`import()` 直接失败、
  错误里带服务端诊断（助手还会把响应体抓回来拼进 `Error.message`），不会静默变成空模块；
  经典脚本（不带 `module=1`）仍然照旧拿说明性注释，不会让页面报语法错误；
- 导入**受读权限约束**（`?action=raw` 本来就会校验），无权页面拿不到；
- 加载失败 / 页面没有 `export` 时会在控制台给出可读提示。

**已知边界**

- 只支持**绝对标题**（`用户:张三/工具.ts`）：模块内部写相对路径 `./util.js` 会被解析成
  `/wiki/用户:张三/util.js`（HTML 页面）而失败——要相对导入需要另一套「模块路由 + import map」，暂未实现；
- `.js` 页面按 MediaWiki 规则只有**界面管理员**能创建 / 编辑（`.css` 同理）；普通编辑者可以写 `.ts`，
  所以**用 TypeScript 写页面脚本是普通编辑者唯一的“可执行脚本”路径**；
- 导入即执行对方代码：引用前请确认对方页面可信（这与 Gadget / 页面脚本的信任模型一致）。

**演示与测试**：`WikiImport演示页`（宿主页 + `演示.ts` 页面脚本 + `工具.ts` / `常量.ts` / `坏模块.ts` 三个模块）；
回归测试 `WikiModuleTest`（ETag / 304 不重编译 / `module=1` 失败语义 / 助手与引入）。

### CSS 与 MediaWiki / OOUI 的对齐（规范与来源）

样式值不是凭感觉定的，能对齐官方就对齐官方——参考文件抓在 `build/wk-vendor-probe/`（本地调研用，不入库）：

| 组件 | 规范来源 | 我们的实现 |
| --- | --- | --- |
| 按钮 / 输入框 / 下拉框 / 复选框 | OOUI 0.54 `wikimediaui` 主题（`oojs-ui-core-wikimediaui.css`） | 1px `#a2a9b1` 边框、2px 圆角、白底输入框、`#f8f9fa` 按钮、悬停变白 + 边框 `#54595d`、聚焦 `inset 0 0 0 1px #36c`、禁用 `#eaecf0`/`#72777d`、复选框 `accent-color: #36c`；**裸控件也有基线样式**（任何容器 / 动态生成的 UI 都一致，容器级规则特异性更高会覆盖） |
| 消息框 | `mediawiki.skinning/messageBoxes.less` + Codex token | `.wiki-warning` 与新增的兼容类 `.mw-message-box`（`-error` / `-warning` / `-success`）：`12px 24px` 内边距，红 `#ffe9e5`/`#f54739`、黄 `#fdf2d5`/`#ab7f2a`、绿 `#dff2eb`/`#099979`、通知 `#eaecf0`/`#72777d` |
| 表格 | `mediawiki.skinning/content.tables.less` | `.wikitable`：外框 1px `#a2a9b1`、单元格 1px + `0.2em 0.4em` 内边距、表头 `#eaecf0` **居中** |
| 代码 | `mediawiki.skinning/elements.less` | `pre`/`code`/`.mw-code`：等宽字体、`#f8f9fa` 底 + 1px `#dadde3` 边框；行内 `code` 圆角 + `1px 4px` 内边距（`pre` 内自动去框） |
| 差异页 | `mediawiki.diff.styles/diff.less` | 单元格 4px 间隔（`border-spacing`）、内容行「左 4px 彩色边框 + 0.33em 圆角」（新增 `#a3d3ff`、删除 `#ffe49c`，Codex token）、行号 / 标记列无底色、**只有改动的词上底色**、标题行居中、行号加粗 |
| 最近更改字节数 | Codex token（`color-content-added` / `-removed`） | `(+n)` `#006400`、`(−n)` `#8b0000`、`(0)` `#a2a9b1` |
| 文件页历史 | `mediawiki.action/mediawiki.action.view.filepage.less` | 当前版本行加粗（MW `.filehistory-selected`）、表头左对齐、单元格顶对齐 |
| 其它 | — | 页签监视星标 ☆/★（`#watch-tab`，随 `data-watched`）、ConfirmEdit 算式加粗、滥用过滤器标记 / 受限说明灰字、搜索框不透明白底（Vector 经典版） |

- **审计脚本**（可复跑）：`build/wk-stress/css_audit.py`（抓页面 class × CSS 选择器，列没有规则的 class）、
  `css_audit2.py`（JS / Java / 模板里动态生成的 class 同样对比）——剩下的都是 JS 钩子 / 结构包装类（无需样式）；
- 浏览器侧还有「UA 默认样式扫描」用法：遍历所有特殊页面，找 `border-color: rgb(118,118,118)` /
  `font-family: Arial` 的控件（修复前有搜索按钮 / 回顶按钮 / 若干裸输入框，现在为 0）。

### 插件皮肤

`plugin/plain-skin` 是一个可直接构建的示例（`gradlew installPlugins` 或复制 jar 到插件目录）：

```text
plugin/plain-skin/
  src/main/java/.../PlainSkinPlugin.java   # onStart: context.register(Skin.class, new PlainSkin())
  src/main/resources/META-INF/wiki-plugin.properties
  src/main/resources/templates/skins/plain/layout.html   # 布局（插件 jar 内，PluginTemplateResolver 解析）
  src/main/resources/static/skins/plain/plain.css        # 样式（/skins/plain/plain.css 端点读取）
```

插件皮肤的展示名 / 说明若需要跟随界面语言，可在 core 的语言包里加 `skin.<id>.name` / `skin.<id>.description`
（没有时用插件 `Skin.displayName()` / `description()` 的固定值）。

搜索：

- `特殊:搜索` / `Special:Search`：标题 + 正文模糊匹配（LIKE），带摘要；
- `GET /api/search?q=...`：聚合 **core 自带的数据库搜索**（`DatabaseSearchProvider`）与插件贡献的 `SearchProvider`。
