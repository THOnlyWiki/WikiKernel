/**
 * TypeScript companion declaration for recent-changes.js.
 * 最近更改页交互（仿 MediaWiki）： - 过滤更改输入框：按文本过滤条目 - 动态更新：每 15 秒用 Ajax 拉取当前页面，把新条目插到最上面（淡黄高亮渐隐）， 不再整页刷新；标签页隐藏时暂停轮询，重新可见时立刻补一次 - 激活的过滤器：隐藏 / 显示过滤区（过滤器的移除全部走服务端链接，不用前端假删除）
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
