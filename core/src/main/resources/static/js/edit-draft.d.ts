/**
 * TypeScript companion declaration for edit-draft.js.
 * 编辑页草稿保护（本地 localStorage，仿“手残救星”）： - 输入停顿 5 秒自动保存草稿；关闭 / 刷新 / 切后台前也会落盘； - 还有未保存修改时关闭页面 → 触发浏览器自带的离开确认弹窗（文案由浏览器决定，无法自定义）； - 下次打开同一页面自动恢复草稿，提示保存时间；页面在草稿之后被改过会额外给出冲突提示； - 保存成功（提交表单）后自动清除草稿，不再弹确认。 键：tempedit-cache-<页面标题>（段落编辑为 tempedit-cache-<页面标题>@s<段落号>） 值格式：JSON { v, t, rev, base, text }；“text” 原样存储（localStorage 原生支持 Unicode，无需 base64）。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
