/**
 * TypeScript companion declaration for access-control.js.
 * 「特殊:权限配置」的交互： - form.access-rule-form：目标 + 动作 + 允许范围（所有人 / 无人 / 指定用户组）→ POST /api/access-rules - .access-rule-delete：删除规则 → POST /api/access-rules/delete 保存 / 删除成功后刷新页面。
 *
 * This file is a browser side-effect script (IIFE), not an importable API module.
 * Shared browser/WikiKernel globals are declared in global.d.ts.
 */
/// <reference path="./global.d.ts" />
export {};
