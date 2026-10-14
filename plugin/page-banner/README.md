# WikidataPageBanner（页面横幅，插件）

WikidataPageBanner 扩展的 WikiKernel 移植（对应 MediaWiki 的
[WikidataPageBanner](https://www.mediawiki.org/wiki/Extension:WikidataPageBanner)），
从 core 内置功能拆出为独立插件（`plugin/page-banner`）：横幅的**渲染与设置都在本插件**，
core 里不再有页面横幅代码（设置页的分区与安装向导步骤都由插件注册，core 只按声明的键渲染 / 保存）。

## 功能

- `{{PAGEBANNER:文件名|caption=说明}}`（不带 `#`，与 MediaWiki 一致）：指定本页横幅图片与说明；
  指令只把参数写进页面属性（`pagebanner-file` / `pagebanner-caption`），自身输出空串从正文剥离；
  也接受命名参数写法 `{{PAGEBANNER:image=文件名|caption=说明}}`（`1=` / `file=` / `filename=` 同样可用，
  不支持的 `icon=` / `toc=` 忽略）；
- `<exclude-banner-page />`：本页不显示横幅（即使命中白名单 / 逐页配置）；标签从正文剥离，只写
  `pagebanner-exclude=1` 页面属性；
- 横幅 HTML：页面大标题上方（core 皮肤布局的 `#page-banner` 容器，来自
  `PageDisplayHook.beforeTitle`）：
  `<div class="page-banner"><img src="直链" alt="文件名"/>[<div class="page-banner-caption">说明</div>]</div>`，
  图片直链与文件名 / 说明都做 HTML 转义；
- 图片来源优先级：`{{PAGEBANNER:…}}` > 逐页配置 > 图片池（按轮换机制）；
  文件不存在或不是图片时不显示横幅；
- 样式：`static/plugins/page-banner/page-banner.css`（原 core `base.css` 的 `.page-banner*` 区块），
  本页**可能**显示横幅时由 `PageDisplayHook.headItems` 注入到 `<head>`。

## 配置（插件自己注册的设置分区与向导步骤，键与迁移前完全一致）

设置由插件注册的「站点设置分区」（`PageBannerSettingsSection`，显示在「特殊:权限配置 → 站点设置 → 插件设置」）
与安装向导步骤（`PageBannerSetupStep`，`/init` 里本插件的 tab）读写；`LocalSettings.yaml` 的
`wikikernel.features.page-banner*` 由插件在启动时导入站点设置（`PageBannerYamlSettings`），
插件经 `common.service.SiteConfig` 读同一批键：

| 键 | 含义 |
| --- | --- |
| `features.page-banner` | 功能开关（**默认开启**） |
| `features.page-banner.files` | 横幅图片池（`\|` 或换行分隔的文件页名称；为空 = 无默认横幅） |
| `features.page-banner.whitelist-enabled` | 白名单模式（默认开启） |
| `features.page-banner.whitelist` | 白名单页面标题（`\|` 或换行分隔） |
| `features.page-banner.per-page` | 逐页配置（每行 `页面标题 = 横幅文件名`，文件名可省；配过的页面自动放行白名单） |
| `features.page-banner.rotation` | 轮换机制：`stable`（默认）/ `random` / `rotate` / `daily` / `hourly`，非法值回退 `stable` |

轮换机制语义与迁移前一致：`stable` 按标题哈希固定；`random` 每次浏览随机；`rotate` 每次浏览顺序轮换下一张
（进程内计数器，重启归零）；`daily` / `hourly` 按时间片 + 标题哈希。

图片必须是在站内已存在的**图片**文件（`common.service.FileStore`）。

## 与 MediaWiki 原版的差异

- 不做 Wikidata 查询（原版可从 Wikidata 取 P948 页面图片）：横幅完全来自站点设置 / 页面指令；
- 只移植 `{{PAGEBANNER:文件名|caption=说明}}` 与 `<exclude-banner-page />`：原版的其它参数（`icon=` / `toc=` 等）、
  `param=` 等展示模式与配套皮肤增强（TOC 旁的横幅等）未实现；
- 原版的 `{{PAGEBANNER:…}}` 会展开模板参数，这里按**原文**使用（与迁移前 core 抽取参数的行为一致）；
- 说明文字不做 wikitext 解析（转义后原样显示）；
- 图片按容器宽度铺满（`width:100%`、`object-fit:cover`、最高 220px），说明文字叠加在图片下缘
  （与原版「横幅 + 叠加说明」的观感一致，但没有原版的 TOC 内联 / 视差等皮肤增强）。

## 与迁移前（core 内置）的差异

- core 不再渲染横幅：`WikitextRenderer` 里的 `{{PAGEBANNER:…}}` / `<exclude-banner-page />` 抽取、
  `RenderResult.bannerFile/bannerCaption/bannerExcluded`、`PageBannerService.htmlFor` 与
  `WikiPageController` 的 `pageBannerHtml` 都已删除；插件未加载时指令留在正文里
  （`{{PAGEBANNER:…}}` 按未注册指令 / 模板处理，`<exclude-banner-page />` 按原文转义显示）；
- 指令 `{{PAGEBANNER:…}}` 现在走 core 的插件解析器函数派发：core 把
  `{{名字:参数}}`（不带 `#`）里名字命中的插件 `ParserFunction` 交给插件（未注册时仍按模板解析）；
- `pageBanner.rotation.*` 文案随插件走（core 的表单通过合并后的 `MessageSource` 解析，
  键名不变；卸载插件时下拉框显示 `stable` 等原始值）；
- 样式 URL 从 `base.css` 的区块变为 `/plugins/page-banner/page-banner.css`；
  `#page-banner` 容器与它的外边距仍在 core（皮肤布局 `layout.html` + `base.css` / `vector.css`），
  插件只负责容器内部元素；
- 可视化编辑器（`VisualWikitextTokenizer`）仍把 `{{PAGEBANNER:…}}` 与 `<exclude-banner-page />`
  识别为整块占位（编辑侧的保值行为，与渲染无关，留在 core）；
- 开关、白名单、逐页配置、轮换机制、图片池与表单行为**都不变**（同一批站点设置键）。

## 测试

`src/test` 覆盖：`{{PAGEBANNER:…}}` 写页面属性（含引号 / 命名参数 / 未知参数）、
`<exclude-banner-page />` 写排除属性、白名单 / 逐页配置 / 轮换机制 / 图片池与 `caption`、
`{{PAGEBANNER:…}}` 优先、开关关闭与排除时不显示、`FileStore` 里图片缺失 / 非图片时不显示、
横幅钩子的 `beforeTitle` / `headItems` 与插件装配（`PageBannerPluginTest`）、
设置分区与向导步骤的字段 / YAML 路径（`PageBannerSettingsSectionTest`）。

core 侧只验证「插件未加载时 core 不渲染横幅」（`WikitextRendererTest` 的解析器函数派发与
`PageSettingsSectionTest`-类边界）；设置分区平台机制由 `PluginSettingsSectionTest` 覆盖。
