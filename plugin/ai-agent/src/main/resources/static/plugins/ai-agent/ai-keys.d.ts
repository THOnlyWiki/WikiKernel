/**
 * TypeScript companion declaration for ai-keys.js.
 * AI 厂商密钥表单（「参数设置 → AI 代理」= 我的密钥；「特殊:AI代理?keys=1」= 站点密钥）： 每个厂商一行（.ai-key-row），「保存 / 清除」按钮 POST /api/ai-keys， 保存后即时生效（不用重启），响应只带尾号掩码；密钥框永远不回填真实密钥。 站点级开关「允许普通用户使用」走 POST /api/ai-keys/allow-normal。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
