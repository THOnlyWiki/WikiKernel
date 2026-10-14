/**
 * TypeScript companion declaration for comments.js.
 * 评论区前端（页面含 comment 标签时由 view.html 渲染）： - 发表：拦截 .comment-form 提交到 POST /api/comments；成功后立即清空输入框， 用 Ajax 拉取当前页面并只替换评论区（不整页刷新，浏览器也就不会恢复输入内容），新评论高亮定位 - 回复：.comment-reply-link 设置表单 parentId 并显示「正在回复」 - 删除：.comment-delete-link 调 POST /api/comments/delete，同样只刷新评论区（保留已输入的草稿） 事件都委托在 document 上，替换评论区后无需重新绑定。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
