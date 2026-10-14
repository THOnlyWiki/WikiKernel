/**
 * TypeScript companion declaration for nuke.js.
 * 「特殊:大量删除」交互： - 全选复选框切换所有行 - 「删除所选页面」提交勾选的 titles；未勾选时也提交，由服务端返回 nuke.selectedNone 提示 - 「删除全部匹配页面」提交当前列出的所有 titles - 提交前用模板注入的 data-confirm-template 做 confirm()，成功后刷新
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
