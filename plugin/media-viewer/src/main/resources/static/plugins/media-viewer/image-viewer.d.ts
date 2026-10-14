/**
 * TypeScript companion declaration for image-viewer.js.
 * 图片放大查看（仿 MediaWiki MediaViewer）： - 点击词条嵌入图片（[[文件:...]]，默认链到文件页时）/ 文件页大图 → 全屏深色查看器 - 缩放（按钮 / 双击 / 滚轮）、适应窗口、1:1、同一页面内上一张 / 下一张 - 键盘：Esc 关闭、← → 翻页、+ - 缩放、0 适应窗口、1 原始尺寸 - 下载菜单：原图，以及用 <canvas> 在浏览器端缩放到指定宽度的版本（服务器不做任何缩放） - 仅当启用「多媒体预览」（window.WIKI_CONFIG.mediaViewer）时生效； 自定义 link= 的图片保持原链接行为（与 MediaWiki 一致）
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/*
/ <reference path="./global.d.ts" />
*/
export {};
