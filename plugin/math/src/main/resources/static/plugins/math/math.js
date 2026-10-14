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

    if ( !window.WIKI_CONFIG || !window.WIKI_CONFIG.math ) {
        return;
    }

    var CSS_URL = '/vendor/katex/katex.min.css';
    var JS_URL = '/vendor/katex/katex.min.js';
    var MHCHEM_URL = '/vendor/katex/contrib/mhchem.min.js';
    var loading = null;

    function ensureKatex( needChem ) {
        if ( window.katex ) {
            if ( needChem && !window.katex.__mhchemLoaded ) {
                loadChem( window.katex.__mhchemLoadedResolver );
            }
            return Promise.resolve( window.katex );
        }
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
                    loadChem( resolve );
                };
                script.onerror = function () {
                    reject( new Error( '加载 ' + JS_URL + ' 失败' ) );
                };
                document.head.appendChild( script );
            } );
        }
        return loading;
    }

    /** 加载 mhchem（化学式）；失败也继续，只有 \ce{} 的公式会报错。 */
    function loadChem( resolve ) {
        if ( window.katex.__mhchemLoaded || window.katex.__mhchemLoading ) {
            if ( resolve ) {
                resolve( window.katex );
            }
            return;
        }
        window.katex.__mhchemLoading = true;
        var script = document.createElement( 'script' );
        script.src = MHCHEM_URL;
        script.onload = function () {
            window.katex.__mhchemLoaded = true;
        };
        script.onerror = function () { /* 忽略：普通公式不受影响 */ };
        document.head.appendChild( script );
        if ( resolve ) {
            resolve( window.katex );
        }
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
