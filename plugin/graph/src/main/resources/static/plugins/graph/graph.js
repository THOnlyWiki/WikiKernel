/*
 * 图形（仿 MediaWiki Graph 扩展，插件版）：把 <graph> 标签 / 图表命名空间整页图的占位用 Vega 渲染。
 *
 * - 绘图库本地内置（/vendor/vega/：vega + vega-lite + vega-embed），不走 CDN；
 *   规范放在 <script type="application/json" class="mw-graph-spec"> 里（服务端只做转义与校验）；
 * - Vega 规范与 Vega-Lite 规范都支持（vega-embed 会自动识别 $schema）；
 * - 渲染失败时在占位里显示错误提示，不影响页面其余部分；脚本未加载时保留「需要 JavaScript」回退。
 */
( function () {
    'use strict';

    if ( !window.WIKI_CONFIG || !window.WIKI_CONFIG.graph ) {
        return;
    }

    function errorText( error ) {
        var prefix = window.WIKI_CONFIG.graphRuntimeError || 'graph: render failed';
        var detail = error && error.message ? error.message : '';
        return prefix + detail;
    }

    function showError( plot, error ) {
        plot.innerHTML = '';
        var span = document.createElement( 'span' );
        span.className = 'wiki-error';
        span.textContent = errorText( error );
        plot.appendChild( span );
        if ( window.console && console.warn ) {
            console.warn( '[wiki] ' + errorText( error ) );
        }
    }

    function renderOne( figure ) {
        if ( figure.getAttribute( 'data-graph-ready' ) === '1' ) {
            return;
        }
        var specElement = figure.querySelector( 'script.mw-graph-spec' );
        var plot = figure.querySelector( '.mw-graph-plot' );
        if ( !specElement || !plot ) {
            return;
        }
        figure.setAttribute( 'data-graph-ready', '1' );

        var spec;
        try {
            spec = JSON.parse( specElement.textContent || '{}' );
        } catch ( error ) {
            showError( plot, error );
            return;
        }
        if ( !window.vegaEmbed ) {
            showError( plot, new Error( window.WIKI_CONFIG.graphVegaMissing || 'Vega is not available' ) );
            return;
        }
        plot.innerHTML = '';
        window.vegaEmbed( plot, spec, {
            actions: false,
            renderer: 'svg',
            config: { background: 'transparent' }
        } ).then( function ( result ) {
            if ( result && result.view && result.view.width ) {
                figure.setAttribute( 'data-graph-width', String( result.view.width() ) );
            }
        } ).catch( function ( error ) {
            showError( plot, error );
        } );
    }

    function initAll( root ) {
        var scope = root && root.querySelectorAll ? root : document;
        var figures = scope.querySelectorAll( 'figure.mw-graph' );
        Array.prototype.forEach.call( figures, renderOne );
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

    // 调试辅助：graphRender.init()
    window.graphRender = {
        init: function () {
            initAll( document );
        }
    };
}() );
