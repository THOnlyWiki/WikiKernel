/*
 * 页面预览（仿 MediaWiki 的 Popups / Page Previews 扩展）：
 * 桌面端把鼠标悬停在页面链接上约 0.5 秒，显示该页的摘要与缩略图浮层。
 *
 * - 数据：GET /api/page-preview?title=…（不存在 / 不可读返回 404，静默忽略并记入负缓存）；
 * - 开关：功能 features.popups（/init 或 特殊:权限配置 → 站点设置）；关闭时本脚本不会加载；
 * - 文案：浮层里的界面文字来自 window.WIKI_MESSAGES（服务端按当前语言注入，见 PopupsHeadProvider）；
 * - 只处理正文区（#bodyContent）里指向站内页面的链接：侧栏、个人工具、页签、搜索框、页脚等
 *   界面区域的链接不预览（与 MediaWiki Page Previews 一致）；红链（.new）、特殊页、
 *   带 ?action=/?oldid= 等的链接、以及带 .no-preview 类的链接都不会预览；窄屏（<720px，触摸为主）不启用；
 * - 键盘可达：Tab 聚焦链接同样会显示（focusin / focusout）。
 */
( function () {
    'use strict';

    if ( !window.WIKI_CONFIG || !window.WIKI_CONFIG.popups || !window.jQuery ) {
        return;
    }
    if ( window.innerWidth < 720 ) {
        return;
    }

    var $ = window.jQuery;
    var MESSAGES = window.WIKI_MESSAGES || {};
    var SHOW_DELAY = 500;
    var HIDE_DELAY = 200;
    var CACHE_LIMIT = 50;
    // 只绑定正文区（#bodyContent）：侧栏 / 个人工具 / 页签 / 页脚等界面链接不触发预览
    var LINK_SELECTOR = '#bodyContent a[href^="/wiki/"]';

    var cache = new Map();
    var $popup = null;
    var currentTitle = null;
    var showTimer = null;
    var hideTimer = null;

    function decodeTitle( href ) {
        var raw = href.replace( /^\/wiki\//, '' ).replace( /[?#].*$/, '' );
        try {
            return decodeURIComponent( raw ).replace( /_/g, ' ' );
        } catch ( error ) {
            return raw.replace( /_/g, ' ' );
        }
    }

    /** 返回可预览的标题；不可预览返回 null。 */
    function eligibleTitle( link ) {
        var href = link.getAttribute( 'href' ) || '';
        if ( href.indexOf( '/wiki/' ) !== 0 ) {
            return null;
        }
        var classes = typeof link.className === 'string' ? link.className : '';
        if ( classes.indexOf( 'new' ) !== -1 || classes.indexOf( 'no-preview' ) !== -1
            || classes.indexOf( 'mw-no-popups' ) !== -1 ) {
            return null; // 红链 / 显式排除
        }
        if ( /[?&](?:action|oldid|diff|veaction|printable|useskin)=/.test( href ) ) {
            return null;
        }
        if ( link.closest( '#mw-popup' ) ) {
            return null;
        }
        var title = decodeTitle( href );
        if ( !title || title.charAt( 0 ) === '#' || /^(?:特殊|Special)\s*:/i.test( title ) ) {
            return null;
        }
        return title;
    }

    function remember( title, value ) {
        if ( cache.size >= CACHE_LIMIT ) {
            cache.delete( cache.keys().next().value );
        }
        cache.set( title, value );
    }

    function ensurePopup() {
        if ( !$popup ) {
            $popup = $( '<div id="mw-popup" class="mw-popup" role="dialog" aria-hidden="true"></div>' )
                .on( 'mouseenter', function () {
                    window.clearTimeout( hideTimer );
                } )
                .on( 'mouseleave', function () {
                    scheduleHide();
                } )
                .appendTo( document.body );
        }
        return $popup;
    }

    function render( preview ) {
        var $card = ensurePopup().empty();
        $( '<div class="mw-popup-header">' )
            .append( $( '<a>' ).attr( 'href', preview.url ).text( preview.title ) )
            .appendTo( $card );
        var $body = $( '<div class="mw-popup-body">' ).appendTo( $card );
        if ( preview.thumbnail ) {
            $( '<img class="mw-popup-thumb">' )
                .attr( 'src', preview.thumbnail )
                .attr( 'alt', preview.thumbnailAlt || '' )
                .appendTo( $body );
        }
        $( '<div class="mw-popup-extract">' )
            .text( preview.extract || MESSAGES.popupsEmptyExtract || '' )
            .appendTo( $body );
        $( '<div class="mw-popup-footer">' )
            .append( $( '<a>' ).attr( 'href', preview.url )
                .text( MESSAGES.popupsGoToPage || '' ) )
            .appendTo( $card );
    }

    function position( link ) {
        var rect = link.getBoundingClientRect();
        $popup.css( { left: '0px', top: '0px', visibility: 'hidden' } ).addClass( 'mw-popup-visible' );
        var width = $popup.outerWidth();
        var height = $popup.outerHeight();
        var gap = 10;
        var left = Math.min( Math.max( 8, rect.left ), Math.max( 8, window.innerWidth - width - 8 ) );
        var top = rect.bottom + gap;
        if ( top + height > window.innerHeight - 8 ) {
            var above = rect.top - height - gap;
            top = above >= 8 ? above : Math.max( 8, window.innerHeight - height - 8 );
        }
        $popup.css( { left: left + 'px', top: top + 'px', visibility: 'visible' } );
    }

    function hide() {
        window.clearTimeout( showTimer );
        window.clearTimeout( hideTimer );
        currentTitle = null;
        if ( $popup ) {
            $popup.removeClass( 'mw-popup-visible' ).attr( 'aria-hidden', 'true' );
        }
    }

    function scheduleHide() {
        window.clearTimeout( hideTimer );
        hideTimer = window.setTimeout( hide, HIDE_DELAY );
    }

    function showFor( link ) {
        var title = eligibleTitle( link );
        if ( !title ) {
            return;
        }
        window.clearTimeout( showTimer );
        currentTitle = title;
        showTimer = window.setTimeout( function () {
            if ( currentTitle !== title ) {
                return;
            }
            var cached = cache.get( title );
            if ( cached === 'missing' ) {
                return;
            }
            if ( cached ) {
                render( cached );
                position( link );
                return;
            }
            $.getJSON( '/api/page-preview', { title: title } )
                .done( function ( result ) {
                    if ( result && result.code === 200 && result.data ) {
                        remember( title, result.data );
                        if ( currentTitle === title ) {
                            render( result.data );
                            position( link );
                        }
                    } else {
                        remember( title, 'missing' );
                    }
                } )
                .fail( function () {
                    remember( title, 'missing' ); // 页面不存在 / 不可读：负缓存，避免反复请求
                } );
        }, SHOW_DELAY );
    }

    $( document )
        .on( 'mouseenter', LINK_SELECTOR, function () {
            window.clearTimeout( hideTimer );
            showFor( this );
        } )
        .on( 'mouseleave', LINK_SELECTOR, function () {
            window.clearTimeout( showTimer );
            if ( currentTitle ) {
                scheduleHide();
            }
        } )
        .on( 'focusin', LINK_SELECTOR, function () {
            if ( this.tagName === 'A' ) {
                showFor( this );
            }
        } )
        .on( 'focusout', LINK_SELECTOR, function () {
            scheduleHide();
        } )
        .on( 'click', hide );

    $( window ).on( 'scroll', function () {
        if ( currentTitle ) {
            hide();
        }
    } );
    $( document ).on( 'keydown', function ( event ) {
        if ( event.key === 'Escape' ) {
            hide();
        }
    } );

    // 调试辅助（控制台可用）：mwPopups.inspect()
    window.mwPopups = {
        inspect: function () {
            var result = {};
            cache.forEach( function ( value, key ) {
                result[ key ] = value === 'missing' ? 'missing' : ( value.extract || '' ).slice( 0, 40 );
            } );
            return result;
        },
        clear: function () {
            cache.clear();
        }
    };
}() );
