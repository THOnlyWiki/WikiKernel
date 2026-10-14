/**
 * TypeScript companion declaration for js-compat.js.
 * JS 兼容性（mw.loader「未内置模块」的显式提示）： 1. 特殊:Gadgets：给声明了 dependencies= 的 Gadget 标出未内置的依赖（⚠ + 悬停说明）； 2. 任何页面里的 #js-compat-report 容器（演示页 / 微件用）：渲染模块提供情况表， 并现场演示 mw.loader.using() 未内置模块时的告警（捕获 console.warn 显示在页面上）。  未内置清单的唯一来源是 mw.loader.unsupported()（在 /js/mediawiki.js 里维护）， 本脚本不做分类判断，只负责展示。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
