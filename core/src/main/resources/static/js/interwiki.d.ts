/**
 * TypeScript companion declaration for interwiki.js.
 * 「特殊:跨Wiki」交互（仿 MediaWiki 的 Interwiki）： - #wiki-interwiki-form：新建 / 更新前缀 → POST /api/interwiki/save，成功后回到列表 - .interwiki-delete：删除前缀 → POST /api/interwiki/delete（先确认） - #wiki-interwiki-import-form：从文本导入 → POST /api/interwiki/import 三种操作都需要管理员 / 行政员，权限由服务端校验。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
