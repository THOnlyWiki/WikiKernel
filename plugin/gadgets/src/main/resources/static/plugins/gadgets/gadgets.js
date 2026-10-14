/*
 * 参数设置 → 小工具（Gadgets 插件）：把勾选的小工具保存到 /api/gadgets/preferences
 * （{@code wiki_user.gadgets}；一个都没勾 = 明确全部关闭，存 "!"）。
 */
( function () {
    'use strict';

    // 提示文案由参数设置分区注入（GadgetsPreferenceSection → window.WIKI_MESSAGES）
    var MESSAGES = window.WIKI_MESSAGES || {};

    function ready( fn ) {
        if ( document.readyState === 'loading' ) {
            document.addEventListener( 'DOMContentLoaded', fn );
        } else {
            fn();
        }
    }

    ready( function () {
        var form = document.getElementById( 'gadgets-preferences-form' );
        if ( !form ) {
            return;
        }
        var message = form.querySelector( '.form-message' );
        function show( text, ok ) {
            if ( !message ) {
                return;
            }
            message.textContent = text;
            message.hidden = false;
            message.classList.toggle( 'error', !ok );
        }
        form.addEventListener( 'submit', function ( event ) {
            event.preventDefault();
            var params = new URLSearchParams();
            form.querySelectorAll( 'input[name="gadgets"]:checked' ).forEach( function ( input ) {
                params.append( 'gadgets', input.value );
            } );
            fetch( form.getAttribute( 'data-api' ) || '/api/gadgets/preferences', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
                body: params.toString(),
                credentials: 'same-origin'
            } ).then( function ( response ) {
                return response.json();
            } ).then( function ( result ) {
                show( result && result.message ? result.message : MESSAGES.gadgetsSaved, !result || result.code === 200 );
            } ).catch( function () {
                show( MESSAGES.networkError, false );
            } );
        } );
    } );
}() );
