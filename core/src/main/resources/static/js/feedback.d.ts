/**
 * TypeScript companion declaration for feedback.js.
 * 全局表单提示自动淡出： - 所有 .form-message 提示在显示后自动淡出：成功（.success）3 秒、其它（错误等）8 秒 - 淡出过渡 0.65s，结束后置 hidden；重新显示（改文字 / 去掉 hidden）会重新计时 - 用 MutationObserver 监听整个文档，所有脚本 / 服务端渲染的提示统一生效，无需逐个改脚本
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
