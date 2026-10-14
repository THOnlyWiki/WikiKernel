/**
 * TypeScript companion declaration for graph.js.
 * 图形（仿 MediaWiki Graph 扩展）：把 <graph> 标签 / 图表命名空间整页图的占位用 Vega 渲染。  - 绘图库本地内置（/vendor/vega/：vega + vega-lite + vega-embed），不走 CDN； 规范放在 <script type="application/json" class="mw-graph-spec"> 里（服务端只做转义与校验）； - Vega 规范与 Vega-Lite 规范都支持（vega-embed 会自动识别 $schema）； - 渲染失败时在占位里显示错误提示，不影响页面其余部分；脚本未加载时保留「需要 JavaScript」回退。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
