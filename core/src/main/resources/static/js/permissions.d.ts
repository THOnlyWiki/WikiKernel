/**
 * TypeScript companion declaration for permissions.js.
 * 特殊:用户权限 / 用户页权限编辑器：勾选用户组后提交到 POST /api/user/permissions。 仿 MediaWiki：每个勾选的组下面显示「期限」下拉（不会过期 / 1天 / 1周 / 1个月 / 3个月 / 6个月 / 1年 / 其他时间）， 选「其他时间」再显示 datetime-local；表单底部可填「原因」。 只读模式（data-can-edit != "true"，即当前用户不是管理员/行政员）时脚本直接跳过。 提示文案来自服务端（AjaxResult.message），网络错误用 head.html 注入的 WIKI_MESSAGES。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
