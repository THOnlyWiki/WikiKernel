/**
 * TypeScript companion declaration for edit-preview.js.
 * 编辑预览（仿 MediaWiki「显示预览」）： 点「显示预览」把编辑框内容 POST 到 /api/pages/preview 服务端渲染（不落盘）， 结果显示在编辑框上方（含目录折叠、脚注、分类栏、模板 / 微件等）； 未登录 / 无编辑权限时显示服务端返回的错误提示。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
