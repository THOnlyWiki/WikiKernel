# DynamicPageList4（WikiKernel 移植）

对应 MediaWiki 的 **DynamicPageList4** 扩展，提供页面里按条件列出页面的解析器函数：

```wikitext
{{#dpl:
|category=有未完成章节的页面
|ordermethod=title
|noresultsheader=无
}}
```

## 已支持

### 筛选

| 参数 | 说明 |
| --- | --- |
| `category=…` | 分类筛选；可重复写多个表示 AND（分类名可带/不带 `分类:` 前缀） |
| `notcategory=…` | 排除分类；可重复 |
| `namespace=…` | 命名空间；空白 = 主命名空间，`all` = 全部，也可以写 `template` / `模板` |
| `count=…` | 最多返回条数（默认 10，上限 500） |
| `offset=…` | 跳过前 N 条 |
| `titleprefix=…` | 标题前缀 |
| `titlecontains=…` | 标题包含 |
| `titlematch=…` | DPL 通配符标题匹配（`%` = 任意串，`_` = 单字符） |
| `titleregexp=…` | 标题正则 |
| `nottitlematch=…` | 排除标题通配符 |
| `nottitleregexp=…` | 排除标题正则 |
| `redirects=include/exclude/only` | 是否包含重定向页 |
| `ordermethod=…` | `title` / `firstedit` / `lastedit` / `pagetouched` / `none` |
| `order=ascending/descending` | 排序方向 |

### 输出

| 参数 | 说明 |
| --- | --- |
| `mode=unordered/ordered/inline/none` | 默认无序列表；`inline` 用 `·` 连接 |
| `format=开始,分隔,结束` | 自定义输出；分隔中可用 `%PAGE%` / `%TITLE%` / `%PAGENAME%` / `%NR%` |
| `shownamespace=0` | 链接文字去掉命名空间前缀 |
| `escapelinks=1` | 只输出标题文字，不生成链接 |
| `titlemaxlength=…` | 链接文字截断长度 |
| `resultsheader` / `resultsfooter` | 有结果时的头 / 尾 |
| `noresultsheader` / `noresultsfooter` | 无结果时的头 / 尾 |
| `oneresultheader` / `oneresultfooter` | 恰好一条结果时的头 / 尾 |

`resultsheader` 等会在当前页面上下文里展开，所以 `{{NUMBEROFARTICLES}}`、模板和魔术字都能用；
`cacheperiod` / `allowcachedresults` 接受但由 core 的页面 HTML 缓存统一处理，不单独建缓存。

### 辅助解析器函数

- `{{#dplvar:set|name|value}}` / `{{#dplvar:name}}` / `{{#dplvar:default|name|value}}`
- `{{#dplreplace:text|pattern|replacement}}`（支持 `/pattern/` 写法）
- `{{#dplnum:text}}`（取第一个数字）

## 未包含

以下 DPL 高级函数依赖 DPL 的章节转写 / 矩阵上下文，当前没有移植：

- `#dplchapter`
- `#dplmatrix`
- `#dpltime`
- `#dplurl`

主查询里的 `include` / `includepage` / `secseparators` 等「把页面内容转写进结果」的参数也暂未实现；
当前输出是页面标题列表。若站点后续用到这些参数，可以在本插件的 `DplFunction` + `PageSearchStore` 上继续扩展。

## 实现位置

- 插件：`plugin/dynamic-page-list/`
- 页面检索接口：`common/.../service/PageSearchStore.java`
- core 实现：`core/.../service/impl/PageSearchStoreImpl.java`

插件不直接访问 `wiki_page` / `wiki_page_category` 表；分类、命名空间、标题与排序都由 core 的
`PageSearchStore` 统一处理，SQLite / MySQL / H2 行为一致。
