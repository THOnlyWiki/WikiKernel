/*
 * 数学公式（仿 MediaWiki Math 扩展）：把 <math>/<chem> 生成的占位用 KaTeX 渲染成 HTML + MathML。
 *
 * - 懒加载：页面里存在 .mw-math 占位时才加载 /vendor/katex/（katex.min.css + katex.min.js，
 *   用到化学式时再加载 mhchem 扩展）；普通页面零开销；
 * - 服务端只做占位（TeX 放在 data-tex，标签内容不解析 wikitext，与 MediaWiki 一致）；
 * - KaTeX 渲染失败时保留 TeX 源码回退（throwOnError:false 时错误公式会以红色显示，与 MediaWiki 的错误提示类似）；
 * - 动态内容：监听 mw.hook('wikipage.content')，评论等异步插入的公式也会渲染。
 */
( function () {
    'use strict';

    // 站点级 + 词条阅读视图会各插入一次同一脚本（core 的 head 扩展现状）：
    // 重复执行会让两个副本各自加载 KaTeX（后加载的副本替换 window.katex），
    // 先渲染的公式会拿不到 mhchem 的 \ce 宏——这里只保留第一个副本。
    if ( window.WIKI_MATH_SCRIPT_LOADED ) {
        return;
    }
    window.WIKI_MATH_SCRIPT_LOADED = true;

    if ( !window.WIKI_CONFIG || !window.WIKI_CONFIG.math ) {
        return;
    }

    var CSS_URL = '/vendor/katex/katex.min.css';
    var JS_URL = '/vendor/katex/katex.min.js';
    var MHCHEM_URL = '/vendor/katex/contrib/mhchem.min.js';
    var loading = null;

    function ensureKatex( needChem ) {
        var ready = window.katex ? Promise.resolve( window.katex ) : null;
        if ( !ready ) {
            if ( !loading ) {
                loading = new Promise( function ( resolve, reject ) {
                    if ( !document.querySelector( 'link[href*="katex.min.css"]' ) ) {
                        var link = document.createElement( 'link' );
                        link.rel = 'stylesheet';
                        link.href = CSS_URL;
                        document.head.appendChild( link );
                    }
                    var script = document.createElement( 'script' );
                    script.src = JS_URL;
                    script.onload = function () {
                        if ( !window.katex ) {
                            reject( new Error( 'KaTeX 未初始化' ) );
                            return;
                        }
                        resolve( window.katex );
                    };
                    script.onerror = function () {
                        reject( new Error( '加载 ' + JS_URL + ' 失败' ) );
                    };
                    document.head.appendChild( script );
                } );
            }
            ready = loading;
        }
        if ( !needChem ) {
            return ready;
        }
        // 含 \ce{}（<chem>）时必须等 mhchem 真正加载完成再渲染，
        // 否则 KaTeX 会报 "Undefined control sequence: \ce"，而 data-math-ready 已置位就不再重试。
        return ready.then( function ( katex ) {
            return loadChem().then( function () {
                return katex;
            } );
        } );
    }

    /**
     * 加载 mhchem（化学式）。返回 Promise：**等脚本真正 onload 之后才 resolve**
     * （失败也 resolve，普通公式不受影响，只有 \ce{} 会报错）。
     * 结果缓存在 katex.__mhchemPromise，重复调用不会重复插入 <script>。
     */
    function loadChem() {
        if ( window.katex.__mhchemPromise ) {
            return window.katex.__mhchemPromise;
        }
        window.katex.__mhchemPromise = new Promise( function ( resolve ) {
            if ( window.katex.__mhchemLoaded ) {
                resolve();
                return;
            }
            var script = document.createElement( 'script' );
            script.src = MHCHEM_URL;
            script.onload = function () {
                window.katex.__mhchemLoaded = true;
                resolve();
            };
            script.onerror = function () { /* 忽略：普通公式不受影响 */
                resolve();
            };
            document.head.appendChild( script );
        } );
        return window.katex.__mhchemPromise;
    }

    function renderOne( element, katex ) {
        if ( element.getAttribute( 'data-math-ready' ) === '1' ) {
            return;
        }
        element.setAttribute( 'data-math-ready', '1' );
        var tex = element.getAttribute( 'data-tex' ) || '';
        if ( element.getAttribute( 'data-chem' ) === '1' ) {
            tex = '\\ce{' + tex + '}';
        }
        var target = document.createElement( 'span' );
        target.className = 'mw-math-render';
        element.appendChild( target );
        var fallback = element.querySelector( '.mw-math-fallback' );
        if ( fallback ) {
            fallback.style.display = 'none';
        }
        try {
            katex.render( tex, target, {
                displayMode: element.getAttribute( 'data-display' ) === 'block',
                throwOnError: false,
                errorColor: '#d33',
                output: 'htmlAndMathml',
                trust: false,
                strict: 'ignore'
            } );
            element.setAttribute( 'data-math-error',
                target.querySelector( '.katex-error' ) ? '1' : '0' );
        } catch ( error ) {
            if ( fallback ) {
                fallback.style.display = '';
            }
            element.setAttribute( 'data-math-error', '1' );
        }
    }

    function initAll( root ) {
        var scope = root && root.querySelectorAll ? root : document;
        var elements = scope.querySelectorAll( '.mw-math[data-tex]' );
        if ( !elements.length ) {
            return;
        }
        var needChem = Array.prototype.some.call( elements, function ( element ) {
            return element.getAttribute( 'data-chem' ) === '1';
        } );
        ensureKatex( needChem ).then( function ( katex ) {
            Array.prototype.forEach.call( elements, function ( element ) {
                renderOne( element, katex );
            } );
        } ).catch( function ( error ) {
            // 加载失败：保留 TeX 源码回退
            console.warn( '[wiki] 数学公式渲染不可用：' + ( ( error && error.message ) || error ) );
        } );
    }

    function start() {
        initAll( document );
        if ( window.mw && window.mw.hook ) {
            window.mw.hook( 'wikipage.content' ).add( function ( $content ) {
                initAll( $content && $content[ 0 ] ? $content[ 0 ] : document );
            } );
        }
    }

    if ( window.jQuery ) {
        window.jQuery( start );
    } else if ( document.readyState === 'loading' ) {
        document.addEventListener( 'DOMContentLoaded', start );
    } else {
        start();
    }

    // 调试辅助：mathRender.init() / mathRender.katex()
    window.mathRender = {
        init: function () {
            initAll( document );
        },
        katex: function () {
            return window.katex || null;
        }
    };
}() );
