# comments（MediaWiki Comments 扩展移植）

正文里写 `<comment />`（也支持 `<comments />`）的页面，底部显示评论区：发表、回复（支持 wikitext）、
软删除；作者本人或「评论管理员」/ 管理员 / 行政员可以删除。

| 部分 | 说明 |
| --- | --- |
| 标签 | `CommentTag`（`expandBody()=false`）：把标签从正文剥离，只写页面属性 `comments=1` |
| 评论区 | `CommentSectionHook`（`PageDisplayHook.afterContent`，`order=200`）：页面属性有 `comments=1` 且功能开启时渲染列表 + 发表表单 |
| API | `CommentsApiController`：`POST /api/comments`（发表 / 回复）、`POST /api/comments/delete`（软删除） |
| 资源 | `/plugins/comments/comments.js`（发表 / 回复 / 删除后只局部刷新评论区，保留草稿）、`comments.css` |
| 日志 | 用 `common.extension.LogType` 注册 `comment` / `commentdelete` 两个类型（`特殊:日志` 可过滤） |

> 数据层属于插件自己：实体 `plugin/comments/.../data/WikiComment.java`，表 `wiki_comment`
> 由 core 按 `CommentsPlugin.schemaEntities()` 的 Auto-DDL 自动建表 / 补列，插件用
> `common.service.PluginDatabase`（JDBC 桥）读写（`service/CommentStoreJdbc`）。

- **开关**：`features.comments`（默认开启；`/init`、`特殊:权限配置 → 站点设置`、`LocalSettings.yaml` 都可改）。
  关闭时标签仍剥离，但不显示评论区；接口返回 403 `feature.commentsDisabled`。
- **权限**：发表需要登录 + 未被封禁 + 对该页面有查看权限（`common.service.AccessCheck`）；
  删除限作者本人、「评论管理员」、管理员、行政员。
- **数据**：表 `wiki_comment` 与实体 `WikiComment` **属于插件自己**（实体在插件内，core 按
  `CommentsPlugin.schemaEntities()` 自动建表 / 补列，插件经 `common.service.PluginDatabase` 裸 SQL 读写，
  见 `service/CommentStoreJdbc`）；作者名与头像走 `UserStore` / `AvatarStore`，内容渲染走 `WikiText`
  （与页面正文同一套渲染器，模板 / 解析器函数 / 白名单全部生效）。
- 头像是否显示跟随「用户交互」开关（`features.social-profile`），与迁移前一致。

## 与迁移前的差异

- core 不再内置评论：`COMMENT_TAG` 正则、`RenderResult.comments`、`CommentApiController`、`CommentService`、
  `view.html` 的评论区、`base.css` 的评论区样式、`comment.*` 文案全部移除；
  **插件未加载时 `<comment />` 按原文转义显示、页面底部没有评论区、`/api/comments` 不存在**；
- 评论区 HTML 由插件在服务端渲染（与迁移前模板的 class / `data-*` 完全一致），
  因此前端脚本的行为（局部刷新、回复缩进、删除确认）与迁移前相同；
- 评论区 DOM 通过 `PageDisplayHook.afterContent` 注入（`wiki/view.html` 的
  `pageAfterContentHtml` 槽位），而不是模板里的固定区块。

## 与 MediaWiki 的差异

- MediaWiki 的 Comments 扩展用 `<comments>` 解析器钩子 + 自己的表；这里兼容 `<comment />` 与 `<comments />`
  两种写法，并沿用 WikiKernel 原有的「标签剥离 + 页面属性」实现方式；
- 没有投票 / 排序 / 分页 / 通知（Echo），回复用缩进展示（最多 5 层）而不是真正的树形控件；
- 删除是软删除（保留占位「此评论已删除」），不写独立的删除理由，与迁移前一致。

> **功能开关**：`features.comments`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
