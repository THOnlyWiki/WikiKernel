/**
 * TypeScript companion declaration for ai-agent.js.
 * 「特殊:AI代理」交互： - #ai-agent-send：把输入框内容发给 POST /api/ai-agent/chat（可带 AI 厂商），渲染回复（Markdown）/ 步骤 / 待确认提案； - **多条目提案**：#ai-agent-proposal-list 列出本次会话的全部待确认提案（正文 + 它引用的模板等）， 可点条目切换审阅、「确认提交这个」逐个提交，或点「全部确认提交」一键提交（POST /api/ai-agent/apply-all）； - 刷新还原：GET /api/ai-agent/session 取回对话记录、步骤轨迹与全部待确认提案； - 提案面板：预览（服务端渲染）/ 差异 / Wikitext（**可直接改**）三个页签； - 所有动态文案由模板注入到
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
