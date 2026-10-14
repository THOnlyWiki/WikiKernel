/*
 * Flash 播放器（Ruffle WASM）：把 <flash-player> 渲染的占位初始化成真正的播放器。
 *
 * - 懒加载：只有页面里存在 .flash-player 占位时才去加载 /vendor/ruffle/ruffle.js（随后 Ruffle 自己按需
 *   下载 wasm 核心，约 13MB；没有 Flash 内容的页面不会有任何开销）；
 * - 开关：功能 features.flash-player（/init 或 特殊:权限配置 → 站点设置）；关闭时本脚本不会加载，
 *   服务端也会把 <flash-player> 退化成文件链接；
 * - 参数：width / height / autoplay / loop / fullscreen / letterbox / menu / bgcolor / quality / scale /
 *   wmode / allowscriptaccess / unmute / flashvars / base / caption（详见 README 与演示页）；
 * - 文案：本地化的界面文字来自 window.WIKI_MESSAGES（服务端按当前语言注入，见 FlashPlayerHeadProvider）；
 * - 动态内容：监听 mw.hook('wikipage.content')，评论等异步插入的内容里出现占位也会初始化。
 */
( function () {
    'use strict';

    if ( !window.WIKI_CONFIG || !window.WIKI_CONFIG.flashPlayer ) {
        return;
    }

    var MESSAGES = window.WIKI_MESSAGES || {};
    var RUFFLE_URL = '/vendor/ruffle/ruffle.js';
    var loading = null;

    function ensureRuffle() {
        if ( window.RufflePlayer ) {
            return Promise.resolve( window.RufflePlayer );
        }
        if ( !loading ) {
            loading = new Promise( function ( resolve, reject ) {
                var script = document.createElement( 'script' );
                script.src = RUFFLE_URL;
                script.onload = function () {
                    if ( window.RufflePlayer ) {
                        resolve( window.RufflePlayer );
                    } else {
                        reject( new Error( MESSAGES.flashPlayerRuffleNotReady
                            || 'RufflePlayer failed to initialize' ) );
                    }
                };
                script.onerror = function () {
                    reject( new Error( ( MESSAGES.flashPlayerLoadFailed || 'Failed to load Ruffle: ' )
                        + RUFFLE_URL ) );
                };
                document.head.appendChild( script );
            } );
        }
        return loading;
    }

    /** 解析 flashvars="a=1&b=2" → { a: '1', b: '2' }（也支持 ; 分隔）。 */
    function parseFlashvars( text ) {
        var parameters = {};
        String( text || '' ).split( /[&;]+/ ).forEach( function ( pair ) {
            if ( !pair ) {
                return;
            }
            var equals = pair.indexOf( '=' );
            var key = decodeURIComponent( ( equals === -1 ? pair : pair.substring( 0, equals ) ).replace( /\+/g, ' ' ) );
            var value = equals === -1 ? '' : decodeURIComponent( pair.substring( equals + 1 ).replace( /\+/g, ' ' ) );
            if ( key ) {
                parameters[ key ] = value;
            }
        } );
        return parameters;
    }

    function boolAttr( element, name, fallback ) {
        var value = element.getAttribute( 'data-flash-' + name );
        if ( value === null ) {
            return fallback;
        }
        return value === 'true';
    }

    function loadOptions( container ) {
        var scale = ( container.getAttribute( 'data-flash-scale' ) || 'showall' ).toLowerCase();
        var scaleMap = { showall: 'showAll', noborder: 'noBorder', exactfit: 'exactFit', noscale: 'noScale' };
        var allow = ( container.getAttribute( 'data-flash-allowscriptaccess' ) || 'samedomain' ).toLowerCase();
        var allowMap = { samedomain: 'sameDomain', always: 'always', never: 'never' };
        var unmute = ( container.getAttribute( 'data-flash-unmute' ) || 'overlay' ).toLowerCase();
        var options = {
            url: container.getAttribute( 'data-flash-src' ),
            autoplay: boolAttr( container, 'autoplay', true ),
            loop: boolAttr( container, 'loop', false ),
            backgroundColor: container.getAttribute( 'data-flash-bgcolor' ) || '#000000',
            quality: container.getAttribute( 'data-flash-quality' ) || 'high',
            // Ruffle 的 scale / allowScriptAccess / letterbox / unmuteOverlay 都是字符串且区分大小写
            scale: scaleMap[ scale ] || 'showAll',
            allowScriptAccess: allowMap[ allow ] || 'sameDomain',
            letterbox: boolAttr( container, 'letterbox', true ) ? 'on' : 'off',
            unmuteOverlay: unmute === 'on' ? 'hidden' : 'visible',
            wmode: container.getAttribute( 'data-flash-wmode' ) || 'window',
            menu: boolAttr( container, 'menu', true ),
            fullscreenEnabled: boolAttr( container, 'fullscreen', true )
        };
        var flashvars = container.getAttribute( 'data-flash-flashvars' );
        if ( flashvars ) {
            options.parameters = parseFlashvars( flashvars );
        }
        var base = container.getAttribute( 'data-flash-base' );
        if ( base ) {
            options.base = base;
        }
        return options;
    }

    function errorText( container, message ) {
        var error = document.createElement( 'span' );
        error.className = 'wiki-error';
        error.textContent = ( MESSAGES.flashPlayerPlayFailed || 'Flash playback failed: ' ) + message;
        container.appendChild( error );
    }

    function initOne( container ) {
        if ( container.getAttribute( 'data-flash-ready' ) === '1' ) {
            return;
        }
        container.setAttribute( 'data-flash-ready', '1' );
        var width = parseInt( container.getAttribute( 'data-flash-width' ) || '550', 10 );
        var height = parseInt( container.getAttribute( 'data-flash-height' ) || '400', 10 );
        var options = loadOptions( container );

        ensureRuffle().then( function ( RufflePlayer ) {
            var ruffle = RufflePlayer.newest();
            var player = ruffle.createPlayer();
            player.style.width = width + 'px';
            player.style.height = height + 'px';
            player.style.maxWidth = '100%';
            container.appendChild( player );

            var play = function () {
                var overlay = container.querySelector( '.flash-player-play' );
                if ( overlay ) {
                    overlay.remove();
                }
                var promise = player.ruffle().load( options );
                if ( promise && typeof promise.catch === 'function' ) {
                    promise.catch( function ( error ) {
                        errorText( container, ( error && error.message ) || String( error ) );
                    } );
                }
            };

            if ( options.autoplay === false ) {
                // 需要点击才播放：先摆一个「点击播放」遮罩
                var overlay = document.createElement( 'button' );
                overlay.type = 'button';
                overlay.className = 'flash-player-play wiki-button';
                overlay.textContent = MESSAGES.flashPlayerClickToPlay || '▶ Click to play Flash';
                overlay.addEventListener( 'click', play );
                container.appendChild( overlay );
            } else {
                play();
            }
        } ).catch( function ( error ) {
            errorText( container, ( error && error.message ) || String( error ) );
        } );
    }

    function initAll( root ) {
        var scope = root && root.querySelectorAll ? root : document;
        Array.prototype.forEach.call( scope.querySelectorAll( '.flash-player[data-flash-player]' ), initOne );
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

    // 调试辅助：flashPlayer.init() / flashPlayer.ruffle()
    window.flashPlayer = {
        init: function () {
            initAll( document );
        },
        ruffle: function () {
            return window.RufflePlayer || null;
        }
    };
}() );
