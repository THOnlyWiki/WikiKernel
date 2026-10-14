/**
 * TypeScript companion declaration for visual-editor.js.
 * 可视化编辑器（编辑页，仿 MediaWiki 的可视化编辑体验）： - 只从编辑框里的 Wikitext 出发：POST /api/pages/visual/render （服务端先分词再渲染，并剥掉目录 / 分类栏等「后生成」内容） - 独占段落的模板直接渲染成「预览块」，点击打开模板对话框： 参数表单（服务端从模板源代码发现全部 {{{参数}}}）、源代码编辑、实时预览； 行内模板 / 引用 / 文件 / 复杂表格等显示为源码块，点击弹窗编辑源码 - 「应用更改」调用 /api/pages/visual/serialize 安全地序列化回 Wikitext； 遇到不支持的结构会整体拒绝（绝不写回半成品），仍可用源代码编辑
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
