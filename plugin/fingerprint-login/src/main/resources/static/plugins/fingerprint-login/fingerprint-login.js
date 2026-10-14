/*
 * 浏览器指纹登录（插件 fingerprint-login，1.0.0）
 * ---------------------------------------------------------------------------
 * 一个文件承担两件事（脚本自己在 DOM 上找宿主，找不到就什么都不做）：
 *
 *   1) 登录页（特殊:登录 / Special:UserLogin）：
 *      在密码表单上插入「用浏览器指纹登录」按钮。点击 → 采集稳定分量 → 拼规范字符串 →
 *      crypto.subtle.digest('SHA-256') → 十六进制摘要 → POST /api/fingerprint-login。
 *      非安全上下文（http + 非 localhost）拿不到 crypto.subtle → 隐藏按钮并提示。
 *
 *   2) 管理界面（参数设置 → 指纹登录 分区 / 特殊:指纹登录）：
 *      容器 #fingerprint-login-panel 里列出自己的指纹（名称 / 创建时间 / 最近使用 / UA 摘要）、
 *      添加当前浏览器（填名称）、删除某个指纹；接口与登录页共用 /api/fingerprint-login/*。
 *
 * 指纹只在浏览器端计算，服务端不重算、不保存原始特征（只保存摘要与 UA 摘要）。
 * 分量都取自「同一台设备同一浏览器基本不变」的项，跨重启 / 跨隐身窗口保持稳定。
 */
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

    /** 当前是否安全上下文（crypto.subtle 只在安全上下文可用）。 */
    function cryptoAvailable() {
        return !!( window.crypto && window.crypto.subtle && window.crypto.subtle.digest );
    }

    function isLoginPage() {
        var path = decodeURIComponent( window.location.pathname );
        return path.indexOf( '/wiki/特殊:登录' ) === 0
            || path.indexOf( '/wiki/Special:UserLogin' ) === 0
            || path.indexOf( '/wiki/Special:Login' ) === 0;
    }

    /** JSON 解析（失败返回 null，不抛异常）。 */
    function parseJson( raw ) {
        try {
            return JSON.parse( raw );
        } catch ( error ) {
            return null;
        }
    }

    // ==================== 指纹采集 ====================

    /** 分量收集失败时用 '-' 占位，保证字符串长度稳定（不会因为某项缺失而整体错位）。 */
    function part( name, value ) {
        return name + '=' + ( value === undefined || value === null || value === '' ? '-' : String( value ) );
    }

    /** canvas 绘制结果（同一设备同一浏览器稳定；隐私插件可能返回噪声，那就只能靠其它分量）。 */
    function canvasSignature() {
        try {
            var canvas = document.createElement( 'canvas' );
            canvas.width = 240;
            canvas.height = 60;
            var context = canvas.getContext( '2d' );
            if ( !context ) {
                return '-';
            }
            // 固定文本 + 渐变 + 曲线 + 圆形：不同 GPU / 驱动 / 字体栈得到不同像素
            var gradient = context.createLinearGradient( 0, 0, 240, 60 );
            gradient.addColorStop( 0, '#f60' );
            gradient.addColorStop( 1, '#069' );
            context.fillStyle = gradient;
            context.fillRect( 0, 0, 240, 60 );
            context.font = '17px "Arial"';
            context.fillStyle = 'rgba(20,20,20,0.7)';
            context.fillText( 'WikiKernel fingerprint 指纹', 4, 22 );
            context.strokeStyle = 'rgba(255,255,255,0.6)';
            context.beginPath();
            context.arc( 180, 30, 18, 0, Math.PI * 2, true );
            context.stroke();
            context.beginPath();
            context.moveTo( 0, 50 );
            context.bezierCurveTo( 60, 10, 120, 55, 240, 15 );
            context.stroke();
            return canvas.toDataURL();
        } catch ( error ) {
            return '-';
        }
    }

    /** WebGL 未遮罩的厂商 / 渲染器（取不到就跳过，返回 '-'）。 */
    function webglSignature() {
        try {
            var canvas = document.createElement( 'canvas' );
            var gl = canvas.getContext( 'webgl' ) || canvas.getContext( 'experimental-webgl' );
            if ( !gl ) {
                return '-';
            }
            var info = gl.getExtension( 'WEBGL_debug_renderer_info' );
            if ( !info ) {
                return '-';
            }
            var vendor = gl.getParameter( info.UNMASKED_VENDOR_WEBGL );
            var renderer = gl.getParameter( info.UNMASKED_RENDERER_WEBGL );
            if ( !vendor && !renderer ) {
                return '-';
            }
            return String( vendor || '-' ) + ' / ' + String( renderer || '-' );
        } catch ( error ) {
            return '-';
        }
    }

    function timeZone() {
        try {
            return Intl.DateTimeFormat().resolvedOptions().timeZone || '-';
        } catch ( error ) {
            return '-';
        }
    }

    /**
     * 规范指纹字符串：一行一个分量，顺序固定。
     * 顺序 / 命名一旦改变，所有已登记的指纹都会失配——要改就当作一次「重新登记」。
     */
    function canonicalString() {
        var nav = window.navigator || {};
        var screen_ = window.screen || {};
        var languages = nav.languages && nav.languages.join ? nav.languages.join( ',' ) : '-';
        return [
            'v=1',
            part( 'ua', nav.userAgent ),
            part( 'lang', nav.language ),
            part( 'langs', languages ),
            part( 'platform', nav.platform ),
            part( 'cores', nav.hardwareConcurrency ),
            part( 'memory', nav.deviceMemory ),
            part( 'screen', screen_.width + 'x' + screen_.height ),
            part( 'colorDepth', screen_.colorDepth ),
            part( 'avail', screen_.availWidth + 'x' + screen_.availHeight ),
            part( 'tz', timeZone() ),
            part( 'canvas', canvasSignature() ),
            part( 'webgl', webglSignature() )
        ].join( '\n' );
    }

    /**
     * 计算指纹摘要：SHA-256(规范字符串) → 小写十六进制。
     * 返回 Promise<string>；不安全上下文 / 浏览器不支持时 reject。
     */
    function computeFingerprint() {
        if ( !cryptoAvailable() ) {
            return Promise.reject( new Error( 'crypto-unavailable' ) );
        }
        var encoder = new TextEncoder();
        var bytes = encoder.encode( canonicalString() );
        return window.crypto.subtle.digest( 'SHA-256', bytes ).then( function ( buffer ) {
            var view = new Uint8Array( buffer );
            var hex = '';
            for ( var i = 0; i < view.length; i++ ) {
                hex += ( '0' + view[ i ].toString( 16 ) ).slice( -2 );
            }
            return hex;
        } );
    }

    // ==================== HTTP ====================

    function postForm( url, params ) {
        return fetch( url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
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
        if ( !form && !document.querySelector( '.wiki-auth' ) ) {
            return;
        }
        var host = form || document.querySelector( '.wiki-auth' );
        if ( !host || !host.parentNode ) {
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

        if ( !cryptoAvailable() || ( CONFIG.fingerprintLoginHttpsOnly && !window.isSecureContext ) ) {
            // 不可用：隐藏按钮并提示（http + 非 localhost 拿不到 crypto.subtle）
            block.hidden = true;
            var notice = document.createElement( 'p' );
            notice.className = 'wiki-warning fingerprint-login-unavailable';
            notice.textContent = !cryptoAvailable()
                ? text( 'fingerprintLoginNotSupported', '' )
                : text( 'fingerprintHttpsOnly', '' );
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
            computeFingerprint().then( function ( hash ) {
                var params = new URLSearchParams();
                params.append( 'fingerprint', hash );
                params.append( 'remember', rememberBox.checked ? '1' : '0' );
                return postForm( LOGIN_API, params );
            } ).then( function ( result ) {
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

    /** 登录成功后的跳转：优先 ?returnto=（只接受站内路径），否则刷新回首页。 */
    function redirectAfterLogin() {
        var params = new URLSearchParams( window.location.search );
        var returnto = params.get( 'returnto' );
        if ( returnto && returnto.charAt( 0 ) === '/' && returnto.indexOf( '//' ) !== 0 ) {
            window.location.href = returnto;
            return;
        }
        window.location.reload();
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
        if ( CONFIG.fingerprintLoginHttpsOnly && !window.isSecureContext && httpsNotice ) {
            httpsNotice.hidden = false;
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
                computeFingerprint().then( function ( hash ) {
                    var params = new URLSearchParams();
                    params.append( 'fingerprint', hash );
                    params.append( 'label', label );
                    return postForm( ADD_API, params );
                } ).then( function ( result ) {
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
                } ).catch( function ( error ) {
                    addButton.disabled = false;
                    if ( error && error.message === 'crypto-unavailable' ) {
                        show( panelText( 'notSupported', '' ), false );
                        return;
                    }
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
