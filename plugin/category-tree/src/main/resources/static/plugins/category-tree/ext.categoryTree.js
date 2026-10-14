/*!
 * CategoryTree 前端（移植自 MediaWiki 扩展 CategoryTree 的 modules/ext.categoryTree/ext.categoryTree.js）。
 *
 * 与原版的差异：只使用原生 DOM API（站点全局加载了 jQuery，但这里不依赖），
 * 不依赖 mw.* / mw.Api / ResourceLoader；文案来自服务端内联的 window.CategoryTreeMessages，
 * 取不到时用本文件内置的英文兜底。AJAX 契约与 core 的 action=categorytree 一致：
 *   GET /api.php?action=categorytree&category=<分类名>&options=<data-ct-options>&formatversion=2
 *   → {"categorytree":{"html":"<子节点 HTML>"}}
 */
( function () {
    'use strict';

    /** 文案兜底（服务端未内联 window.CategoryTreeMessages 时使用）。 */
    var FALLBACK_MESSAGES = {
        expand: 'expand',
        collapse: 'collapse',
        loading: 'loading…',
        error: 'Problem loading data.',
        retry: 'Please wait a moment and try again.',
        'no-subcategories': 'no subcategories',
        'no-pages': 'no pages or subcategories',
        'no-parent-categories': 'no parent categories',
        'nothing-found': 'nothing found'
    };

    /** 找不到 .CategoryTreeTag 时的兜底选项（与服务端默认值一致）。 */
    var DEFAULT_OPTIONS = '{"mode":"categories","hideprefix":"categories","showcount":false,'
        + '"namespaces":false,"notranslations":false}';

    /** 取一条文案。 */
    function msg( key ) {
        var messages = window.CategoryTreeMessages;
        if ( messages && typeof messages[ key ] === 'string' ) {
            return messages[ key ];
        }
        return FALLBACK_MESSAGES[ key ] || key;
    }

    /** 节点对应的 .CategoryTreeChildren（必须是同一 .CategoryTreeSection 的直接子节点）。 */
    function childrenOf( link ) {
        var section = link.closest ? link.closest( '.CategoryTreeSection' ) : null;
        if ( !section ) {
            return null;
        }
        for ( var i = 0; i < section.children.length; i++ ) {
            var node = section.children[ i ];
            if ( node.nodeType === 1 && node.classList.contains( 'CategoryTreeChildren' ) ) {
                return node;
            }
        }
        return null;
    }

    /** 最近的 .CategoryTreeTag（提供 data-ct-options）。 */
    function optionsOf( link ) {
        var tag = link.closest ? link.closest( '.CategoryTreeTag' ) : null;
        var options = tag ? tag.getAttribute( 'data-ct-options' ) : null;
        return options || DEFAULT_OPTIONS;
    }

    /** 从选项 JSON 里取 mode（解析失败按 categories）。 */
    function modeOf( options ) {
        try {
            return JSON.parse( options ).mode || 'categories';
        } catch ( e ) {
            return 'categories';
        }
    }

    /** 空结果时按 mode 选提示（兼容 < 1.47 的数字模式）。 */
    function noticeKeyFor( mode ) {
        if ( mode === 'categories' || mode === 0 ) {
            return 'no-subcategories';
        }
        if ( mode === 'pages' || mode === 10 ) {
            return 'no-pages';
        }
        if ( mode === 'parents' || mode === 100 ) {
            return 'no-parent-categories';
        }
        return 'nothing-found';
    }

    function setExpanded( link, expanded ) {
        link.setAttribute( 'aria-expanded', expanded ? 'true' : 'false' );
        link.setAttribute( 'title', msg( expanded ? 'collapse' : 'expand' ) );
    }

    function expandNode( link ) {
        var children = childrenOf( link );
        if ( children ) {
            children.style.display = '';
        }
        setExpanded( link, true );
        if ( link.getAttribute( 'data-ct-loaded' ) === null ) {
            loadChildren( link, children );
        }
    }

    function collapseNode( link ) {
        var children = childrenOf( link );
        if ( children ) {
            children.style.display = 'none';
        }
        setExpanded( link, false );
    }

    function handleNode( link ) {
        if ( link.getAttribute( 'aria-expanded' ) === 'false' ) {
            expandNode( link );
        } else {
            collapseNode( link );
        }
    }

    /** 用一条斜体提示替换容器内容。 */
    function renderNotice( container, text ) {
        var notice = document.createElement( 'i' );
        notice.className = 'CategoryTreeNotice';
        notice.textContent = text;
        container.textContent = '';
        container.appendChild( notice );
    }

    /** 加载失败：显示错误文案 + 可点击 / 可键盘激活的「重试」。 */
    function showError( link, children ) {
        if ( !children ) {
            return;
        }
        children.textContent = '';
        children.appendChild( document.createTextNode( msg( 'error' ) + ' ' ) );
        var retry = document.createElement( 'a' );
        retry.setAttribute( 'role', 'button' );
        retry.setAttribute( 'tabindex', '0' );
        retry.textContent = msg( 'retry' );
        retry.addEventListener( 'click', function ( event ) {
            event.preventDefault();
            loadChildren( link, children );
        } );
        retry.addEventListener( 'keydown', function ( event ) {
            if ( event.key === 'Enter' || event.key === ' ' ) {
                event.preventDefault();
                loadChildren( link, children );
            }
        } );
        children.appendChild( retry );
    }

    /** AJAX 加载子节点（契约见文件头注释）。 */
    function loadChildren( link, children ) {
        if ( !children ) {
            return;
        }
        link.setAttribute( 'data-ct-loaded', '1' );
        renderNotice( children, msg( 'loading' ) );

        var title = link.getAttribute( 'data-ct-title' );
        if ( !title ) {
            // 没有标题就没法查询：直接报错（原版行为）
            showError( link, children );
            return;
        }
        var options = optionsOf( link );
        var url = '/api.php?action=categorytree'
            + '&category=' + encodeURIComponent( title )
            + '&options=' + encodeURIComponent( options )
            + '&formatversion=2';

        window.fetch( url, {
            headers: { Accept: 'application/json' },
            credentials: 'same-origin'
        } ).then( function ( response ) {
            if ( !response.ok ) {
                throw new Error( 'HTTP ' + response.status );
            }
            return response.json();
        } ).then( function ( data ) {
            if ( !data || data.error ) {
                throw new Error( data && data.error ? data.error.info : 'invalid response' );
            }
            var html = ( data.categorytree && data.categorytree.html ) || '';
            if ( html === '' ) {
                renderNotice( children, msg( noticeKeyFor( modeOf( options ) ) ) );
                return;
            }
            children.innerHTML = html;
            prepareToggles( children );
        } ).catch( function () {
            showError( link, children );
        } );
    }

    /** 给（新出现的）展开箭头补上 role / tabindex / title（原版在 attachHandler 里做同样的事）。 */
    function prepareToggles( root ) {
        if ( !root || !root.querySelectorAll ) {
            return;
        }
        var toggles = root.querySelectorAll( '.CategoryTreeToggle' );
        for ( var i = 0; i < toggles.length; i++ ) {
            var toggle = toggles[ i ];
            if ( toggle.getAttribute( 'role' ) === 'button' ) {
                continue;
            }
            toggle.setAttribute( 'role', 'button' );
            toggle.setAttribute( 'tabindex', '0' );
            toggle.setAttribute( 'title',
                msg( toggle.getAttribute( 'aria-expanded' ) === 'false' ? 'expand' : 'collapse' ) );
        }
    }

    /** 点击 / 键盘事件的公共入口：判断事件目标是不是展开箭头。 */
    function toggleFrom( target ) {
        return target && target.closest ? target.closest( '.CategoryTreeToggle' ) : null;
    }

    document.addEventListener( 'click', function ( event ) {
        var toggle = toggleFrom( event.target );
        if ( !toggle ) {
            return;
        }
        event.preventDefault();
        handleNode( toggle );
    } );

    document.addEventListener( 'keydown', function ( event ) {
        if ( event.key !== 'Enter' && event.key !== ' ' ) {
            return;
        }
        var toggle = toggleFrom( event.target );
        if ( !toggle ) {
            return;
        }
        event.preventDefault();
        handleNode( toggle );
    } );

    function init() {
        prepareToggles( document );
    }

    if ( document.readyState === 'loading' ) {
        document.addEventListener( 'DOMContentLoaded', init );
    } else {
        init();
    }
}() );
