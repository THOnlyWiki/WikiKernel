/*
 * Vue 组件加载器（ES 模块，随页面按需注入）：
 *
 *  1) 页面里的挂载点（解析标签 <test-component /> 渲染出的 div[data-vue-component]）；
 *  2) 拉组件源码：GET /wiki/<组件标题>?action=raw（组件 = *.vue 页面，标准 SFC）；
 *  3) 浏览器端拆块 + 编译：<template> 交给 Vue 运行时编译器，<script> 作为 ES 模块执行
 *     （export default 出组件定义）；<script setup> 由轻量编译器转成 setup() 定义；
 *     <style> 注入 <head>（按标题去重）；
 *  4) 挂载：props 来自 data-vue-props（":" 绑定的值会 JSON.parse），
 *     标签内容 = 默认插槽 —— 内层组件挂载点会递归转成真正的子组件 VNode（所以可嵌套）；
 *  5) 组件定义按标题缓存；动态插入的内容（预览 / 可视化编辑器）由 MutationObserver 自动挂载。
 *
 * 约定与限制：
 *  - <script> 里用 import 时请写绝对路径（如 mw.wikiImport('组件:工具.ts')）；Blob 模块没有相对基址；
 *    从 'vue' 导入会自动改写为插件自带的 Vue ESM 地址；
 *  - <script setup> 支持 JavaScript（不支持 lang="ts" / 类型宏 / 顶层 await）；
 *  - 暂不支持 <style scoped>（后续可加）；
 *  - 组件脚本由「界面管理员」维护，页面正文里的数据全部按文本 / 属性传入（不会当代码执行）。
 */
import * as Vue from './vue.esm-browser.prod.js';

const { createApp, h, createStaticVNode } = Vue;
const VUE_MODULE_URL = new URL('./vue.esm-browser.prod.js', import.meta.url).href;

const ROOT_SELECTOR = '[data-vue-component]';
const MOUNTED_ATTR = 'data-vue-mounted';

/** 组件缓存：标题 → Promise<{definition}>（加载中） */
const componentCache = new Map();
/** 组件缓存：标题 → {definition}（已完成；同步建 VNode 用） */
const resolvedComponents = new Map();
/** 已注入的样式 id */
const injectedStyles = new Set();

/* ---------- 工具 ---------- */

function encodeTitle(title) {
    return String(title || '').replace(/ /g, '_').split('/').map(function (segment) {
        return encodeURIComponent(segment);
    }).join('/');
}

function escapeHtml(text) {
    return String(text == null ? '' : text)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/** SFC 分块：<template> 允许内部再嵌 <template>；<script> 取第一个；<style> 全部。 */
function splitBlocks(source) {
    const blocks = { template: null, scripts: [], styles: [] };
    const tagRe = /<\s*(\/?)\s*(template|script|style)\b([^>]*)>/gi;
    let type = null;
    let depth = 0;
    let start = -1;
    let attrs = '';
    let match;
    while ((match = tagRe.exec(source)) !== null) {
        const closing = match[1] === '/';
        const name = match[2].toLowerCase();
        if (!closing) {
            if (depth === 0) {
                type = name;
                depth = 1;
                start = tagRe.lastIndex;
                attrs = match[3] || '';
            } else if (name === type) {
                depth += 1;
            }
        } else if (type === name && depth > 0) {
            depth -= 1;
            if (depth === 0) {
                const content = source.slice(start, match.index);
                if (type === 'template' && blocks.template === null) {
                    blocks.template = content;
                } else if (type === 'script') {
                    blocks.scripts.push({ code: content, attrs: attrs });
                } else if (type === 'style') {
                    blocks.styles.push({ code: content, attrs: attrs });
                }
                type = null;
                start = -1;
            }
        }
    }
    return blocks;
}

/** <script> 内容作为 ES 模块执行（Blob URL 导入，模块缓存天然隔离）。 */
async function evaluateScript(code) {
    const blob = new Blob([code], { type: 'text/javascript' });
    const url = URL.createObjectURL(blob);
    try {
        const module = await import(url);
        return module && 'default' in module ? module.default : module;
    } finally {
        URL.revokeObjectURL(url);
    }
}

/* ---------- <script setup> 轻量编译 ---------- */

/** 把 `from 'vue'` 改写成当前插件自带的 Vue ESM 地址（Blob 模块没有相对基址）。 */
function rewriteVueImports(code, url) {
    return String(code || '').replace(/(\bfrom\s*|\bimport\s*)(['"])vue\2/g,
        function (all, prefix, quote) {
            return prefix + quote + url + quote;
        });
}

/** 从 setup 脚本中取出顶层 import（import 不能出现在 setup 函数体里）。 */
function extractImports(code) {
    const imports = [];
    let body = '';
    let i = 0;
    while (i < code.length) {
        if (code.startsWith('//', i)) {
            let end = code.indexOf('\n', i);
            end = end < 0 ? code.length : end;
            body += code.slice(i, end);
            i = end;
            continue;
        }
        if (code.startsWith('/*', i)) {
            let end = code.indexOf('*/', i + 2);
            end = end < 0 ? code.length : end + 2;
            body += code.slice(i, end);
            i = end;
            continue;
        }
        if (code.startsWith('import', i) && !/[\w$.]/.test(code[i - 1] || '')) {
            const rest = code.slice(i);
            let match = /^import\s+[\s\S]*?\s+from\s+(['"])[^'"]+\1\s*;?/.exec(rest);
            if (!match) {
                match = /^import\s+(['"])[^'"]+\1\s*;?/.exec(rest);
            }
            if (match) {
                imports.push(match[0]);
                i += match[0].length;
                continue;
            }
        }
        body += code[i++];
    }
    return { imports: imports, body: body };
}

/** 一条 import 语句里引入的本地名字（默认 / 命名 / 命名空间）。 */
function importNames(statement) {
    const names = [];
    const match = /^import\s+([\s\S]*?)\s+from\s+/.exec(statement);
    if (!match) {
        return names;
    }
    const clause = match[1].trim();
    const defaultName = /^([A-Za-z_$][\w$]*)\s*(?:,|$)/.exec(clause);
    if (defaultName) {
        names.push(defaultName[1]);
    }
    const namespace = /\*\s+as\s+([A-Za-z_$][\w$]*)/.exec(clause);
    if (namespace) {
        names.push(namespace[1]);
    }
    const named = /\{([\s\S]*?)\}/.exec(clause);
    if (named) {
        named[1].split(',').forEach(function (part) {
            let item = part.trim();
            if (!item || /^type\s/.test(item)) {
                return;
            }
            item = item.replace(/\s+as\s+/, ' ').split(/\s+/).pop();
            if (/^[A-Za-z_$][\w$]*$/.test(item)) {
                names.push(item);
            }
        });
    }
    return names;
}

function skipQuoted(code, start) {
    const quote = code[start];
    let i = start + 1;
    while (i < code.length) {
        if (code[i] === '\\') {
            i += 2;
            continue;
        }
        if (code[i] === quote) {
            return i + 1;
        }
        i++;
    }
    return code.length;
}

function matchingParen(code, open) {
    let depth = 0;
    for (let i = open; i < code.length; i++) {
        if (code.startsWith('//', i)) {
            const end = code.indexOf('\n', i);
            if (end < 0) {
                return -1;
            }
            i = end;
            continue;
        }
        if (code.startsWith('/*', i)) {
            const end = code.indexOf('*/', i + 2);
            if (end < 0) {
                return -1;
            }
            i = end + 1;
            continue;
        }
        const c = code[i];
        if (c === '"' || c === "'" || c === '`') {
            i = skipQuoted(code, i) - 1;
            continue;
        }
        if (c === '(') {
            depth++;
        } else if (c === ')') {
            depth--;
            if (depth === 0) {
                return i;
            }
        }
    }
    return -1;
}

function matchingAngle(code, open) {
    let depth = 0;
    for (let i = open; i < code.length; i++) {
        const c = code[i];
        if (c === '"' || c === "'") {
            i = skipQuoted(code, i) - 1;
            continue;
        }
        if (c === '<') {
            depth++;
        } else if (c === '>' && code[i - 1] !== '=') {
            depth--;
            if (depth === 0) {
                return i;
            }
        }
    }
    return -1;
}

/** 找 `defineProps(...)` / `defineEmits(...)` 等宏调用（支持 `defineProps<T>()`）。 */
function findCall(code, name) {
    const regex = new RegExp('\\b' + name + '\\b', 'g');
    let match;
    while ((match = regex.exec(code)) !== null) {
        let i = regex.lastIndex;
        while (i < code.length && /\s/.test(code[i])) {
            i++;
        }
        let typeArg = '';
        if (code[i] === '<') {
            const close = matchingAngle(code, i);
            if (close < 0) {
                continue;
            }
            typeArg = code.slice(i + 1, close);
            i = close + 1;
            while (i < code.length && /\s/.test(code[i])) {
                i++;
            }
        }
        if (code[i] !== '(') {
            continue;
        }
        const close = matchingParen(code, i);
        if (close < 0) {
            continue;
        }
        return { arg: code.slice(i + 1, close), typeArg: typeArg, start: match.index, open: i, close: close };
    }
    return null;
}

function splitTopLevel(text) {
    const parts = [];
    let start = 0;
    let braces = 0;
    let parens = 0;
    let brackets = 0;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (c === '"' || c === "'" || c === '`') {
            i = skipQuoted(text, i) - 1;
            continue;
        }
        if (c === '{') {
            braces++;
        } else if (c === '}') {
            braces = Math.max(0, braces - 1);
        } else if (c === '(') {
            parens++;
        } else if (c === ')') {
            parens = Math.max(0, parens - 1);
        } else if (c === '[') {
            brackets++;
        } else if (c === ']') {
            brackets = Math.max(0, brackets - 1);
        } else if (c === ',' && braces === 0 && parens === 0 && brackets === 0) {
            parts.push(text.slice(start, i));
            start = i + 1;
        }
    }
    parts.push(text.slice(start));
    return parts;
}

function propNamesFromExpression(expression) {
    const source = String(expression || '').trim();
    const names = [];
    if (source.startsWith('[')) {
        const end = source.lastIndexOf(']');
        const body = end > 0 ? source.slice(1, end) : source.slice(1);
        body.split(',').forEach(function (part) {
            const match = /['"]([^'"]+)['"]/.exec(part.trim());
            if (match) {
                names.push(match[1]);
            }
        });
    } else if (source.startsWith('{')) {
        const end = source.lastIndexOf('}');
        const body = end > 0 ? source.slice(1, end) : source.slice(1);
        splitTopLevel(body).forEach(function (part) {
            const item = part.trim();
            if (!item) {
                return;
            }
            const colon = item.indexOf(':');
            if (colon < 0) {
                return;
            }
            let key = item.slice(0, colon).trim();
            key = key.replace(/^['"]|['"]$/g, '');
            if (/^[A-Za-z_$][\w$]*$/.test(key)) {
                names.push(key);
            }
        });
    }
    return names;
}

function typeMemberNames(typeExpression) {
    const source = String(typeExpression || '').trim();
    const names = [];
    if (!source.startsWith('{')) {
        return names;
    }
    const end = source.lastIndexOf('}');
    const body = end > 0 ? source.slice(1, end) : source.slice(1);
    // 类型字面量通常用 ; 分隔，也允许逗号；嵌套数组/对象由 splitTopLevel 保持整体。
    splitTopLevel(body.replace(/;/g, ',')).forEach(function (part) {
        const item = part.trim();
        if (!item || item.startsWith('[') || item.startsWith('(')) {
            return;
        }
        const match = /^(?:['"]([^'"]+)['"]|([A-Za-z_$][\w$]*))\s*\??\s*:/.exec(item);
        if (match) {
            names.push(match[1] || match[2]);
        }
    });
    return names;
}

function addDeclaredNames(text, names) {
    const source = String(text || '').trim();
    if (!source) {
        return;
    }
    if (source.startsWith('{') || source.startsWith('[')) {
        const open = source[0];
        const close = open === '{' ? '}' : ']';
        const end = source.lastIndexOf(close);
        const body = end > 0 ? source.slice(1, end) : source.slice(1);
        splitTopLevel(body).forEach(function (part) {
            let item = part.trim();
            if (!item) {
                return;
            }
            const colon = item.indexOf(':');
            if (colon >= 0) {
                item = item.slice(colon + 1).trim();
            }
            const match = /^([A-Za-z_$][\w$]*)/.exec(item);
            if (match) {
                names.add(match[1]);
            }
        });
    } else {
        source.split(',').forEach(function (part) {
            const match = /^([A-Za-z_$][\w$]*)/.exec(part.trim());
            if (match) {
                names.add(match[1]);
            }
        });
    }
}

/** 收集 setup 顶层声明（只收顶层，避免把函数内部变量放进返回对象）。 */
function collectBindings(code) {
    const names = new Set();
    let brace = 0;
    let paren = 0;
    let bracket = 0;
    let i = 0;
    while (i < code.length) {
        if (code.startsWith('//', i)) {
            const end = code.indexOf('\n', i);
            i = end < 0 ? code.length : end;
            continue;
        }
        if (code.startsWith('/*', i)) {
            const end = code.indexOf('*/', i + 2);
            i = end < 0 ? code.length : end + 2;
            continue;
        }
        const c = code[i];
        if (c === '"' || c === "'" || c === '`') {
            i = skipQuoted(code, i);
            continue;
        }
        if (c === '{') {
            brace++;
            i++;
            continue;
        }
        if (c === '}') {
            brace = Math.max(0, brace - 1);
            i++;
            continue;
        }
        if (c === '(') {
            paren++;
            i++;
            continue;
        }
        if (c === ')') {
            paren = Math.max(0, paren - 1);
            i++;
            continue;
        }
        if (c === '[') {
            bracket++;
            i++;
            continue;
        }
        if (c === ']') {
            bracket = Math.max(0, bracket - 1);
            i++;
            continue;
        }
        if (brace === 0 && paren === 0 && bracket === 0) {
            const rest = code.slice(i);
            let match = /^(?:const|let|var)\s+/.exec(rest);
            if (match) {
                const start = i + match[0].length;
                let end = start;
                while (end < code.length && code[end] !== '=' && code[end] !== ';' && code[end] !== '\n') {
                    end++;
                }
                addDeclaredNames(code.slice(start, end), names);
                i = end;
                continue;
            }
            match = /^(?:async\s+)?function\s+([A-Za-z_$][\w$]*)/.exec(rest);
            if (match) {
                names.add(match[1]);
                i += match[0].length;
                continue;
            }
            match = /^class\s+([A-Za-z_$][\w$]*)/.exec(rest);
            if (match) {
                names.add(match[1]);
                i += match[0].length;
                continue;
            }
        }
        i++;
    }
    return names;
}

function stripMacroTypeArguments(code, name) {
    let result = code;
    let offset = 0;
    while (offset < result.length) {
        const call = findCall(result.slice(offset), name);
        if (!call) {
            break;
        }
        if (!call.typeArg) {
            offset += call.start + name.length;
            continue;
        }
        const start = offset + call.start + name.length;
        const end = offset + call.open;
        result = result.slice(0, start) + result.slice(end);
        offset = start;
    }
    return result;
}

/** 把 <script setup> 编译成普通组件定义（浏览器端近似编译器）。 */
function compileScriptSetup(code) {
    const rewritten = rewriteVueImports(code, VUE_MODULE_URL);
    const extracted = extractImports(rewritten);
    const body = extracted.body;
    let setupBody = body;
    ['defineProps', 'defineEmits', 'defineModel', 'defineSlots'].forEach(function (name) {
        setupBody = stripMacroTypeArguments(setupBody, name);
    });
    const imports = extracted.imports.slice();
    const macro = function (name) {
        const call = findCall(body, name);
        return call ? call.arg.trim() : '';
    };
    const propsCall = findCall(body, 'defineProps');
    const emitsCall = findCall(body, 'defineEmits');
    let propsExpression = propsCall ? propsCall.arg.trim() : '';
    let emitsExpression = emitsCall ? emitsCall.arg.trim() : '';
    const optionsExpression = macro('defineOptions') || '{}';
    let defaultsExpression = '';

    const withDefaults = findCall(body, 'withDefaults');
    let propsType = propsCall ? propsCall.typeArg.trim() : '';
    if (withDefaults) {
        const parts = splitTopLevel(withDefaults.arg);
        const inner = findCall(parts[0] || '', 'defineProps');
        if (inner) {
            propsExpression = inner.arg.trim();
            propsType = inner.typeArg.trim();
            defaultsExpression = parts.slice(1).join(',').trim();
        }
    }
    let emitsType = emitsCall ? emitsCall.typeArg.trim() : '';
    if (!propsExpression && propsType) {
        // defineProps<{ title?: string }>()：只生成运行时 prop 名，不做 TypeScript 类型校验
        const names = typeMemberNames(propsType);
        propsExpression = '{ ' + names.map(function (name) {
            return JSON.stringify(name) + ': {}';
        }).join(', ') + ' }';
    }
    if (!emitsExpression && emitsType) {
        const names = typeMemberNames(emitsType);
        emitsExpression = '[' + names.map(function (name) {
            return JSON.stringify(name);
        }).join(', ') + ']';
    }
    if (!propsExpression || propsExpression.startsWith('<')) {
        propsExpression = '{}';
    }
    if (!emitsExpression || emitsExpression.startsWith('<')) {
        emitsExpression = '[]';
    }
    const propsInit = defaultsExpression
        ? '__sfcWithDefaults(' + propsExpression + ', ' + defaultsExpression + ')'
        : propsExpression;
    const modelNames = [];
    const modelRegex = /\bdefineModel\s*(?:<[^>]*>)?\s*\(\s*(['"])([^'"]+)\1/g;
    let modelMatch;
    while ((modelMatch = modelRegex.exec(body)) !== null) {
        if (modelNames.indexOf(modelMatch[2]) < 0) {
            modelNames.push(modelMatch[2]);
        }
    }
    if (/\bdefineModel\s*(?:<[^>]*>)?\s*\(/.test(body) && modelNames.length === 0) {
        modelNames.push('modelValue');
    }
    let propsWithModel = propsInit;
    if (modelNames.length) {
        const extras = modelNames.map(function (name) {
            return JSON.stringify(name) + ': {}';
        }).join(', ');
        propsWithModel = 'Object.assign({}, ' + propsInit + ', { ' + extras + ' })';
    }
    let emitsWithModel = emitsExpression;
    if (modelNames.length) {
        const updateNames = modelNames.map(function (name) {
            return JSON.stringify('update:' + name);
        });
        if (emitsExpression.trim().startsWith('[')) {
            const inner = emitsExpression.trim().slice(1, -1).trim();
            emitsWithModel = '[' + (inner ? inner + ', ' : '') + updateNames.join(', ') + ']';
        } else if (emitsExpression.trim().startsWith('{')) {
            emitsWithModel = 'Object.assign({}, ' + emitsExpression + ', { '
                + updateNames.map(function (name) { return name + ': {}'; }).join(', ') + ' })';
        }
    }
    const propNames = propNamesFromExpression(propsExpression);
    modelNames.forEach(function (name) {
        if (propNames.indexOf(name) < 0) {
            propNames.push(name);
        }
    });
    const hasModel = modelNames.length > 0;

    const bindings = collectBindings(body);
    imports.forEach(function (statement) {
        importNames(statement).forEach(function (name) {
            bindings.add(name);
        });
    });
    const reserved = new Set([
        'defineProps', 'defineEmits', 'withDefaults', 'defineOptions', 'defineExpose',
        'defineSlots', 'defineModel', '__props', '__emit', '__expose'
    ]);
    const returnEntries = [];
    bindings.forEach(function (name) {
        if (!reserved.has(name)) {
            returnEntries.push(name + ': ' + name);
        }
    });
    propNames.forEach(function (name) {
        if (!bindings.has(name) && !reserved.has(name)) {
            returnEntries.push('get ' + JSON.stringify(name)
                + '() { return __props[' + JSON.stringify(name) + ']; }');
        }
    });
    if (!bindings.has('props') && propNames.length) {
        returnEntries.push('props: __props');
    }

    const modelImport = hasModel
        ? 'import { computed as __sfcComputed } from ' + JSON.stringify(VUE_MODULE_URL) + ';\n'
        : '';
    const modelMacro = hasModel
        ? '    const defineModel = function (name, options) {\n'
        + '      if (name && typeof name !== "string") { options = name; name = ""; }\n'
        + '      if (!name) { name = "modelValue"; }\n'
        + '      return __sfcComputed({\n'
        + '        get: function () { return __props[name]; },\n'
        + '        set: function (value) { __emit("update:" + name, value); }\n'
        + '      });\n'
        + '    };\n'
        : '';

    const generated = imports.join('\n') + '\n' + modelImport
        + 'function __sfcWithDefaults(props, defaults) {\n'
        + '  if (Array.isArray(props)) { return props; }\n'
        + '  const result = {};\n'
        + '  Object.keys(props).forEach(function (key) {\n'
        + '    const item = props[key];\n'
        + '    result[key] = typeof item === "function"\n'
        + '      ? { type: item, default: defaults[key] }\n'
        + '      : Object.assign({}, item, { default: defaults[key] });\n'
        + '  });\n'
        + '  return result;\n'
        + '}\n'
        + 'const __sfcProps = ' + propsWithModel + ';\n'
        + 'const __sfcEmits = ' + emitsWithModel + ';\n'
        + 'const __sfcOptions = ' + optionsExpression + ';\n'
        + 'export default Object.assign({}, __sfcOptions, {\n'
        + '  props: __sfcProps,\n'
        + '  emits: __sfcEmits,\n'
        + '  setup(__props, { expose: __expose, emit: __emit }) {\n'
        + '    const defineProps = function () { return __props; };\n'
        + '    const defineEmits = function () { return __emit; };\n'
        + '    const withDefaults = function (props) { return props; };\n'
        + '    const defineOptions = function (options) { return options; };\n'
        + '    const defineExpose = function (value) { __expose(value); };\n'
        + '    const defineSlots = function () { return {}; };\n'
        + modelMacro
        + setupBody + '\n'
        + '    return { ' + returnEntries.join(', ') + ' };\n'
        + '  }\n'
        + '});\n';
    return generated;
}

function injectStyle(key, css) {
    if (injectedStyles.has(key) || document.getElementById(key)) {
        injectedStyles.add(key);
        return;
    }
    const style = document.createElement('style');
    style.id = key;
    style.setAttribute('data-vue-component-style', '1');
    style.textContent = css;
    document.head.appendChild(style);
    injectedStyles.add(key);
}

function errorBox(key, message) {
    return '<div class="wiki-error wiki-vue-error">组件「' + escapeHtml(key) + '」：'
        + escapeHtml(message) + '</div>';
}

/* ---------- 加载 / 编译 ---------- */

function loadComponent(title) {
    if (resolvedComponents.has(title)) {
        return Promise.resolve(resolvedComponents.get(title));
    }
    if (componentCache.has(title)) {
        return componentCache.get(title);
    }
    const promise = fetch('/wiki/' + encodeTitle(title) + '?action=raw', {
        headers: { 'Accept': 'text/plain' },
        credentials: 'same-origin'
    }).then(function (response) {
        if (!response.ok) {
            throw new Error('无法读取页面（HTTP ' + response.status + '）');
        }
        return response.text();
    }).then(async function (source) {
        const blocks = splitBlocks(source);
        const scripts = blocks.scripts || [];
        const normalScript = scripts.find(function (item) {
            return !/\bsetup\b/i.test(item.attrs || '');
        });
        const setupScript = scripts.find(function (item) {
            return /\bsetup\b/i.test(item.attrs || '');
        });
        let definition = {};
        if (normalScript && normalScript.code.trim()) {
            const normalCode = rewriteVueImports(normalScript.code, VUE_MODULE_URL);
            const evaluated = await evaluateScript(normalCode);
            if (evaluated && typeof evaluated === 'object') {
                definition = evaluated;
            }
        }
        if (setupScript && setupScript.code.trim()) {
            if (/\blang\s*=\s*["']ts["']/i.test(setupScript.attrs || '')) {
                throw new Error('暂不支持 <script setup lang="ts">（浏览器端轻量编译器只支持 JavaScript）');
            }
            const setupDefinition = await evaluateScript(compileScriptSetup(setupScript.code));
            if (!setupDefinition || typeof setupDefinition !== 'object') {
                throw new Error('<script setup> 没有生成有效的组件定义');
            }
            definition = Object.assign({}, definition, setupDefinition);
        }
        if (blocks.template !== null) {
            definition.template = blocks.template;
        }
        if (!definition.template && !definition.render) {
            throw new Error('缺少 <template> 块（或在 <script> 里提供 render）');
        }
        blocks.styles.forEach(function (style, index) {
            injectStyle('vue-sfc-' + title + '-' + index, style.code);
        });
        const value = { definition: definition };
        resolvedComponents.set(title, value);
        return value;
    });
    componentCache.set(title, promise);
    return promise;
}

/* ---------- 挂载 ---------- */

function unwrapProps(raw) {
    const props = {};
    Object.keys(raw || {}).forEach(function (key) {
        const value = raw[key];
        if (value && typeof value === 'object' && typeof value.$json === 'string') {
            try {
                props[key] = JSON.parse(value.$json);
            } catch (error) {
                // 绑定属性必须是严格 JSON（如 :items='["甲","乙"]'）；写成 JS 单引号会退化成字符串
                console.warn('[vue-components] 绑定属性 ' + key + ' 不是合法 JSON，已按字符串处理：' + value.$json);
                props[key] = value.$json;
            }
        } else {
            props[key] = value;
        }
    });
    return props;
}

/** 静态 HTML 片段 → VNode（按节点数传参，避免把 {{ }} 当成模板表达式）。 */
function staticVNode(html) {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const count = doc.body.childNodes.length || 1;
    return createStaticVNode(html, count);
}

/** 插槽内容：内层组件挂载点 → 子组件 VNode，其余 DOM → 静态 VNode（顺序保留）。 */
function slotChildren(element) {
    const children = [];
    let html = '';
    const flush = function () {
        if (html.trim()) {
            children.push(staticVNode(html));
        }
        html = '';
    };
    Array.prototype.forEach.call(element.childNodes, function (node) {
        if (node.nodeType === 1 && node.hasAttribute && node.hasAttribute('data-vue-component')) {
            flush();
            children.push(componentVNode(node));
            return;
        }
        if (node.nodeType === 1) {
            html += node.outerHTML;
        } else if (node.nodeType === 3) {
            html += node.textContent;
        } else if (node.nodeType === 8) {
            html += '<!--' + node.textContent + '-->';
        }
    });
    flush();
    return children;
}

function componentVNode(element) {
    const title = element.getAttribute('data-vue-title') || element.getAttribute('data-vue-component');
    const resolved = resolvedComponents.get(title);
    if (!resolved) {
        throw new Error('组件未加载：' + title);
    }
    const props = unwrapProps(JSON.parse(element.getAttribute('data-vue-props') || '{}'));
    const children = slotChildren(element);
    return h(resolved.definition, props, children.length ? { default: function () { return children; } } : null);
}

function collectTitles(element, titles) {
    const own = element.getAttribute('data-vue-title') || element.getAttribute('data-vue-component');
    if (own) {
        titles.add(own);
    }
    Array.prototype.forEach.call(element.querySelectorAll(ROOT_SELECTOR), function (child) {
        const title = child.getAttribute('data-vue-title') || child.getAttribute('data-vue-component');
        if (title) {
            titles.add(title);
        }
    });
    return titles;
}

async function mountRoot(element) {
    if (element.hasAttribute(MOUNTED_ATTR)) {
        return;
    }
    element.setAttribute(MOUNTED_ATTR, '1');
    const key = element.getAttribute('data-vue-component');
    try {
        const titles = Array.from(collectTitles(element, new Set()));
        await Promise.all(titles.map(loadComponent));
        const vnode = componentVNode(element);
        createApp({ render: function () { return vnode; } }).mount(element);
    } catch (error) {
        console.error('[vue-components] 组件加载失败：' + key, error);
        element.innerHTML = errorBox(key, error && error.message ? error.message : '未知错误');
    }
}

/** 只挂最外层的挂载点（内层由父组件当插槽递归处理）。 */
function isRoot(element) {
    let parent = element.parentElement;
    while (parent) {
        if (parent.hasAttribute && parent.hasAttribute('data-vue-component')) {
            return false;
        }
        parent = parent.parentElement;
    }
    return true;
}

function mountAll(scope) {
    Array.prototype.forEach.call((scope || document).querySelectorAll(ROOT_SELECTOR), function (element) {
        if (!element.hasAttribute(MOUNTED_ATTR) && isRoot(element)) {
            mountRoot(element);
        }
    });
}

mountAll(document);

// 动态插入的内容（页面预览 / 可视化编辑器 / 插件注入）也自动挂载
if (window.MutationObserver) {
    new MutationObserver(function (mutations) {
        mutations.forEach(function (mutation) {
            Array.prototype.forEach.call(mutation.addedNodes, function (node) {
                if (node.nodeType !== 1) {
                    return;
                }
                if (node.hasAttribute && node.hasAttribute('data-vue-component')
                        && !node.hasAttribute(MOUNTED_ATTR) && isRoot(node)) {
                    mountRoot(node);
                    return;
                }
                mountAll(node);
            });
        });
    }).observe(document.body, { childList: true, subtree: true });
}
