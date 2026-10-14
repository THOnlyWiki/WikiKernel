/**
 * TypeScript companion declaration for file-upload.js.
 * 文件功能前端： - #file-upload-form：multipart 提交到 /api/files/upload，本地即时预览选中的图片 - .file-delete：POST /api/files/delete（勾选「彻底删除」时不进回收站） - .file-purge：POST /api/files/delete + hard=true（已删除文件列表里彻底删除） - .file-restore：POST /api/files/restore（恢复软删除文件） - .file-revert：POST /api/files/revert（回滚到历史版本）
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
