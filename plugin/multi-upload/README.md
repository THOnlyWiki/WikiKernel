# multi-upload（MediaWiki MsUpload / 上传向导移植）

多文件批量上传：**上传页**可以一次选择多个文件，已选文件显示成可改名 / 可移除的列表，
提交时逐个上传（每个文件独立成文件页，单个失败不影响其它），完成后把新文件插进「最近上传」画廊；
**编辑页工具栏**的「上传并插入图片」对话框同样可以多选，逐个上传后把 `[[文件:名|thumb]]` 全部插到光标处。

| 部分 | 说明 |
| --- | --- |
| 开关 | `features.multi-upload`（**默认关闭**；`/init`、`特殊:权限配置 → 站点设置`、`LocalSettings.yaml` 都可改）。core 维护这个键，插件经 `SiteConfig` 读它 |
| 上传接口 | 仍走 core 的 `POST /api/files/upload`（权限、审核、大小上限、类型限制都在 core 侧） |
| 前端脚本 | `/plugins/multi-upload/multi-upload.js`（站点级注入；脚本自己判断页面上有没有上传表单 / 编辑器工具栏） |
| 文案 | `files.multi*` / `files.chooseMany` / `toolbar.uploadChooseMany`（键与迁移前 core 一致，core 里已删除） |

## 与迁移前的差异

- core 的上传页**永远是单文件表单**（`#file-input` + 「目标文件名」输入框），多选列表、提示文字与
  「已选择的文件」区块由插件脚本在页面加载后创建（未加载插件 = 迁移前「开关关闭」的样子）；
- `window.WIKI_CONFIG.multiUpload` 与批量上传文案改由插件注入（core 的 `head.html` 不再输出）；
- 编辑页对话框：core 的 `edit-toolbar.js` 只保留单文件流程；插件在对话框出现时增强它
  （文件框加 `multiple`、隐藏「文件名」输入框、在**捕获阶段**接管「上传并插入」按钮）。
  选中的文件数 = 1 时仍交给 core 的单文件流程（提示与插入位置保持一致）；
- 批量编排的顺序、状态文案（等待 / 上传中 / 已上传 / 失败 / 已提交审核）、失败不影响其它文件、
  完成后插入画廊等行为与迁移前一致；有文件失败或进入审核队列时保留对话框并提示成功 / 失败数。

## 与 MediaWiki 的差异

- MediaWiki 的 MsUpload 是**小工具 / Gadget**（要自己写定义页），这里是随站点分发的插件；
- 没有分块上传 / 断点续传（`UploadWizard` 的能力），单个文件仍受 `wikikernel.max-file-size-mb` 限制；
- 上传逐个串行（与迁移前一致），没有并发上传与取消队列。

> **功能开关**：`features.multi-upload`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
