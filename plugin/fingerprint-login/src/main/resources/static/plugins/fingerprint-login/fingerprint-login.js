/* Saved browser login and credential management. */
( function () {
    'use strict';

    var CONFIG = window.WIKI_CONFIG || {};
    var MESSAGES = window.WIKI_MESSAGES || {};

    /** 登录接口路径。 */
    var LOGIN_API = '/api/fingerprint-login';
    /** 管理接口路径。 */
    var LIST_API = '/api/fingerprint-login/list';
    var ADD_API = '/api/fingerprint-login/add';
    var REMOVE_API = '/api/fingerprint-login/remove';

    /** 面板容器 / 配置输入框 id（与插件 Java 侧 FingerprintLoginPanel 保持一致）。 */
    var PANEL_ID = 'fingerprint-login-panel';
    var CONFIG_INPUT_ID = 'fingerprint-login-config';

    // ==================== 通用工具 ====================

    function ready( fn ) {
        if ( document.readyState === 'loading' ) {
            document.addEventListener( 'DOMContentLoaded', fn );
        } else {
            fn();
        }
    }

    function text( key, fallback ) {
        var value = MESSAGES[ key ];
        return value === undefined || value === null ? fallback : value;
    }

    /** 占位符替换：{0} / {1} … */
    function format( template, args ) {
        if ( !args || !args.length ) {
            return template;
        }
        var result = String( template );
        for ( var i = 0; i < args.length; i++ ) {
            result = result.split( '{' + i + '}' ).join( String( args[ i ] ) );
        }
        return result;
    }

    /** JSON 解析（失败返回 null，不抛异常）。 */
    function parseJson( raw ) {
        try {
            return JSON.parse( raw );
        } catch ( error ) {
            return null;
        }
    }

    // ==================== HTTP ====================

    function postForm( url, params ) {
        return fetch( url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' },
            body: params.toString(),
            credentials: 'same-origin'
        } ).then( function ( response ) {
            return response.json();
        } );
    }

    function readJson( url, options ) {
        return fetch( url, options ).then( function ( response ) {
            return response.json();
        } );
    }

    // ==================== 登录页 ====================

    function setupLoginPage() {
        var form = document.getElementById( 'wiki-login-form' );
        // 只增强真正的登录表单：注册页 / 其他页面也可能有 .wiki-auth，不能作为宿主。
        if ( !form || !form.parentNode ) {
            return;
        }
        var host = form;
        if ( !host.parentNode ) {
            return;
        }
        var block = document.createElement( 'div' );
        block.className = 'fingerprint-login-block';
        var button = document.createElement( 'button' );
        button.type = 'button';
        button.className = 'wiki-button fingerprint-login-button';
        button.textContent = text( 'fingerprintLoginButton', '用浏览器指纹登录' );
        button.title = text( 'fingerprintLoginHint', '' );
        var remember = document.createElement( 'label' );
        remember.className = 'fingerprint-login-remember';
        var rememberBox = document.createElement( 'input' );
        rememberBox.type = 'checkbox';
        rememberBox.checked = true;
        remember.appendChild( rememberBox );
        var rememberText = document.createElement( 'span' );
        rememberText.textContent = text( 'fingerprintLoginRemember', '' );
        remember.appendChild( rememberText );
        var message = document.createElement( 'p' );
        message.className = 'form-message fingerprint-login-message';
        message.setAttribute( 'role', 'alert' );
        message.hidden = true;

        block.appendChild( button );
        block.appendChild( remember );
        block.appendChild( message );
        // 插在密码表单后面（不修改 core 的登录模板）
        if ( form && form.parentNode ) {
            form.parentNode.insertBefore( block, form.nextSibling );
        } else {
            host.appendChild( block );
        }

        function show( content, ok ) {
            message.textContent = content;
            message.hidden = false;
            message.classList.toggle( 'error', !ok );
        }

        if ( !window.isSecureContext ) {
            block.hidden = true;
            var notice = document.createElement( 'p' );
            notice.className = 'wiki-warning fingerprint-login-unavailable';
            notice.textContent = text( 'fingerprintHttpsOnly', '' );
            if ( form && form.parentNode ) {
                form.parentNode.insertBefore( notice, block );
            } else {
                host.appendChild( notice );
            }
            return;
        }

        button.addEventListener( 'click', function () {
            button.disabled = true;
            show( text( 'fingerprintLoginWorking', '' ), true );
            var params = new URLSearchParams( { remember: rememberBox.checked ? '1' : '0' } );
            postForm( LOGIN_API, params ).then( function ( result ) {
                if ( result && result.code === 200 ) {
                    show( result.message || text( 'fingerprintLoginSuccess', '' ), true );
                    redirectAfterLogin();
                    return;
                }
                button.disabled = false;
                show( result && result.message ? result.message : text( 'fingerprintLoginError', '' ), false );
            } ).catch( function () {
                button.disabled = false;
                show( text( 'fingerprintLoginNetworkError', '' ), false );
            } );
        } );
    }

    /** 登录成功后的跳转：优先读登录表单的隐藏 returnto（密码登录同一来源），否则读查询串。 */
    function redirectAfterLogin() {
        var form = document.getElementById( 'wiki-login-form' );
        var input = form ? form.querySelector( 'input[name="returnto"]' ) : null;
        var returnto = input ? input.value : '';
        if ( !returnto ) {
            var params = new URLSearchParams( window.location.search );
            returnto = params.get( 'returnto' ) || '';
        }
        if ( returnto && returnto.charAt( 0 ) === '/' && returnto.indexOf( '//' ) !== 0 ) {
            window.location.assign( returnto );
            return;
        }
        // 没有来源页时回首页；不要 reload 登录页（登录页不会因为已登录自动离开）。
        window.location.assign( '/' );
    }

    // ==================== 管理界面 ====================

    function readPanelConfig() {
        var input = document.getElementById( CONFIG_INPUT_ID );
        return input ? parseJson( input.value ) || {} : {};
    }

    function setupPanel() {
        var panel = document.getElementById( PANEL_ID );
        if ( !panel ) {
            return;
        }
        var config = readPanelConfig();
        var messages = config.messages || {};
        function panelText( key, fallback ) {
            var value = messages[ key ];
            return value === undefined || value === null ? fallback : value;
        }

        var loggedIn = panel.getAttribute( 'data-logged-in' ) === 'true';
        var enabled = panel.getAttribute( 'data-enabled' ) === 'true';
        var max = parseInt( panel.getAttribute( 'data-max' ) || '10', 10 );
        var tbody = panel.querySelector( '.fingerprint-rows' );
        var labelInput = panel.querySelector( '.fingerprint-label' );
        var addButton = panel.querySelector( '.fingerprint-add' );
        var message = panel.querySelector( '.fingerprint-message' );
        var httpsNotice = panel.querySelector( '.fingerprint-https-only' );
        var loginNotice = panel.querySelector( '.fingerprint-login-required' );

        function show( content, ok ) {
            if ( !message ) {
                return;
            }
            message.textContent = content;
            message.hidden = false;
            message.classList.toggle( 'error', !ok );
        }

        if ( !enabled || !loggedIn ) {
            if ( addButton ) {
                addButton.disabled = true;
            }
            if ( !loggedIn && loginNotice ) {
                loginNotice.hidden = false;
            }
            return;
        }
        if ( !window.isSecureContext ) {
            if ( httpsNotice ) httpsNotice.hidden = false;
            if ( addButton ) addButton.disabled = true;
        }

        /** 渲染一行（用 DOM API，避免把用户输入当 HTML 拼）。 */
        function row( item ) {
            var tr = document.createElement( 'tr' );
            tr.className = 'fingerprint-row';
            tr.setAttribute( 'data-id', String( item.id ) );
            var label = document.createElement( 'td' );
            label.className = 'fingerprint-row-label';
            label.textContent = item.label || '';
            var created = document.createElement( 'td' );
            created.className = 'fingerprint-row-created';
            created.textContent = item.createdAtText || '';
            var used = document.createElement( 'td' );
            used.className = 'fingerprint-row-used';
            used.textContent = item.used ? ( item.lastUsedText || '' ) : panelText( 'never', '' );
            var agent = document.createElement( 'td' );
            agent.className = 'fingerprint-row-agent';
            agent.textContent = item.userAgent || '';
            var actions = document.createElement( 'td' );
            var remove = document.createElement( 'button' );
            remove.type = 'button';
            remove.className = 'wiki-button fingerprint-remove';
            remove.textContent = panelText( 'remove', '' );
            actions.appendChild( remove );
            tr.appendChild( label );
            tr.appendChild( created );
            tr.appendChild( used );
            tr.appendChild( agent );
            tr.appendChild( actions );
            return tr;
        }

        function render( items ) {
            if ( !tbody ) {
                return;
            }
            tbody.textContent = '';
            var rows = items || [];
            if ( !rows.length ) {
                var empty = document.createElement( 'tr' );
                empty.className = 'fingerprint-empty';
                var cell = document.createElement( 'td' );
                cell.colSpan = 5;
                cell.className = 'wiki-hint';
                cell.textContent = panelText( 'empty', '' );
                empty.appendChild( cell );
                tbody.appendChild( empty );
                return;
            }
            rows.forEach( function ( item ) {
                tbody.appendChild( row( item ) );
            } );
        }

        function refresh() {
            return readJson( LIST_API, { credentials: 'same-origin' } ).then( function ( result ) {
                if ( result && result.code === 200 && result.data ) {
                    render( result.data.fingerprints );
                }
            } ).catch( function () {
                // 列表拉取失败保持服务端渲染的内容（首屏可用）
            } );
        }

        panel.addEventListener( 'click', function ( event ) {
            var removeButton = event.target.closest ? event.target.closest( '.fingerprint-remove' ) : null;
            if ( !removeButton ) {
                return;
            }
            var tr = removeButton.closest( '.fingerprint-row' );
            var id = tr ? tr.getAttribute( 'data-id' ) : '';
            var name = tr && tr.querySelector( '.fingerprint-row-label' )
                ? tr.querySelector( '.fingerprint-row-label' ).textContent : '';
            if ( !window.confirm( format( panelText( 'removeConfirm', '' ), [ name ] ) ) ) {
                return;
            }
            removeButton.disabled = true;
            var params = new URLSearchParams();
            params.append( 'id', id );
            readJson( REMOVE_API + '?' + params.toString(), {
                method: 'DELETE',
                credentials: 'same-origin'
            } ).then( function ( result ) {
                if ( result && result.code === 200 ) {
                    render( result.data ? result.data.fingerprints : [] );
                    show( result.message || panelText( 'removeSuccess', '' ), true );
                    return;
                }
                removeButton.disabled = false;
                show( result && result.message ? result.message : panelText( 'error', '' ), false );
            } ).catch( function () {
                removeButton.disabled = false;
                show( panelText( 'networkError', '' ), false );
            } );
        } );

        if ( addButton ) {
            addButton.addEventListener( 'click', function () {
                var label = labelInput ? labelInput.value.trim() : '';
                if ( !label ) {
                    show( panelText( 'error', '' ), false );
                    if ( labelInput ) {
                        labelInput.focus();
                    }
                    return;
                }
                addButton.disabled = true;
                show( panelText( 'working', '' ), true );
                postForm( ADD_API, new URLSearchParams( { label: label } ) ).then( function ( result ) {
                    addButton.disabled = false;
                    if ( result && result.code === 200 ) {
                        if ( result.data ) {
                            render( result.data.fingerprints );
                        } else {
                            refresh();
                        }
                        if ( labelInput ) {
                            labelInput.value = '';
                        }
                        show( result.message || panelText( 'addSuccess', '' ), true );
                        return;
                    }
                    show( result && result.message ? result.message : panelText( 'error', '' ), false );
                } ).catch( function () {
                    addButton.disabled = false;
                    show( panelText( 'networkError', '' ), false );
                } );
            } );
        }

        if ( max > 0 ) {
            panel.setAttribute( 'data-limit', String( max ) );
        }
    }

    // ==================== 入口 ====================

    ready( function () {
        if ( !CONFIG.fingerprintLogin ) {
            return;
        }
        try {
            setupLoginPage();
        } catch ( error ) {
            // 登录页增强失败不影响密码登录
        }
        try {
            setupPanel();
        } catch ( error ) {
            // 管理界面增强失败不影响服务端渲染的内容
        }
    } );
}() );
