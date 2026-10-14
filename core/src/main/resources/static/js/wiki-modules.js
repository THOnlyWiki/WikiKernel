/*
 * 引用（import）用户编写的脚本页面：mw.wikiImport / mw.wikiPreload / mw.wikiModule
 *
 * 把 wiki 页面当成 ES 模块加载：
 *   const { formatDate } = await mw.wikiImport('用户:张三/工具.ts');       // .ts 由服务端编译
 *   mw.wikiImport('小部件:我的组件.js').then(function (ui) { ui.mount(); }); // 不需要 async
 *
 * 为什么不能「裸调用」同步拿结果：动态 import() 本质是异步的（要发请求、解析模块）。三条不用 async 的写法：
 *   1) .then(...) 回调（任何位置都能写，推荐给经典脚本）；
 *   2) 页面脚本声明成 <script type="module"> 后用**顶层 await**：const m = await mw.wikiImport('...');
 *   3) 先 mw.wikiPreload([...]) 预加载，之后在事件回调里用 mw.wikiModule('...') **同步**读取。
 *
 * 实现要点：
 *   - 模块 URL = /wiki/{标题}?action=raw&ctype=text/javascript&module=1
 *     （服务端对 .ts/.tsx 返回编译产物；带 ETag=页面版本 + no-cache，改动立即生效、未改动走 304；
 *      module=1 表示请求方是模块加载器，编译失败时服务端直接 500，而不是返回注释让模块静默变空）
 *   - 同一标题只加载一次（并发也共享同一个 Promise），并保证模块单例（ES 模块按 URL 缓存）
 *   - 标题里的 / 是子页面：逐段编码后保留斜杠（与站内 WikiUrls 一致）
 *   - 加载失败时抓回响应体（编译诊断 / 未启用说明）拼进 Error，便于定位
 */
(function () {
    'use strict';

    var mw = window.mw || (window.mw = {});

    /** 已加载模块：标题 → 命名空间。 */
    var registry = {};
    /** 正在加载：标题 → Promise（同标题并发只发一次请求）。 */
    var pending = {};

    function normalize(title) {
        return String(title === null || title === undefined ? '' : title)
            .replace(/\s+/g, ' ')
            .replace(/^ | $/g, '');
    }

    /** 页面标题 → 模块 URL（空格转下划线、逐段编码、保留子页面斜杠）。 */
    function moduleUrl(title) {
        var encoded = normalize(title).replace(/ /g, '_').split('/').map(function (segment) {
            return encodeURIComponent(segment);
        }).join('/');
        return '/wiki/' + encoded + '?action=raw&ctype=text/javascript&module=1';
    }

    /** 加载失败时把响应体（编译诊断等）读出来，拼成可读的错误。 */
    function describeFailure(title, url, cause) {
        return fetch(url, { headers: { 'Accept': 'text/plain, text/javascript;q=0.9, */*;q=0.8' } })
            .then(function (response) { return response.text(); })
            .then(function (body) {
                var detail = String(body || '').trim().slice(0, 1000);
                var error = new Error('mw.wikiImport：无法加载模块「' + title + '」' + (detail ? '：\n' + detail : '')
                    + (cause && cause.message ? '\n（' + cause.message + '）' : ''));
                error.wikiTitle = title;
                error.wikiUrl = url;
                throw error;
            }, function () {
                throw new Error('mw.wikiImport：无法加载模块「' + title + '」（' + (cause && cause.message ? cause.message : '未知错误') + '）');
            });
    }

    /**
     * 加载一个 wiki 页面模块。
     *
     * @param {string} title 页面标题，如 '用户:张三/工具.ts'（可省略后缀吗？不可以——标题就是页面名）
     * @returns {Promise<object>} 模块命名空间（同标题重复调用返回同一个实例）
     */
    function wikiImport(title) {
        var key = normalize(title);
        if (!key) {
            return Promise.reject(new Error('mw.wikiImport：标题不能为空'));
        }
        if (registry[key]) {
            return Promise.resolve(registry[key]);
        }
        if (pending[key]) {
            return pending[key];
        }
        var url = moduleUrl(key);
        var promise = import(url).then(function (namespace) {
            delete pending[key];
            registry[key] = namespace;
            var exported = namespace ? Object.keys(namespace) : [];
            if (exported.length === 0) {
                console.warn('mw.wikiImport：「' + key + '」没有 export，导入得到空模块；'
                    + '页面里用 export 导出想共享的函数 / 常量。');
            }
            return namespace;
        }, function (error) {
            delete pending[key];
            return describeFailure(key, url, error);
        });
        pending[key] = promise;
        return promise;
    }

    /** 并行加载多个模块；返回命名空间数组（顺序与入参一致）。 */
    function wikiImportAll(titles) {
        var list = Array.isArray(titles) ? titles : [titles];
        return Promise.all(list.map(wikiImport));
    }

    /**
     * 同步读取**已经加载好**的模块（没加载好返回 null 并告警）。
     * 典型用法：head 里尽早 mw.wikiPreload([...])，之后在事件回调里同步取。
     */
    function wikiModule(title) {
        var key = normalize(title);
        if (registry[key]) {
            return registry[key];
        }
        if (pending[key]) {
            console.warn('mw.wikiImport：「' + key + '」正在加载中，暂时取不到；'
                + '请用 mw.wikiPreload 提前加载，或改用 await / .then()。');
        } else {
            console.warn('mw.wikiImport：「' + key + '」还没有加载；先调用 mw.wikiImport / mw.wikiPreload。');
        }
        return null;
    }

    /** 已加载模块的快照（调试用）。 */
    function wikiModules() {
        var snapshot = {};
        Object.keys(registry).forEach(function (key) {
            snapshot[key] = registry[key];
        });
        return snapshot;
    }

    mw.wikiImport = wikiImport;
    mw.wikiImportAll = wikiImportAll;
    /** 预加载（语义化别名）：等价于 wikiImportAll，适合放在脚本顶部尽早触发请求。 */
    mw.wikiPreload = wikiImportAll;
    mw.wikiModule = wikiModule;
    mw.wikiModules = wikiModules;
    mw.wikiModuleUrl = moduleUrl;
})();
