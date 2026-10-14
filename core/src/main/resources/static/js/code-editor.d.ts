/**
 * TypeScript companion declaration for code-editor.js.
 * JS/CSS/HTML（微件）代码页面的预览与编辑器（CodeMirror 5，本地内置）。 - 预览：pre.wiki-code[data-language] → 只读高亮代码块（可选择 / 复制，光标隐藏；右上角有「复制」按钮） - 编辑：#wiki-edit-form textarea[data-language] → 代码编辑器（语法高亮 + 自动补全） 补全：JavaScript 用 CodeMirror 内置作用域提示；CSS 用常用属性列表；HTML/微件页用标签与属性列表； 输入字母/连字符时自动弹出，Ctrl+空格手动触发。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
