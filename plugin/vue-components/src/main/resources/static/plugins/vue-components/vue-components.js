/*
 * Vue 组件加载器（ES 模块，随页面按需注入）：
 *
 *  1) 页面里的挂载点（解析标签 <test-component /> 渲染出的 div[data-vue-component]）；
 *  2) 拉组件源码：GET /wiki/<组件标题>?action=raw（组件 = *.vue 页面，标准 SFC）；
 *  3) 浏览器端拆块 + 编译：<template> 交给 Vue 运行时编译器，<script> 作为 ES 模块执行
 *     （export default 出组件定义），<style> 注入 <head>（按标题去重）；
 *  4) 挂载：props 来自 data-vue-props（":" 绑定的值会 JSON.parse），
 *     标签内容 = 默认插槽 —— 内层组件挂载点会递归转成真正的子组件 VNode（所以可嵌套）；
 *  5) 组件定义按标题缓存；动态插入的内容（预览 / 可视化编辑器）由 MutationObserver 自动挂载。
 *
 * 约定与限制（v1）：
 *  - <script> 里用 import 时请写绝对路径（如 mw.wikiImport('组件:工具.ts')）；Blob 模块没有相对基址；
 *  - 暂不支持 <script setup> 宏与 <style scoped>（后续可加）；
 *  - 组件脚本由「界面管理员」维护，页面正文里的数据全部按文本 / 属性传入（不会当代码执行）。
 */
import * as Vue from './vue.esm-browser.prod.js';

const { createApp, h, createStaticVNode } = Vue;

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
    const blocks = { template: null, script: null, styles: [] };
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
                } else if (type === 'script' && blocks.script === null) {
                    blocks.script = content;
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
        let definition = {};
        if (blocks.script && blocks.script.trim()) {
            const evaluated = await evaluateScript(blocks.script);
            if (evaluated && typeof evaluated === 'object') {
                definition = evaluated;
            }
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
