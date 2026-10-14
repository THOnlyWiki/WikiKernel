# WikiSEO（WikiKernel 插件）

移植自 MediaWiki 的 [Extension:WikiSEO](https://www.mediawiki.org/wiki/Extension:WikiSEO) **2.7.0**
（源码基线 REL1_43，原扩展路径 `extensions/WikiSEO`），用于给词条阅读页补充 SEO 元数据：

* 双入口：`{{#seo:…}}` 解析器函数与 `<seo>…</seo>` 标签；
* 参数写入 **页面属性**（WikiKernel 的 `wiki_page_property`，相当于 MediaWiki 的 `page_props`）；
* 渲染词条阅读页时由 `HtmlHeadProvider` 输出 `<meta>` / `<link>` / `application/ld+json` 片段，并按需覆盖 `<title>`；
* 生成器：`MetaTag`（总是启用）、`OpenGraph`、`Twitter`、`SchemaOrg`（默认启用）、`Citation`、`DublinCore`；
* 自动描述：没有手工 `description` 时用页面正文现场生成（**本机化实现，替代原版依赖的 TextExtracts**）。

插件 id：`wiki-seo`；插件版本沿用原扩展版本号 `2.7.0`；Java 包名 `cc.thonly.wikikernel.plugin.wikiseo`。

---

## 1. 安装与启用

1. `gradlew.bat :plugin:wiki-seo:jar`（或 `gradlew.bat installPlugins`）构建插件 jar，放到 core 的插件目录（默认 `run/plugins/` 或仓库根 `plugins/`）。
2. 重启 WikiKernel，插件会被自动加载（`META-INF/services/…WikiPlugin` + `META-INF/wiki-plugin.properties`）。
3. 首次使用建议在安装向导（`/init`）的 **WikiSEO** tab 里填写站点验证码等配置；也可以直接写 `LocalSettings.yaml`：

```yaml
wikikernel:
  plugins:
    settings:
      wiki-seo:
        metadata-generators: "OpenGraph,Twitter,SchemaOrg"
        twitter-site-handle: "@YourSite"
        twitter-card-type: summary_large_image
        default-language: zh-cn
        enable-auto-description: true
```

所有配置键都是可选的，未配置时使用与原版一致的默认值。

---

## 2. 用法

### 2.1 解析器函数（推荐，可以塞变量 / 模板）

```
{{#seo:
 |title=页面标题
 |title_mode=append
 |keywords=WikiSEO, SEO, MediaWiki
 |description=本页的 meta description
 |image=Uploaded_file.png
 |image_alt=示例图
 |type=article
 |site_name=示例站
 |locale=zh_CN
}}
```

`{{#seo:}}` 只写页面属性、**不输出可见内容**（返回 `<!-- WikiSEO -->` 注释，core 的管线会把它清掉）。

### 2.2 `<seo>` 标签

```
<seo>
|title=页面标题
|title_mode=append
|description=标签写法的描述
</seo>
```

也可以写成 HTML 属性（属性优先于标签内容，对应原版的 `array_merge($parsedInput, $args)`）：

```
<seo title="页面标题" title_mode="append" description="属性写法的描述"></seo>
```

> WikiKernel 的标签匹配要求成对闭合：`<seo title="x" />` 这种自闭合写法**不支持**（core 的标签正则要求 `</seo>`）。
> 标签内容会先随正文展开（`expandBody = true`），所以里面可以直接写模板 / 解析器函数。

### 2.3 参数表

| 参数 | 说明 | 备注 |
| --- | --- | --- |
| `title` | 覆盖 `<title>` | 只有设置了它才会覆盖 |
| `title_mode` | `replace`（默认）/ `append` / `prepend` | 未知值按 `replace` |
| `title_separator` | append / prepend 的分隔符 | 默认 `" - "`；**值会被 trim**，要保留空格请写 `&nbsp;&gt;&nbsp;` |
| `keywords` | `<meta name="keywords">`，同时拆成 OpenGraph `article:tag` | 逗号分隔 |
| `description` | meta description / og:description / twitter:description | `auto` / `textextracts` = 交给自动描述 |
| `robots` | `<meta name="robots">`，按「索引策略,跟随策略」顺序 | 例：`index,nofollow` |
| `googlebot` | `<meta name="googlebot">` | |
| `canonical` | `<link rel="canonical">` 覆盖 | 原版由单独的 hook 覆盖 head link，这里直接输出 |
| `image` | 社交图片：本地文件名或 URL | 本地文件自动取绝对 URL 与真实宽高 |
| `image_width` / `image_height` | 图片宽高（URL 图片需要手写） | 本地文件会被真实尺寸覆盖 |
| `image_alt` | `og:image:alt` / `twitter:image:alt` | |
| `type` | OpenGraph / JSON-LD 类型 | 默认 JSON-LD 用 `Article` |
| `site_name` | `og:site_name` / JSON-LD publisher 名 / `DC.publisher` | 缺省用站点名 |
| `locale` | `og:locale` / `DC.language` | 例：`zh_CN`（原版格式 `language_TERRITORY`） |
| `section` | `article:section` / JSON-LD `articleSection` | |
| `author` | `article:author`（按 `;` 拆多条）/ `DC.creator` | |
| `published_time` | `article:published_time` / JSON-LD `datePublished` | ISO-8601；`-` 表示不输出 |
| `modified_time` | `article:modified_time` / JSON-LD `dateModified` | 缺省用页面最后更新时间；`-` 表示不输出 |
| `twitter_site` | `twitter:site`（仅在**没有**全局 `twitter-site-handle` 时使用） | |
| `twitter_card` | `twitter:card`（覆盖配置的 `twitter-card-type`） | |
| `hreflang_xx-xx` | `<link rel="alternate" … hreflang="xx-xx">` | 值是要指向的 URL |
| `citation_*` | Citation 生成器参数（见下） | 需要启用 `Citation` 生成器 |
| `dc.identifier.wikidata` | Wikidata ID | 需要启用 `DublinCore` 生成器 |

Citation 参数（与原版一致）：`citation_author`（多个用 `;` 分隔，输出多条 meta）、`citation_abstract_html_url`、
`citation_conference_title`、`citation_creation_date`、`citation_doi`、`citation_firstpage`、`citation_headline`、
`citation_isbn`、`citation_issn`、`citation_issue`、`citation_journal_title`、`citation_keywords`、`citation_lastpage`、
`citation_license`、`citation_name`、`citation_pdf_url`、`citation_publication_date`、`citation_publisher`、
`citation_title`、`citation_type`、`citation_volume`。

**不在合法集合里的参数会被静默忽略**（与 `Validator::validateParams` 一致）。Citation / DublinCore 参数只有在启用
对应生成器时才被接受——这与原版一致（原版把生成器的参数并进校验器）。因此**开启新生成器后，已存在的页面需要重新保存一次**
（core 在页面保存后重新解析正文并持久化页面属性）。

### 2.4 `<title>` 的三种模式

页面标题为「示例页面」时：

| title_mode | title | 结果 |
| --- | --- | --- |
| `replace`（默认） | `SEO 标题` | `SEO 标题` |
| `append` | `SEO 标题` | `示例页面 - SEO 标题` |
| `prepend` | `SEO 标题` | `SEO 标题 - 示例页面` |

标题里的换行会被去掉，最后再做一次实体解码（与原版 `modifyPageTitle()` 一致）。

### 2.5 noindex 启发式

没有设置 `robots` 参数时，命中以下任意一条即输出 `<meta name="robots" content="noindex">`（对应 `MetaTag::shouldAddNoIndex()`）：

* 页面标题（含去掉命名空间后的标题）命中 `noindex-page-titles`；
* 请求参数（`HeadContext.query()`）命中：
  * 键：`curid`、`diff`、`from`、`group`、`mobileaction`、`oldid`、`printable`、`profile`、`redirect`、`redlink`、`stableid`；
  * 键值对：`action` ∈ {delete, edit, history, info, pagevalues, purge, visualeditor, watch}、`feed=rss`、`limit=500`、`veaction` ∈ {edit, editsource}、`title` 命中 noindex 清单。

### 2.6 图片解析顺序

1. 页面 `image` 参数（本地文件名 → `HeadContext.fileUrl/Width/Height`；绝对 URL 直接用；相对路径补站点地址）；
2. 配置 `default-image`；
3. 未关闭 `disable-logo-fallback-image` 时，尝试站点 Logo 候选文件：`Logo.png` / `Logo.jpg` / `Logo.svg` / `Wiki.png` / `WikiLogo.png`；
4. 都没有 → 不输出图片。

---

## 3. 配置项（`wikikernel.plugins.settings.wiki-seo.*`）

| 配置键 | 默认值 | 对应的 MediaWiki 变量 | 说明 |
| --- | --- | --- | --- |
| `metadata-generators` | `OpenGraph,Twitter,SchemaOrg` | `$wgMetadataGenerators` | 逗号分隔；可用 `OpenGraph` / `Twitter` / `SchemaOrg` / `Citation` / `DublinCore`；未知名字忽略并记日志；`MetaTag` 总是启用 |
| `twitter-site-handle` | 空 | `$wgTwitterSiteHandle` | 设置后页面 `twitter_site` 被忽略 |
| `twitter-card-type` | `summary_large_image` | `$wgTwitterCardType` | |
| `default-image` | 空 | `$wgWikiSeoDefaultImage` | 本地文件名或 URL |
| `disable-logo-fallback-image` | `false` | `$wgWikiSeoDisableLogoFallbackImage` | |
| `default-language` | 空 | `$wgWikiSeoDefaultLanguage` | 给当前页输出一条 hreflang alternate |
| `noindex-page-titles` | 空 | `$wgWikiSeoNoindexPageTitles` | 逗号分隔 |
| `enable-auto-description` | `false` | `$wgWikiSeoEnableAutoDescription` | 开启自动描述 |
| `try-clean-auto-description` | `false` | `$wgWikiSeoTryCleanAutoDescription` | 自动描述截到最后一个句号 |
| `google-site-verification-key` | 空 | `$wgGoogleSiteVerificationKey` | |
| `bing-site-verification-key` | 空 | `$wgBingSiteVerificationKey` | 输出 `msvalidate.01` |
| `facebook-app-id` | 空 | `$wgFacebookAppId` | `property="fb:app_id"` |
| `facebook-admins` | 空 | `$wgFacebookAdmins` | `property="fb:admins"` |
| `yandex-site-verification-key` | 空 | `$wgYandexSiteVerificationKey` | |
| `pinterest-site-verification-key` | 空 | `$wgPinterestSiteVerificationKey` | 输出 `p:domain_verify` |
| `norton-site-verification-key` | 空 | `$wgNortonSiteVerificationKey` | |
| `naver-site-verification-key` | 空 | `$wgNaverSiteVerificationKey` | |
| `baidu-site-verification-key` | 空 | `$wgBaiduSiteVerificationKey` | |
| `overwrite-page-image` | `false` | `$wgWikiSeoOverwritePageImage` | **不生效**：WikiKernel 没有 PageImages，保留配置只为与原版一一对应 |

<!-- 社交图片相关配置（$wgWikiSeoEnableSocialImages 等）未移植，因此没有对应的配置键。 -->

---

## 4. Head 输出明细

`SeoHeadProvider` 按「MetaTag → 配置里列出的生成器（按配置顺序）」输出；除 JSON-LD 外每项都是独立的一行 HTML 片段，
所有属性值都做了转义（`&` → `&amp;`、`"` → `&quot;`、`<` → `&lt;`、`>` → `&gt;`、`'` → `&#39;`），
JSON-LD 内部还会把 `<`、`>`、`&` 转成 `\uXXXX`，避免正文内容破坏页面结构。

* **MetaTag**：`description`、`keywords`、`googlebot`、`robots`、`canonical`、各站点验证码、`fb:app_id`、`fb:admins`、hreflang
  （默认语言一条 + `hreflang_*` 参数按语言码排序）。
* **OpenGraph**：`og:title`（页面标题）、`og:site_name`、`og:url`、`og:image`(+`width`/`height`/`alt`)、`og:type`、`og:description`、`og:locale`；
  article 相关：`article:author`（按 `;` 拆多条）、`article:section`、`article:tag`（keywords 按 `,` 拆多条）、`article:published_time`、`article:modified_time`。
* **Twitter**：`twitter:card`、`twitter:site`、`twitter:title`、`twitter:description`、`twitter:image`(+`:alt`)。
* **SchemaOrg**：一段 `application/ld+json`，含 `@context`（`https://schema.org`）、`@type`、`name`、`headline`、
  `mainEntityOfPage`、`identifier`、`url`、`description`、`keywords`、`articleSection`、`datePublished`、`dateModified`、
  `image`（`ImageObject`）、`author` / `publisher`（`Organization`，用站点名与站点地址）、`potentialAction`（`SearchAction`，
  指向 `…/wiki/Special:Search?q={search_term}`）。
* **Citation**：`citation_*` meta（`citation_author` 按 `;` 拆多条；`description` 不输出）。
* **DublinCore**：`<link rel="schema.DC">` + `DC.title` / `DC.description` / `DC.creator` / `DC.language` / `DC.publisher` /
  `DC.identifier`（页面地址）/ `dc.identifier.wikidata`。

---

## 5. 自动描述（本机化实现）

原版在**页面保存时**通过 TextExtracts API 取摘要写入 `description` page prop；WikiKernel 没有 TextExtracts，
改为在 **Head 阶段**读取页面正文现场生成：

1. 去掉引用 / HTML 标签 / 模板 / 参数 / 内链外链标记与行首标记，压平空白；
2. 取前 150 个字符，并在最后一个句末标点（`。！？.!?`）处收尾（没有句末标点就保留 150 字前缀）；
3. `try-clean-auto-description=true` 时再截到最后一个句号（`。` / `.`）。

触发条件：`enable-auto-description=true` **且**（页面没有 `description` 属性，或属性值为 `auto` / `textextracts`）。
手工描述（写入时会同时写 `manualDescription=1` 页面属性，与原版一致）优先，永不被自动描述覆盖。
自动描述**不会**写回页面属性，只在本次渲染的 head 里生效。

---

## 6. 与 MediaWiki 原版（2.7.0）的差异

### 6.1 未移植的功能

| 原版功能 | 说明 |
| --- | --- |
| 社交分享图片生成（GD / Imagick，`$wgWikiSeoEnableSocialImages` 及 `SocialImage*` 配置） | WikiKernel 没有图片处理链路；相关配置键也没有提供 |
| REST 路由 `/wikiseo/v1/socialmediaimage/{title}`（`RestSocialMediaImage`） | 同上 |
| Scribunto `mw.ext.seo.set`（`includes/mw.ext.seo.lua`、`SeoLua`、`ScribuntoHooks`） | WikiKernel 的 Lua 环境未开放插件扩展点，无法注册 `mw.ext` 库 |
| `$wgWikiSeoOverwritePageImage` / `OverwritePageImageProp` | PageImages 专属；配置键 `overwrite-page-image` 保留但**不生效** |
| `DeferredDescriptionUpdate` / `ApiDescription` | 不再需要：描述改为 Head 阶段现场生成（见第 5 节） |
| `InfoAction` hook（页面信息页展示 SEO 数据） | WikiKernel 没有等价的 info action；SEO 参数仍可在页面属性里查看 |
| `WikiSEOPreAddMetadata` / `WikiSEOPreAddPageProps` / `WikiSEOLuaPreAddPageProps` 钩子 | WikiKernel 的插件没有「页面 props 修改」钩子；如需扩展可在 common 里加扩展点 |
| `$wgMetadataGenerators` 里写自定义生成器类名 | 只支持内置的 5 个生成器名 |
| hreflang 的 ISO 语言码表 | 简化为接受任何 `hreflang_` 前缀参数（原版用 `Validator::$isoLanguageCodes` 白名单逐项校验） |
| 子页面翻译的 hreflang 处理（`zh-cn/页面` 自动补根页面的 alternate） | 未实现；只按 `default-language` 与 `hreflang_*` 参数输出 |

### 6.2 行为差异

1. **参数存储**：原版把参数写进 `page_props`（并做 `unserialize` 容错、`pp_sortkey`、JSON 序列化等），WikiKernel 写进
   `wiki_page_property`（core 在页面保存后重新解析并整体覆盖），值始终是纯字符串。
2. **参数名大小写**：与原版一致，参数名保持原样（`Title=x` 会被忽略）；但 `<seo>` 的 HTML 属性由 core 统一转成小写，
   所以 `title="x"` / `TITLE="x"` 都能用。
3. **值清洗**：与原版一致（展开 → `strip_tags` → 实体解码 → `trim`）。实体解码是自实现，只内置**常用命名实体**与
   十进制 / 十六进制数字实体；原版用 PHP 的 `html_entity_decode`（完整 HTML5 命名实体表）。未知实体会原样保留。
4. **空参数**：`{{#seo:}}` / `<seo></seo>` 静默不写入任何属性；原版会渲染 `Error: … must contain at least one non-empty
   attribute.` 的错误框。
5. **自闭合标签**：`<seo … />` 不支持（core 的标签匹配要求闭合标签）。
6. **标签内容展开时机**：WikiKernel 先随正文展开标签内容（`expandBody=true`），插件再展开一次（用于属性值）；
   原版把原始标签内容交给 hook 后自己 `recursiveTagParseFully`。对绝大多数页面等价，但如果正文里出现**字面量**
   `{{…}}`（例如 `<nowiki>{{</nowiki>`）可能被二次展开，注意用 `<nowiki>` 规避。
7. **robots 默认值**：没有 `robots` 参数且未命中 noindex 启发式时**不输出** `robots` meta；原版由 MediaWiki 的
   OutputPage 输出默认的 `index,follow`。
8. **article 时间的自动补全**：只有页面未显式声明非 article 类型（如 `type=website`）时才用页面最后更新时间补
   `article:modified_time` / `dateModified`，`published_time` 缺省时沿用 `modified_time`；原版对所有类型都自动补。
   `published_time` 也接受 `-` 表示不输出（原版只对 `modified_time` 支持 `-`）。
9. **页面最后更新时间**：来自 `WikiPageStore.byId(pageId).updatedAt()`（本地时间，格式 `yyyy-MM-ddTHH:mm:ss`，没有时区），
   原版用 revision 时间戳并输出带时区的 ISO-8601。
10. **Twitter 生成器**：原版的 `Twitter` 继承 `OpenGraph`，会把 `og:*` 再输出一遍（靠 head item 键去重）；WikiKernel 的
    head 是列表，所以 `Twitter` 只输出 `twitter:*`，另外额外补 `twitter:title` / `twitter:description` / `twitter:image`
    （回退 OpenGraph 数据）——原版 2.7 只输出 `twitter:card` / `twitter:site`。
11. **`og:title`**：与原版一致用页面标题（而不是 SEO `title` 参数）；`title` 参数只影响 `<title>`，同时也会成为
    JSON-LD 的 `headline` / `name`（原版用 `getHTMLTitle()`）。
12. **SchemaOrg**：`@context` 用 `https://schema.org`（原版是 `http://schema.org`）；不再输出 `publisher.logo`
    （WikiKernel 没有 `$wgLogo`/`Logos`，拿不到 Logo 的稳定 URL）；省略了值为 null 的字段。
13. **DublinCore**：额外输出 `DC.identifier`（页面地址）；`dc.identifier.wikidata` 原样输出。
14. **Citation**：只输出 `citation_*` meta，不输出原版的 `jsonld-metadata-citation` JSON-LD 片段。
15. **图片兜底**：原版用 `$wgLogos` / `$wgLogo` 的 URL；WikiKernel 没有等价配置，改为尝试 `Logo.png` / `Logo.jpg` /
    `Logo.svg` / `Wiki.png` / `WikiLogo.png` 这些本地候选文件，都没有就**不输出图片**（可用 `disable-logo-fallback-image` 关闭）。
16. **图片兜底与默认图片**：原版在 PageImages 已给页面图片时跳过默认图片；WikiKernel 没有 PageImages，只要有 `image`
    参数就用它，否则用 `default-image`。
17. **相对图片地址**：原版把找不到的文件名原样输出（可能是坏链接）；WikiKernel 会把相对地址补上站点地址。
18. **不存在的参数不会清空旧属性**：与原版一致（`array_merge(existing, new)`），一次 `#seo` 只覆盖它给出的参数。
19. **`twitter_card` 的合法范围**：原版把它算作 Twitter 生成器的参数（未启用 Twitter 时忽略）；本插件把它放进基础参数集合，
    未启用 Twitter 时参数仍会被保存，但不会产生输出。
20. **持久化时机**：SEO 参数在「页面保存 / 删除」时随正文重新解析并写入页面属性（core 的
    `PagePropertyEventListener`）。因此**安装插件后已有页面要重新保存一次**才会生效；原版则在每次保存时立即写 page_props。
21. **错误提示**：原版通过 `wiki-seo-*` 消息（含中文 i18n）输出错误框；本插件只写日志 / 静默忽略，没有 i18n 消息文件。

---

## 7. 代码结构与测试

```
plugin/wiki-seo/
├── build.gradle
├── README.md
└── src
    ├── main/java/cc/thonly/wikikernel/plugin/wikiseo/
    │   ├── WikiSeoPlugin.java            # 入口：@Autowired WikiPageStore + 注册扩展点
    │   ├── WikiSeoConfig.java            # 插件配置（record + from(PluginConfig)）
    │   ├── WikiSeoSetup.java             # 安装向导字段
    │   ├── SeoParserFunction.java        # {{#seo:…}}
    │   ├── SeoTag.java                   # <seo>…</seo>
    │   ├── SeoMetadata.java              # 参数解析 / description 语义 / 实体解码 / 写入属性
    │   ├── SeoValidator.java             # 合法参数集合（含生成器参数与 hreflang_ 前缀）
    │   ├── SeoHeadProvider.java          # HtmlHeadProvider：head 片段 + <title>
    │   ├── SeoHead.java                  # head 阶段上下文 + 片段拼装 / 转义
    │   ├── SeoImage.java                 # 图片（URL + 宽高）
    │   ├── SeoAutoDescription.java       # 自动描述（抽纯文本 + 截断）
    │   ├── SeoJson.java                  # 极简 JSON 序列化（JSON-LD 用）
    │   ├── SeoGenerator.java             # 生成器接口
    │   ├── MetaTagGenerator.java         # description/keywords/robots/验证码/hreflang/canonical
    │   ├── OpenGraphGenerator.java       # og:* / article:*
    │   ├── TwitterGenerator.java         # twitter:*
    │   ├── SchemaOrgGenerator.java       # application/ld+json
    │   ├── CitationGenerator.java        # citation_*
    │   └── DublinCoreGenerator.java      # DC.*
    ├── main/resources/META-INF/…         # 插件清单与 SPI
    └── test/java/cc/thonly/wikikernel/plugin/wikiseo/
        ├── SeoFakes.java                 # 手写替身（HeadContext / WikiRenderContext / ParserCall / 页面存储）
        ├── SeoMetadataTest.java          # 解析 / 校验 / description 语义 / 标签 / hreflang / 配置
        ├── SeoHeadProviderTest.java      # <title>、robots、noindex、验证码、hreflang、图片、各生成器、转义
        ├── SeoAutoDescriptionTest.java   # 纯文本抽取与截断
        └── WikiSeoPluginTest.java        # 插件装配 / 生成器集合 / 安装向导
```

运行测试与打包：

```
gradlew.bat :plugin:wiki-seo:test :plugin:wiki-seo:jar --console=plain
```

测试全部用 JUnit 5 + 手写替身，不依赖 Spring 上下文（`WikiPageStore` / `PluginConfig` 等都用 fake 实现）。
