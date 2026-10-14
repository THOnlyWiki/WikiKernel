/**
 * TypeScript companion declaration for edit-toolbar.js.
 * 编辑辅助工具栏（仿 MediaWiki WikiEditor 默认皮肤）： - 插入：粗体 / 斜体 / 内部链接 / 外部链接 / 参考文献 / 签名 / 标题 / 列表 / 缩进 / nowiki / 水平线 / 表格 - 特殊字符面板；Ctrl+B / Ctrl+I 快捷键 - 快速上传图片：弹窗选择文件 → POST /api/files/upload → 插入 [[文件:名称|thumb]] 所有插入都会触发 input 事件，编辑草稿保护（edit-draft.js）随之自动保存。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
