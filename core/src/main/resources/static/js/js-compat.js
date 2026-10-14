/*
 * JS 兼容性（mw.loader「未内置模块」的显式提示）：
 *  1. 特殊:Gadgets：给声明了 dependencies= 的 Gadget 标出未内置的依赖（⚠ + 悬停说明）；
 *  2. 任何页面里的 #js-compat-report 容器（演示页 / 微件用）：渲染模块提供情况表，
 *     并现场演示 mw.loader.using() 未内置模块时的告警（捕获 console.warn 显示在页面上）。
 *
 * 未内置清单的唯一来源是 mw.loader.unsupported()（在 /js/mediawiki.js 里维护），
 * 本脚本不做分类判断，只负责展示。
 */
( function () {
    'use strict';

    if ( !window.mw || !window.mw.loader ) {
        return;
    }
    var loader = window.mw.loader;

    function escapeHtml( text ) {
        var div = document.createElement( 'div' );
        div.textContent = text === null || text === undefined ? '' : String( text );
        return div.innerHTML;
    }

    /** 特殊:Gadgets：未内置的依赖标红并显示 ⚠。 */
    function markGadgetDependencies() {
        Array.prototype.forEach.call(
            document.querySelectorAll( '.gadget-dependencies .gadget-module' ),
            function ( span ) {
                var name = span.getAttribute( 'data-module' ) || '';
                var info = loader.getCompat ? loader.getCompat( name ) : null;
                if ( !info ) {
                    return;
                }
                span.classList.add( 'gadget-module-unsupported' );
                span.setAttribute( 'title', '未内置：' + info.category + ' — ' + info.hint );
                var cell = span.closest( 'td' ) || span.parentNode;
                var warning = cell ? cell.querySelector( '.gadget-module-missing' ) : null;
                if ( warning ) {
                    warning.hidden = false;
                    warning.setAttribute( 'title', info.category + '：' + info.hint );
                }
            }
        );
    }

    /** 报告容器：#js-compat-report（演示页/微件）。 */
    function renderReport() {
        var container = document.getElementById( 'js-compat-report' );
        if ( !container ) {
            return;
        }
        var provided = loader.provided ? loader.provided() : [];
        var unsupported = loader.unsupported ? loader.unsupported() : [];
        var html = [];
        html.push( '<p class="js-compat-provided"><b>本兼容层提供的模块</b>（' +
            provided.length + ' 个，<code>mw.loader.getState()</code> 为 <code>ready</code>）：<br><code>' +
            escapeHtml( provided.join( '、' ) ) + '</code></p>' );
        html.push( '<table class="wikitable js-compat-table"><thead><tr>' +
            '<th>未内置模块</th><th>分类</th><th>说明</th></tr></thead><tbody>' );
        unsupported.forEach( function ( item ) {
            html.push( '<tr><td><code>' + escapeHtml( item.name ) + '</code></td><td>' +
                escapeHtml( item.category ) + '</td><td>' + escapeHtml( item.hint ) + '</td></tr>' );
        } );
        html.push( '</tbody></table>' );
        html.push( '<div class="js-compat-live"><b>现场演示</b>：正在 <code>mw.loader.using()</code>…</div>' );
        container.innerHTML = html.join( '' );

        var live = container.querySelector( '.js-compat-live' );
        var demoModules = [ 'mediawiki.util', 'oojs-ui-core', 'jquery.ui', 'mediawiki.language' ];
        var captured = [];
        var originalWarn = window.console && console.warn;

        if ( originalWarn ) {
            console.warn = function () {
                captured.push( Array.prototype.slice.call( arguments ).join( ' ' ) );
                return originalWarn.apply( console, arguments );
            };
        }
        loader.using( demoModules ).always( function () {
            if ( originalWarn ) {
                console.warn = originalWarn;
            }
            var rows = demoModules.map( function ( name ) {
                return '<tr><td><code>' + escapeHtml( name ) + '</code></td><td><code>' +
                    escapeHtml( String( loader.getState( name ) ) ) + '</code></td></tr>';
            } ).join( '' );
            live.innerHTML = '<b>现场演示</b>：<code>mw.loader.using( [ ' +
                escapeHtml( demoModules.join( ', ' ) ) + ' ] )</code> 已完成（未内置模块解析为空模块，不阻塞）。' +
                '<table class="wikitable js-compat-table"><thead><tr><th>模块</th><th>getState()</th></tr></thead><tbody>' +
                rows + '</tbody></table>' +
                '<p><b>捕获到的告警</b>（控制台里也是这些，同一模块每次会话只提示一次）：</p>' +
                ( captured.length
                    ? '<pre class="js-compat-warnings">' + escapeHtml( captured.join( '\n' ) ) + '</pre>'
                    : '<p class="wiki-hint">（没有新告警：这些模块之前已经提示过了）</p>' );
        } );
    }

    markGadgetDependencies();
    renderReport();
}() );
