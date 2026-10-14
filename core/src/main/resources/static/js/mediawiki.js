/*!
 * WikiKernel 的 MediaWiki JS API 兼容层：让 MediaWiki 风格的 Gadgets / 页面脚本直接跑在本维基上。
 *
 * 覆盖（按 MediaWiki 同名 API 实现）：
 *   mw.config（Map）、mw.messages / mw.message（Message）、mw.hook（Hooks）、
 *   mw.loader（register / implement / load / using / require / getState / getVersion / addStyleTag / state）、
 *   mw.util（getParamValue / getUrl / wikiUrl / wikiScript / wikiUrlencode / rawurlencode /
 *            addPortletLink / addPortlet / escapeIdForAttribute / escapeIdForLink / isIPAddress / $content …）、
 *   mw.user（getName / getId / isAnon / getGroups / getRights / tokens / sessionId）、mw.Api（get / post /
 *            getToken / postWithToken / abort …，与 /api.php 对接）、
 *   mw.Title / mw.Title.newFromText、mw.Uri、mw.html（create / element / escape）、mw.cookie、
 *   mw.storage（+ mw.storage.session）、mw.log（含 deprecate）、mw.errorLogger、mw.track / trackSubscribe、
 *   mw.notify、mw.requestIdleCallback / mw.requestAnimationFrame。
 *
 * 页面配置来自服务端注入的 window.RLCONF（见 web/MwConfigAdvice 与 head.html）；
 * window.RLQ 里的排队回调（function 或 [模块名, 回调]）会在本脚本加载后执行，与 MediaWiki 的启动队列一致。
 *
 * 与 MediaWiki 的已知差异（README 有详细说明）：
 *   - 没有 ResourceLoader 服务端：模块靠 mw.loader.register(...)（可带 url / scripts / styles / messages）声明；
 *     本兼容层已提供的能力注册为 ready；**已知未内置**的常见模块（oojs、oojs-ui-*、jquery.ui*、codex、vue、
 *     mediawiki.language 等）在 using/load 时给出明确告警（每个模块每次会话只提示一次），仍按空模块解析，不阻塞页面；
 *     mw.loader.unsupported() / getCompat(name) / missing() / provided() 可查询清单与运行时缺口；
 *     Gadget 定义里的 dependencies=…（含未内置模块时）会在页面加载时点名告警（见 特殊:Gadgets）；
 *   - mw.html#wikitext 不做服务端解析（按纯文本处理）；mw.message 缺失时返回键名（与 MediaWiki 一致）；
 *   - mw.Api 只走 /api.php（MediaWiki 兼容的 Action API）；mw.user.getRights() 暂返回空数组；
 *   - package 模块（module.exports）与 skipFunction、mw.loader.store 等高级特性未实现。
 */
( function () {
'use strict';

if ( window.mw && window.mw.loader ) {
    return; // 已加载
}

var config = new Map();
var messages = new Map();

// ==================== mw.config ====================
var RLCONF = window.RLCONF || {};
Object.keys( RLCONF ).forEach( function ( key ) {
    config.set( key, RLCONF[ key ] );
} );

// ==================== mw.messages / mw.Message ====================
var mwMessages = {
    set: function ( values ) {
        Object.keys( values ).forEach( function ( key ) {
            messages.set( key, values[ key ] );
        } );
    },
    get: function ( key ) {
        return messages.get( key );
    },
    exists: function ( key ) {
        return messages.has( key );
    }
};

function mwEscape( text ) {
    return String( text )
        .replace( /&/g, '&amp;' )
        .replace( /</g, '&lt;' )
        .replace( />/g, '&gt;' )
        .replace( /"/g, '&quot;' )
        .replace( /'/g, '&#039;' );
}

function mwMessageText( message ) {
    var key = message.key;
    var text = messages.has( key ) ? String( messages.get( key ) ) : key;
    var plain = [];
    var raw = [];
    ( message.parameters || [] ).forEach( function ( parameter ) {
        if ( parameter && parameter.raw !== undefined ) {
            raw.push( { index: raw.length, value: parameter.raw } );
        } else if ( parameter && parameter.plaintext !== undefined ) {
            raw.push( { index: raw.length, value: mwEscape( parameter.plaintext ) } );
        } else {
            raw.push( { index: raw.length, value: mwEscape( parameter ) } );
        }
        plain.push( parameter && parameter.raw !== undefined ? parameter.raw
            : ( parameter && parameter.plaintext !== undefined ? parameter.plaintext : parameter ) );
    } );
    var rendered = text.replace( /\$(\d+)/g, function ( all, number ) {
        var found = raw[ parseInt( number, 10 ) - 1 ];
        return found ? found.value : all;
    } );
    return { text: rendered, plain: text.replace( /\$(\d+)/g, function ( all, number ) {
        var found = plain[ parseInt( number, 10 ) - 1 ];
        return found === undefined ? all : String( found );
    } ) };
}

function Message( key, parameters, format ) {
    this.key = key;
    this.parameters = parameters || [];
    this.format = format || 'text';
}

Message.prototype.params = function () {
    this.parameters = this.parameters.concat( Array.prototype.slice.call( arguments ) );
    return this;
};

Message.prototype.rawParams = function () {
    var args = Array.prototype.slice.call( arguments );
    this.parameters = this.parameters.concat( args.map( function ( value ) {
        return { raw: value };
    } ) );
    return this;
};

Message.prototype.plaintextParams = function () {
    var args = Array.prototype.slice.call( arguments );
    this.parameters = this.parameters.concat( args.map( function ( value ) {
        return { plaintext: value };
    } ) );
    return this;
};

Message.prototype.exists = function () {
    return messages.has( this.key );
};

Message.prototype.text = function () {
    return mwMessageText( this ).text;
};

Message.prototype.plain = function () {
    return mwMessageText( this ).plain;
};

Message.prototype.parse = function () {
    return this.text(); // 无服务端解析：与 text 相同（差异见文件头）
};

Message.prototype.escaped = function () {
    return mwEscape( mwMessageText( this ).plain );
};

Message.prototype.toString = function () {
    return this.escaped();
};

Message.prototype.inLanguage = function () {
    return this;
};

function message( key ) {
    var parameters = Array.prototype.slice.call( arguments, 1 );
    var format = 'text';
    if ( parameters.length && typeof parameters[ parameters.length - 1 ] === 'string'
        && /^(text|parse|plain|escaped)$/.test( parameters[ parameters.length - 1 ] ) ) {
        format = parameters.pop();
    }
    return new Message( key, parameters, format );
}

// ==================== mw.hook ====================
var hooks = new Map();

function hook( name ) {
    if ( !hooks.has( name ) ) {
        var handlers = [];
        hooks.set( name, {
            name: name,
            handlers: handlers,
            add: function ( handler ) {
                if ( typeof handler === 'function' && handlers.indexOf( handler ) === -1 ) {
                    handlers.push( handler );
                }
                return this;
            },
            remove: function ( handler ) {
                var index = handlers.indexOf( handler );
                if ( index !== -1 ) {
                    handlers.splice( index, 1 );
                }
                return this;
            },
            fire: function ( data ) {
                var proceed = true;
                handlers.slice().forEach( function ( handler ) {
                    try {
                        if ( handler( data ) === false ) {
                            proceed = false;
                        }
                    } catch ( error ) {
                        logError( error, 'mw.hook(' + name + ')' );
                    }
                } );
                return proceed;
            }
        } );
    }
    return hooks.get( name );
}

// ==================== mw.log / mw.errorLogger / mw.track ====================
function consoleArgs( prefix, args ) {
    return [ prefix ].concat( Array.prototype.slice.call( args ) );
}

var log = {
    log: function () {
        if ( window.console && console.log ) {
            console.log.apply( console, consoleArgs( '[mw]', arguments ) );
        }
    },
    warn: function () {
        if ( window.console && console.warn ) {
            console.warn.apply( console, consoleArgs( '[mw]', arguments ) );
        }
    },
    error: function () {
        if ( window.console && console.error ) {
            console.error.apply( console, consoleArgs( '[mw]', arguments ) );
        }
    },
    /** mw.log.deprecate(obj, key, val, since, replacement)：访问时打一次弃用警告。 */
    deprecate: function ( obj, key, value, since, replacement ) {
        var warned = false;
        Object.defineProperty( obj, key, {
            configurable: true,
            get: function () {
                if ( !warned ) {
                    warned = true;
                    log.warn( 'Use of "' + key + '" is deprecated' + ( since ? ' since ' + since : '' )
                        + ( replacement ? '. Use ' + replacement + ' instead.' : '.' ) );
                }
                return value;
            },
            set: function ( next ) {
                value = next;
            }
        } );
        return obj;
    }
};

function logError( error, source, detail ) {
    if ( window.console && console.error ) {
        console.error( '[mw]', source || 'error', detail || '', error );
    }
    track( 'error', source || 'error', error && error.message ? error.message : String( error ) );
}

var trackHandlers = new Map();

function track( topic ) {
    var args = Array.prototype.slice.call( arguments, 1 );
    var topics = [ topic, topic.split( '.' )[ 0 ] + '.*', '*' ];
    topics.forEach( function ( pattern ) {
        ( trackHandlers.get( pattern ) || [] ).forEach( function ( handler ) {
            try {
                handler.apply( null, [ topic ].concat( args ) );
            } catch ( error ) {
                log.warn( 'track handler failed', error );
            }
        } );
    } );
}

function trackSubscribe( topic, handler ) {
    if ( !trackHandlers.has( topic ) ) {
        trackHandlers.set( topic, [] );
    }
    trackHandlers.get( topic ).push( handler );
}

// ==================== mw.loader ====================
var JQUERY_AVAILABLE = typeof window.jQuery !== 'undefined';
var modules = new Map();
var assetVersion = config.get( 'wgVersion' ) || '1';

/**
 * 已知「未内置」的模块表（本站没有 ResourceLoader 服务端）：写成清单是为了让 mw.loader 给出
 * **明确、可定位**的告警，而不是无声失效。名字以 * 结尾的按前缀匹配。
 * 表中没有列出的未注册模块仍会按通用提示告警（见 warnMissingModule）。
 * 后续若要真正兼容 OOUI：按 MediaWiki 模块名内置即可（README「JS 兼容性」一节有方案与体积）。
 */
var UNSUPPORTED_MODULES = {
    'oojs': { category: 'OOjs', hint: 'OO.* 类库（EventEmitter / Registry / Factory / 继承）' },
    'oojs-ui': { category: 'OOUI', hint: 'OOUI 总模块（ButtonWidget / Dialog / WindowManager…）' },
    'oojs-ui-core': { category: 'OOUI', hint: '控件核心（Widget / Layout / FieldLayout…）' },
    'oojs-ui-widgets': { category: 'OOUI', hint: '更多控件（Dropdown / ComboBox / SelectFile…）' },
    'oojs-ui-toolbars': { category: 'OOUI', hint: '工具栏（Toolbar / ToolGroup / Tool…）' },
    'oojs-ui-windows': { category: 'OOUI', hint: '窗口（WindowManager / Dialog / Process…）' },
    'oojs-ui-core.styles': { category: 'OOUI', hint: 'OOUI 核心样式（wikimediaui 主题）' },
    'oojs-ui-core.icons': { category: 'OOUI', hint: 'OOUI 核心图标' },
    'oojs-ui-widgets.styles': { category: 'OOUI', hint: 'OOUI 控件样式' },
    'oojs-ui-widgets.icons': { category: 'OOUI', hint: 'OOUI 控件图标' },
    'oojs-ui-toolbars.icons': { category: 'OOUI', hint: 'OOUI 工具栏图标' },
    'oojs-ui-windows.icons': { category: 'OOUI', hint: 'OOUI 窗口图标' },
    'oojs-ui.styles.indicators': { category: 'OOUI', hint: '指示器图标（tag / 状态点）' },
    'oojs-ui.styles.icons-*': { category: 'OOUI', hint: 'OOUI 图标包（MediaWiki 里按 18 个包懒加载）' },
    'jquery.ui': { category: 'jQuery UI', hint: 'MediaWiki 1.29 起已弃用（官方建议改用 Codex）；只有很老的 gadget 会用到' },
    'jquery.ui.*': { category: 'jQuery UI', hint: '按控件拆分的旧模块名（如 jquery.ui.dialog）' },
    'codex': { category: 'Codex（Vue）', hint: '现代 MediaWiki 的 UI 库（基于 Vue 3）' },
    'codex-search': { category: 'Codex（Vue）', hint: 'Codex 搜索组件' },
    '@wikimedia/codex': { category: 'Codex（Vue）', hint: '现代 MediaWiki 的 UI 库（基于 Vue 3）' },
    'vue': { category: 'Codex（Vue）', hint: 'Vue 3 运行时（Codex 依赖）' },
    'mediawiki.language': { category: 'MW 核心模块（未提供）', hint: '语言助手 mw.language（getDir / getFallbackLanguageChain…），OOUI 与部分 gadget 会依赖' },
    'mediawiki.widgets': { category: 'MW 核心模块（未提供）', hint: '基于 OOUI 的表单控件（DateInputWidget / TitleWidget…）' }
};

/** 查模块的「未内置」信息（精确名或前缀规则）；已提供 / 未知 → null。 */
function compatInfo( name ) {
    if ( Object.prototype.hasOwnProperty.call( UNSUPPORTED_MODULES, name ) ) {
        return UNSUPPORTED_MODULES[ name ];
    }
    var wildcards = Object.keys( UNSUPPORTED_MODULES ).filter( function ( key ) {
        return key.charAt( key.length - 1 ) === '*';
    } );
    for ( var i = 0; i < wildcards.length; i++ ) {
        var prefix = wildcards[ i ].slice( 0, -1 );
        if ( name.indexOf( prefix ) === 0 ) {
            return UNSUPPORTED_MODULES[ wildcards[ i ] ];
        }
    }
    return null;
}

var warnedMissing = Object.create( null );
var missingSeen = [];

/** 未注册模块只告警一次（含分类与提示），仍解析为空模块，避免页面/脚本被卡住。 */
function warnMissingModule( name ) {
    if ( warnedMissing[ name ] ) {
        return;
    }
    warnedMissing[ name ] = true;
    missingSeen.push( name );
    var info = compatInfo( name );
    log.warn( 'mw.loader：模块「' + name + '」本站未内置' +
        ( info ? '（' + info.category + ' — ' + info.hint + '）' : '（不在已知未内置清单里）' ) +
        '；依赖它的脚本会拿到空模块、可能不工作。清单与说明见「JS兼容性演示页」。' );
}

/**
 * 已启用 Gadget 在定义页声明的模块依赖（dependencies=…）：未内置的点名告警，
 * 方便从别处搬 Gadget 时立刻发现缺什么（本站没有 ResourceLoader 服务端，不会自动按依赖加载）。
 */
function warnGadgetDependencies() {
    var list = ( window.WIKI_CONFIG && window.WIKI_CONFIG.gadgetDependencies ) || [];
    list.forEach( function ( gadget ) {
        var missing = ( gadget.dependencies || [] ).filter( function ( name ) {
            return compatInfo( name );
        } );
        if ( missing.length ) {
            log.warn( 'mw.loader：Gadget「' + gadget.name + '」依赖未内置的模块：' + missing.join( '、' ) +
                '（该 Gadget 可能不工作；依赖清单见 特殊:Gadgets）' );
            missing.forEach( warnMissingModule );
        }
    } );
}

function deferred() {
    return JQUERY_AVAILABLE ? window.jQuery.Deferred()
        : ( function () {
            var resolveCallbacks = [];
            var rejectCallbacks = [];
            var state = 'pending';
            var promise = {
                then: function ( done, fail ) {
                    return promise.done( done ).fail( fail );
                },
                done: function ( callback ) {
                    if ( state === 'resolved' ) {
                        callback();
                    } else {
                        resolveCallbacks.push( callback );
                    }
                    return promise;
                },
                fail: function ( callback ) {
                    if ( state === 'rejected' ) {
                        callback();
                    } else {
                        rejectCallbacks.push( callback );
                    }
                    return promise;
                },
                always: function ( callback ) {
                    return promise.done( callback ).fail( callback );
                },
                promise: function () {
                    return promise;
                }
            };
            return {
                resolve: function () {
                    state = 'resolved';
                    resolveCallbacks.forEach( function ( callback ) {
                        callback();
                    } );
                },
                reject: function () {
                    state = 'rejected';
                    rejectCallbacks.forEach( function ( callback ) {
                        callback();
                    } );
                },
                promise: function () {
                    return promise;
                }
            };
        }() );
}

function moduleEntry( name ) {
    if ( !modules.has( name ) ) {
        modules.set( name, { name: name, state: 'missing', deps: [], scripts: [], styles: [], messages: null, pending: null } );
    }
    return modules.get( name );
}

function registerOne( entry ) {
    var record;
    if ( typeof entry === 'string' ) {
        record = { name: entry };
    } else if ( Array.isArray( entry ) ) {
        record = { name: entry[ 0 ], dependencies: entry[ 1 ] };
    } else {
        record = entry;
    }
    if ( !record || !record.name ) {
        return;
    }
    var module = moduleEntry( record.name );
    module.deps = record.dependencies || record.deps || [];
    module.url = record.url;
    module.style = record.style || record.css;
    module.scripts = record.scripts || [];
    module.styles = record.styles || [];
    if ( record.messages ) {
        module.messages = record.messages;
    }
    if ( module.state === 'missing' ) {
        // 与 MediaWiki 一致：register 之后是 registered（有实现 / URL 的等 using 时再加载）
        module.state = 'registered';
    }
}

function implement( name, scripts, styles, messageValues ) {
    var module = moduleEntry( name );
    module.scripts = scripts || [];
    module.styles = styles || [];
    module.messages = messageValues || null;
    module.state = 'implemented';
    if ( module.pending ) {
        var pending = module.pending;
        module.pending = null;
        executeModule( module );
        pending.resolve();
    }
}

function addStyleTag( css ) {
    var style = document.createElement( 'style' );
    style.textContent = css;
    ( document.head || document.documentElement ).appendChild( style );
    return style;
}

function executeModule( module ) {
    if ( module.messages ) {
        mwMessages.set( module.messages );
    }
    ( module.styles || [] ).forEach( function ( css ) {
        addStyleTag( typeof css === 'string' ? css : css.css );
    } );
    ( module.scripts || [] ).forEach( function ( script ) {
        var element = document.createElement( 'script' );
        element.textContent = script;
        ( document.head || document.documentElement ).appendChild( element );
    } );
    module.state = 'ready';
}

function ensureModule( name ) {
    var module = moduleEntry( name );
    if ( module.state === 'ready' || module.state === 'error' ) {
        return module.promise || ( module.promise = ( function () {
            var done = deferred();
            done.resolve();
            return done.promise();
        }() ) );
    }
    if ( module.state === 'loading' ) {
        return module.pending.promise();
    }
    if ( module.state === 'missing' ) {
        // 未注册模块：告警一次，解析为「空模块」（不阻塞）；状态保持 missing 便于诊断（getState / missing()）
        warnMissingModule( name );
        if ( !module.promise ) {
            var empty = deferred();
            empty.resolve();
            module.promise = empty.promise();
        }
        return module.promise;
    }
    // 依赖先加载
    var pending = deferred();
    module.pending = pending;
    module.state = 'loading';
    var deps = ( module.deps || [] ).map( function ( dep ) {
        return ensureModule( dep );
    } );
    ( JQUERY_AVAILABLE ? window.jQuery.when.apply( window.jQuery, deps ) : { done: function ( callback ) {
        callback();
    } } ).done( function () {
        if ( module.url ) {
            var element = document.createElement( 'script' );
            element.src = module.url + ( module.url.indexOf( '?' ) === -1 ? '?' : '&' ) + 'v=' + encodeURIComponent( assetVersion );
            element.onload = function () {
                if ( module.style ) {
                    var link = document.createElement( 'link' );
                    link.rel = 'stylesheet';
                    link.href = module.style;
                    ( document.head || document.documentElement ).appendChild( link );
                }
                module.state = 'ready';
                pending.resolve();
            };
            element.onerror = function () {
                log.error( 'mw.loader: 模块「' + name + '」脚本加载失败', module.url );
                module.state = 'error';
                pending.reject();
            };
            ( document.head || document.documentElement ).appendChild( element );
        } else {
            executeModule( module );
            pending.resolve();
        }
    } ).fail( function () {
        module.state = 'error';
        pending.reject();
    } );
    module.promise = pending.promise();
    return module.promise;
}

var loader = {
    /** 注册模块：[name, deps?, …] 或 { name, dependencies, url, style, scripts, styles, messages } */
    register: function ( moduleList ) {
        ( Array.isArray( moduleList ) ? moduleList : [ moduleList ] ).forEach( function ( entry ) {
            // 形如 [ [ 'a', ['b'] ], [ 'c' ] ] 的批量格式
            if ( Array.isArray( entry ) && Array.isArray( entry[ 0 ] ) ) {
                entry.forEach( registerOne );
            } else {
                registerOne( entry );
            }
        } );
    },
    /** 与 MediaWiki 的 RL 一致：服务端实现模块（scripts / styles / messages 直接下发）。 */
    implement: implement,
    load: function ( moduleList, type ) {
        var names = typeof moduleList === 'string' ? [ moduleList ] : moduleList;
        var promises = names.map( function ( name ) {
            var module = moduleEntry( name );
            if ( type === 'text/javascript' ) {
                module.styles = [];
            }
            return ensureModule( name );
        } );
        return JQUERY_AVAILABLE ? window.jQuery.when.apply( window.jQuery, promises ) : promises[ 0 ];
    },
    /** mw.loader.using(modules[, callback])：返回 jQuery Promise（MediaWiki 行为）。 */
    using: function ( moduleList, callback ) {
        var promise = loader.load( moduleList );
        if ( typeof callback === 'function' ) {
            promise.done( callback );
        }
        return promise;
    },
    /** mw.loader.require(module)：同步取模块导出（未实现 package 模块 → undefined）。 */
    require: function ( name ) {
        var module = modules.get( name );
        if ( !module || module.state !== 'ready' ) {
            log.warn( 'mw.loader.require: 模块「' + name + '」尚未加载' );
            return undefined;
        }
        return module.exports;
    },
    getState: function ( name ) {
        return moduleEntry( name ).state;
    },
    getVersion: function () {
        return assetVersion;
    },
    /** 已知「未内置」的模块清单（分类 + 说明；名字以 * 结尾表示前缀匹配）。 */
    unsupported: function () {
        return Object.keys( UNSUPPORTED_MODULES ).map( function ( name ) {
            return {
                name: name,
                category: UNSUPPORTED_MODULES[ name ].category,
                hint: UNSUPPORTED_MODULES[ name ].hint
            };
        } );
    },
    /** 单个模块的兼容信息：未内置 → { category, hint }；已提供 / 未知 → null。 */
    getCompat: compatInfo,
    /** 运行时实际遇到过（using/load 过）的未注册模块名（诊断用）。 */
    missing: function () {
        return missingSeen.slice();
    },
    /** 本兼容层提供的模块名（已注册为 ready）。 */
    provided: function () {
        return BUILTIN_MODULES.slice();
    },
    addStyleTag: addStyleTag,
    /** 兼容旧接口 mw.loader.state(obj)：批量把模块标成指定状态。 */
    state: function ( states ) {
        Object.keys( states ).forEach( function ( name ) {
            moduleEntry( name ).state = states[ name ];
        } );
    },
    /** 调试辅助：当前模块表。 */
    inspect: function () {
        var result = {};
        modules.forEach( function ( module, name ) {
            result[ name ] = { state: module.state, deps: module.deps, url: module.url };
        } );
        return result;
    }
};

// 内置模块：本兼容层已经全部提供，注册为 ready，让 using() 立刻解析
var BUILTIN_MODULES = [
    'mediawiki', 'jquery', 'mediawiki.util', 'mediawiki.api', 'mediawiki.Title', 'mediawiki.Uri',
    'mediawiki.user', 'mediawiki.cookie', 'mediawiki.storage', 'mediawiki.notify', 'mediawiki.notification',
    'mediawiki.html', 'mediawiki.hook', 'mediawiki.log', 'mediawiki.errorLogger', 'mediawiki.message',
    'mediawiki.page.ready', 'mediawiki.requestIdleCallback', 'mediawiki.track',
    'mediawiki.debug' // 置空以防脚本等待（真正调试面板未实现）
];
BUILTIN_MODULES.forEach( function ( name ) {
    moduleEntry( name ).state = 'ready';
} );

// 已启用 Gadget 的模块依赖：未内置的点名告警（诊断页/控制台可见，不影响页面渲染）
warnGadgetDependencies();

// ==================== mw.user ====================
var user = {
    getName: function () {
        return config.get( 'wgUserName' ) || null;
    },
    getId: function () {
        return config.get( 'wgUserId' ) || 0;
    },
    isAnon: function () {
        return !user.getName();
    },
    getGroups: function () {
        return config.get( 'wgUserGroups' ) || [];
    },
    getRights: function () {
        return config.get( 'wgUserRights' ) || [];
    },
    sessionId: function () {
        var key = 'mw.user.sessionId';
        var existing = storage.session.get( key );
        if ( !existing ) {
            existing = user.generateRandomSessionId();
            storage.session.set( key, existing );
        }
        return existing;
    },
    generateRandomSessionId: function () {
        var random = '';
        var chars = '0123456789abcdef';
        for ( var i = 0; i < 20; i++ ) {
            random += chars.charAt( Math.floor( Math.random() * chars.length ) );
        }
        return random;
    },
    tokens: ( function () {
        var tokens = new Map();
        var injected = config.get( 'wgUserTokens' ) || {};
        Object.keys( injected ).forEach( function ( key ) {
            tokens.set( key, injected[ key ] );
        } );
        return {
            get: function ( key ) {
                return tokens.get( key ) || null;
            },
            set: function ( key, value ) {
                if ( typeof key === 'object' && key !== null ) {
                    Object.keys( key ).forEach( function ( name ) {
                        tokens.set( name, key[ name ] );
                    } );
                } else {
                    tokens.set( key, value );
                }
            }
        };
    }() )
};

// ==================== mw.cookie ====================
var cookie = {
    prefix: function () {
        return config.get( 'wgCookiePrefix' ) || localStorageKeysPrefix();
    },
    get: function ( key ) {
        var name = cookie.prefix() + key;
        var match = document.cookie.match( new RegExp( '(?:^|; )' + name.replace( /([.*+?^${}()|[\]\\])/g, '\\$1' ) + '=([^;]*)' ) );
        return match ? decodeURIComponent( match[ 1 ] ) : null;
    },
    set: function ( key, value, options ) {
        options = options || {};
        var cookieString = cookie.prefix() + key + '=' + encodeURIComponent( value );
        if ( options.expires ) {
            var date = new Date();
            if ( typeof options.expires === 'number' ) {
                date.setTime( date.getTime() + options.expires * 86400000 );
            } else {
                date = options.expires;
            }
            cookieString += '; expires=' + date.toUTCString();
        }
        cookieString += '; path=' + ( options.path || '/' );
        if ( options.domain ) {
            cookieString += '; domain=' + options.domain;
        }
        if ( options.secure ) {
            cookieString += '; secure';
        }
        if ( options.sameSite ) {
            cookieString += '; SameSite=' + options.sameSite;
        }
        document.cookie = cookieString;
    },
    remove: function ( key, options ) {
        options = options || {};
        options.expires = new Date( 0 );
        cookie.set( key, '', options );
    }
};

function localStorageKeysPrefix() {
    return '';
}

// ==================== mw.storage ====================
function makeStorage( backing ) {
    var safe = backing;
    try {
        // iOS 无痕模式等：探测可用性
        backing.setItem( '__mw_test__', '1' );
        backing.removeItem( '__mw_test__' );
    } catch ( error ) {
        var memory = {};
        safe = {
            getItem: function ( key ) {
                return Object.prototype.hasOwnProperty.call( memory, key ) ? memory[ key ] : null;
            },
            setItem: function ( key, value ) {
                memory[ key ] = String( value );
            },
            removeItem: function ( key ) {
                delete memory[ key ];
            }
        };
    }
    return {
        get: function ( key ) {
            return safe.getItem( key );
        },
        set: function ( key, value ) {
            try {
                safe.setItem( key, String( value ) );
                return true;
            } catch ( error ) {
                return false;
            }
        },
        remove: function ( key ) {
            safe.removeItem( key );
        },
        getObject: function ( key ) {
            try {
                return JSON.parse( safe.getItem( key ) );
            } catch ( error ) {
                return null;
            }
        },
        setObject: function ( key, value ) {
            try {
                return this.set( key, JSON.stringify( value ) );
            } catch ( error ) {
                return false;
            }
        }
    };
}

var storage = ( function () {
    var local = makeStorage( window.localStorage );
    var session = makeStorage( window.sessionStorage );
    var combined = {
        get: local.get,
        set: local.set,
        remove: local.remove,
        getObject: local.getObject,
        setObject: local.setObject,
        session: session
    };
    return combined;
}() );

// ==================== mw.util ====================
function rawurlencode( value ) {
    return encodeURIComponent( String( value ) )
        .replace( /!/g, '%21' )
        .replace( /'/g, '%27' )
        .replace( /\(/g, '%28' )
        .replace( /\)/g, '%29' )
        .replace( /\*/g, '%2A' );
}

function wikiScript( script ) {
    if ( script === 'api' ) {
        return ( config.get( 'wgScriptPath' ) || '' ) + '/api.php';
    }
    if ( script === 'index' ) {
        return config.get( 'wgScript' ) || '/wiki/';
    }
    return ( config.get( 'wgScriptPath' ) || '' ) + '/' + ( script || '' );
}

function articlePathOf( title ) {
    var path = config.get( 'wgArticlePath' ) || '/wiki/$1';
    return path.replace( '$1', rawurlencode( String( title ).replace( / /g, '_' ) ).replace( /%2F/g, '/' ) );
}

function wikiUrl( title, params ) {
    var url = articlePathOf( title );
    if ( params ) {
        var query = config.get( 'wgServer' ) ? '' : '';
        Object.keys( params ).forEach( function ( key ) {
            query += ( query === '' ? ( url.indexOf( '?' ) === -1 ? '?' : '&' ) : '&' )
                + rawurlencode( key ) + '=' + rawurlencode( params[ key ] );
        } );
        url += query;
    }
    return url;
}

function getParamValue( paramName, url ) {
    var value = null;
    var source = url || ( window.location && window.location.href ) || '';
    var queryIndex = source.indexOf( '?' );
    if ( queryIndex !== -1 ) {
        source.substring( queryIndex + 1 ).split( '&' ).forEach( function ( pair ) {
            if ( value !== null ) {
                return;
            }
            var equals = pair.indexOf( '=' );
            var key = equals === -1 ? pair : pair.substring( 0, equals );
            if ( decodeURIComponent( key.replace( /\+/g, ' ' ) ) === paramName ) {
                value = decodeURIComponent( ( equals === -1 ? '' : pair.substring( equals + 1 ) ).replace( /\+/g, ' ' ) );
            }
        } );
    }
    return value;
}

var util = {
    $content: function () {
        return JQUERY_AVAILABLE ? window.jQuery( '#bodyContent' ) : null;
    },
    wikiScript: wikiScript,
    wikiUrl: wikiUrl,
    getUrl: function ( title, params ) {
        if ( typeof title === 'string' && title.charAt( 0 ) === '/' ) {
            // 相对路径：基于当前标题
            var current = config.get( 'wgPageName' ) || '';
            var base = current.indexOf( '/' ) === -1 ? current : current.substring( 0, current.indexOf( '/' ) );
            title = base + title;
        }
        return wikiUrl( ( title || config.get( 'wgPageName' ) || '' ).replace( /_/g, ' ' ), params );
    },
    getParamValue: getParamValue,
    rawurlencode: rawurlencode,
    wikiUrlencode: rawurlencode,
    escapeIdForAttribute: function ( id ) {
        return encodeURIComponent( String( id ) ).replace( /'/g, '%27' );
    },
    escapeIdForLink: function ( id ) {
        return encodeURIComponent( String( id ) ).replace( /'/g, '%27' );
    },
    isIPv4Address: function ( value ) {
        var parts = String( value ).split( '.' );
        return parts.length === 4 && parts.every( function ( part ) {
            return /^\d{1,3}$/.test( part ) && Number( part ) <= 255;
        } );
    },
    isIPv6Address: function ( value ) {
        return /^[0-9A-Fa-f:]{2,39}$/.test( String( value ) ) && String( value ).indexOf( ':' ) !== -1;
    },
    isIPAddress: function ( value ) {
        return util.isIPv4Address( value ) || util.isIPv6Address( value );
    },
    /** 往 portlet（皮肤里的 ul 容器）加一个链接；容器不存在时尝试常见 id，再不行新建到侧栏。 */
    addPortletLink: function ( portletId, href, text, id, tooltip, accessKey, nextNode ) {
        if ( !JQUERY_AVAILABLE ) {
            return null;
        }
        var $ = window.jQuery;
        var candidates = [
            '#' + portletId, '[data-portlet="' + portletId + '"]',
            '#' + portletId.replace( /^p-/, '' ), '[data-portlet="' + portletId.replace( /^p-/, '' ) + '"]'
        ];
        var $portlet = $();
        candidates.forEach( function ( selector ) {
            if ( !$portlet.length ) {
                try {
                    $portlet = $( selector );
                } catch ( error ) {
                    $portlet = $();
                }
            }
        } );
        if ( !$portlet.length ) {
            // 侧栏兜底：新建一个 portal 分组
            var $panel = $( '#wiki-panel' );
            if ( !$panel.length ) {
                return null;
            }
            $portlet = $( '<div>' ).addClass( 'portal' ).attr( 'id', portletId )
                .append( $( '<h3>' ).text( portletId.replace( /^p-/, '' ) ) )
                .append( $( '<ul>' ) );
            $panel.append( $portlet );
        }
        var $list = $portlet.is( 'ul' ) ? $portlet : $portlet.find( 'ul' ).first();
        if ( !$list.length ) {
            $list = $( '<ul>' ).appendTo( $portlet );
        }
        var $item = $( '<li>' );
        if ( text !== null && text !== undefined ) {
            var $link = $( '<a>' ).attr( 'href', href || '#' ).text( text );
            if ( tooltip ) {
                $link.attr( 'title', tooltip );
            }
            if ( accessKey ) {
                $link.attr( 'accesskey', accessKey );
            }
            $item.append( $link );
        }
        if ( id ) {
            $item.attr( 'id', id );
        }
        if ( nextNode && $( nextNode ).length ) {
            $item.insertBefore( nextNode );
        } else {
            $list.append( $item );
        }
        return $item[ 0 ];
    },
    addPortlet: function ( portletId, label ) {
        if ( !JQUERY_AVAILABLE ) {
            return null;
        }
        var $ = window.jQuery;
        if ( $( '#' + portletId ).length ) {
            return null;
        }
        var $panel = $( '#wiki-panel' );
        if ( !$panel.length ) {
            return null;
        }
        var $portlet = $( '<div>' ).addClass( 'portal' ).attr( 'id', portletId )
            .attr( 'data-portlet', portletId )
            .append( $( '<h3>' ).text( label || '' ) )
            .append( $( '<ul>' ) );
        $panel.append( $portlet );
        return $portlet.find( 'ul' )[ 0 ];
    },
    tooltipAccessKeyPrefix: 'alt-',
    tooltipAccessKeyRegexp: /\[(ctrl-|alt-|shift-)?([a-z])\]/i,
    /** 与 MediaWiki 一致的隐藏/显示 portlet。 */
    hidePortlet: function ( portletId ) {
        if ( JQUERY_AVAILABLE ) {
            window.jQuery( '#' + portletId ).hide();
        }
    },
    validateEmail: function ( value ) {
        return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test( String( value ) );
    }
};

// ==================== mw.Uri ====================
function Uri( uri, options ) {
    options = options || {};
    if ( uri instanceof Uri ) {
        this.protocol = uri.protocol;
        this.user = uri.user;
        this.password = uri.password;
        this.host = uri.host;
        this.port = uri.port;
        this.path = uri.path;
        this.query = Object.assign( {}, uri.query );
        this.fragment = uri.fragment;
        return;
    }
    var text = uri === undefined ? ( window.location && window.location.href ) || '' : String( uri );
    if ( text === '' ) {
        this.protocol = this.host = this.port = this.path = '';
    } else {
        var match = text.match( /^(?:([^:/?#]+):)?(?:\/\/(?:([^:@/]*)(?::([^@/]*))?@)?([^:/?#]*)(?::(\d+))?)?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/ );
        this.protocol = match[ 1 ] || '';
        this.user = match[ 2 ];
        this.password = match[ 3 ];
        this.host = match[ 4 ] || '';
        this.port = match[ 5 ] || '';
        this.path = match[ 6 ] || '';
        this.query = {};
        if ( match[ 7 ] ) {
            match[ 7 ].split( '&' ).forEach( function ( pair ) {
                if ( pair === '' ) {
                    return;
                }
                var equals = pair.indexOf( '=' );
                var key = decodeURIComponent( ( equals === -1 ? pair : pair.substring( 0, equals ) ).replace( /\+/g, ' ' ) );
                var value = decodeURIComponent( ( equals === -1 ? '' : pair.substring( equals + 1 ) ).replace( /\+/g, ' ' ) );
                this.query[ key ] = value;
            }, this );
        }
        this.fragment = match[ 8 ] !== undefined ? match[ 8 ] : null;
    }
    this.options = options;
}

Uri.prototype.extend = function ( parameters, options ) {
    var extended = new Uri( this );
    options = options || {};
    if ( parameters instanceof Uri ) {
        parameters = parameters.query;
    }
    if ( parameters && typeof parameters === 'object' ) {
        Object.keys( parameters ).forEach( function ( key ) {
            if ( parameters[ key ] === undefined ) {
                if ( options.removeQueryStringParams
                    || ( options.removeParams && options.removeParams.indexOf( key ) !== -1 ) ) {
                    delete extended.query[ key ];
                }
            } else {
                extended.query[ key ] = parameters[ key ];
            }
        } );
    }
    return extended;
};

Uri.prototype.toString = function () {
    var query = this.getQueryString();
    return ( this.protocol ? this.protocol + ':' : '' )
        + ( this.host ? '//' + ( this.user ? this.user + ( this.password ? ':' + this.password : '' ) + '@' : '' )
            + this.host + ( this.port ? ':' + this.port : '' ) : '' )
        + this.path + ( query ? '?' + query : '' )
        + ( this.fragment ? '#' + this.fragment : '' );
};

Uri.prototype.getQueryString = function () {
    var parts = [];
    Object.keys( this.query ).forEach( function ( key ) {
        var value = this.query[ key ];
        if ( value === null || value === undefined ) {
            parts.push( rawurlencode( key ) );
        } else {
            parts.push( rawurlencode( key ) + '=' + rawurlencode( value ) );
        }
    }, this );
    return parts.join( '&' );
};

Uri.prototype.getParam = function ( key ) {
    return this.query[ key ] === undefined ? null : this.query[ key ];
};

Uri.prototype.setParam = function ( key, value ) {
    if ( value === undefined || value === null ) {
        delete this.query[ key ];
    } else {
        this.query[ key ] = value;
    }
    return this;
};

Uri.prototype.removeQueryStringParameter = function ( key ) {
    return this.setParam( key, undefined );
};

Uri.prototype.clone = function () {
    return new Uri( this );
};

// ==================== mw.Title ====================
var namespaceIds = config.get( 'wgNamespaceIds' ) || {};
var formattedNamespaces = config.get( 'wgFormattedNamespaces' ) || {};

function namespaceId( name ) {
    if ( typeof name === 'number' ) {
        return name;
    }
    if ( name === undefined || name === null || name === '' ) {
        return 0;
    }
    var key = String( name ).toLowerCase().replace( / /g, '_' ).replace( /:$/, '' );
    return Object.prototype.hasOwnProperty.call( namespaceIds, key ) ? namespaceIds[ key ] : null;
}

function namespacePrefix( id ) {
    return formattedNamespaces[ String( id ) ] || '';
}

function Title( title, namespace ) {
    if ( title instanceof Title ) {
        this.namespace = title.namespace;
        this.title = title.title;
        this.fragment = title.fragment;
        return;
    }
    var text = String( title === undefined ? '' : title );
    var fragment = null;
    var hash = text.indexOf( '#' );
    if ( hash !== -1 ) {
        fragment = text.substring( hash + 1 );
        text = text.substring( 0, hash );
    }
    text = text.replace( /_/g, ' ' ).trim();
    var forced = namespaceId( namespace );
    var colon = text.indexOf( ':' );
    var detected = colon > 0 ? namespaceId( text.substring( 0, colon ) ) : null;
    if ( forced !== null && forced !== 0 ) {
        this.namespace = forced;
        // 传了命名空间参数时，标题里若带同名前缀则去掉（MediaWiki 行为）
        this.title = ( detected !== null && detected === forced )
            ? text.substring( colon + 1 ).trim() : text;
    } else if ( detected !== null && detected !== 0 ) {
        this.namespace = detected;
        this.title = text.substring( colon + 1 ).trim();
    } else {
        this.namespace = 0;
        this.title = text;
    }
    if ( this.title === '' ) {
        throw new Error( 'mw.Title: invalid title "' + title + '"' );
    }
    this.fragment = fragment;
}

Title.prototype.getNamespaceId = function () {
    return this.namespace;
};

Title.prototype.getNamespacePrefix = function () {
    return namespacePrefix( this.namespace );
};

Title.prototype.getMainText = function () {
    return this.title;
};

Title.prototype.getMain = function () {
    return this.title;
};

Title.prototype.getPrefixedText = function () {
    var prefix = this.getNamespacePrefix();
    return ( prefix ? prefix + ':' : '' ) + this.title;
};

Title.prototype.getPrefixedDb = function () {
    return this.getPrefixedText().replace( / /g, '_' );
};

Title.prototype.getUrl = function ( params ) {
    return wikiUrl( this.getPrefixedText(), params );
};

Title.prototype.isTalkPage = function () {
    return this.namespace % 2 === 1;
};

Title.prototype.getTalkPage = function () {
    if ( this.isTalkPage() ) {
        return this;
    }
    return new Title( this.getMainText(), this.namespace === 0 ? 1 : this.namespace + 1 );
};

Title.prototype.getSubjectPage = function () {
    return this.isTalkPage() ? new Title( this.getMainText(), this.namespace - 1 ) : this;
};

Title.newFromText = function ( title, namespace ) {
    try {
        return new Title( title, namespace );
    } catch ( error ) {
        return null;
    }
};

// ==================== mw.html ====================
function Html( tag ) {
    this.tag = tag;
    this.attributes = {};
    this.contents = [];
}

function htmlEscape( text ) {
    return mwEscape( text );
}

Html.prototype.attr = function ( key, value ) {
    if ( value === undefined ) {
        return this.attributes[ key ];
    }
    if ( value === null ) {
        delete this.attributes[ key ];
    } else {
        this.attributes[ key ] = value;
    }
    return this;
};

Html.prototype.addClass = function ( className ) {
    var current = this.attributes.class ? this.attributes.class.split( /\s+/ ) : [];
    String( className ).split( /\s+/ ).forEach( function ( name ) {
        if ( name && current.indexOf( name ) === -1 ) {
            current.push( name );
        }
    } );
    this.attributes.class = current.join( ' ' );
    return this;
};

Html.prototype.removeClass = function ( className ) {
    var remove = String( className ).split( /\s+/ );
    this.attributes.class = ( this.attributes.class || '' ).split( /\s+/ ).filter( function ( name ) {
        return name && remove.indexOf( name ) === -1;
    } ).join( ' ' );
    if ( !this.attributes.class ) {
        delete this.attributes.class;
    }
    return this;
};

Html.prototype.css = function ( key, value ) {
    var style = this.attributes.style || '';
    style += ( style && style.charAt( style.length - 1 ) !== ';' ? ';' : '' ) + key + ':' + value;
    this.attributes.style = style;
    return this;
};

Html.prototype.text = function ( value ) {
    this.contents.push( htmlEscape( value ) );
    return this;
};

Html.prototype.wikitext = function ( value ) {
    // 差异：不做服务端解析，按纯文本处理（README 说明）
    log.warn( 'mw.html#wikitext 在当前实现里按纯文本处理（未经解析器）' );
    return this.text( value );
};

Html.prototype.rawHtml = function ( value ) {
    this.contents.push( String( value ) );
    return this;
};

Html.prototype.append = function ( content ) {
    if ( content instanceof Html ) {
        this.contents.push( content.toString() );
    } else if ( content !== undefined && content !== null ) {
        this.contents.push( String( content ) );
    }
    return this;
};

Html.prototype.prepend = function ( content ) {
    if ( content instanceof Html ) {
        this.contents.unshift( content.toString() );
    } else if ( content !== undefined && content !== null ) {
        this.contents.unshift( String( content ) );
    }
    return this;
};

Html.prototype.toString = function () {
    var attributes = '';
    Object.keys( this.attributes ).forEach( function ( key ) {
        attributes += ' ' + key + '="' + htmlEscape( this.attributes[ key ] ) + '"';
    }, this );
    return '<' + this.tag + attributes + '>' + this.contents.join( '' ) + '</' + this.tag + '>';
};

Html.prototype.get = function () {
    return this.toString();
};

var html = {
    create: function ( tag ) {
        return new Html( tag );
    },
    element: function ( tag, attrs, contents ) {
        var element = new Html( tag );
        if ( attrs ) {
            Object.keys( attrs ).forEach( function ( key ) {
                element.attr( key, attrs[ key ] );
            } );
        }
        if ( typeof contents === 'string' ) {
            element.text( contents );
        } else if ( Array.isArray( contents ) ) {
            contents.forEach( function ( item ) {
                element.append( item );
            } );
        } else if ( contents instanceof Html ) {
            element.append( contents );
        }
        return element.toString();
    },
    escape: htmlEscape
};

// ==================== mw.Api ====================
function Api( options ) {
    this.options = options || {};
    this.defaults = this.options.parameters || {};
    this.pending = [];
}

Api.prototype.ajax = function ( parameters, options ) {
    options = options || {};
    var $ = window.jQuery;
    if ( !$ ) {
        return $.Deferred().reject( 'no-jquery' ).promise();
    }
    var self = this;
    var data = Object.assign( {}, this.defaults, options.parameters, parameters );
    data.format = 'json';
    var deferredResult = $.Deferred();
    var request = $.ajax( {
        url: ( this.options.ajax && this.options.ajax.url ) || wikiScript( 'api' ),
        type: options.type || 'GET',
        dataType: 'json',
        data: data,
        async: options.async !== undefined ? options.async : true
    } );
    this.pending.push( request );
    request.done( function ( response ) {
        if ( response && response.error ) {
            var code = response.error.code || 'unknown';
            log.warn( 'mw.Api 请求出错：' + code, response.error.info || '' );
            deferredResult.reject( code, response );
            return;
        }
        if ( response && response.warnings ) {
            log.warn( 'mw.Api 警告', response.warnings );
        }
        deferredResult.resolve( response, request );
    } ).fail( function ( xhr, status ) {
        deferredResult.reject( status === 'abort' ? 'abort' : 'http', xhr );
    } );
    return deferredResult.promise();
};

Api.prototype.get = function ( parameters, options ) {
    return this.ajax( parameters, Object.assign( { type: 'GET' }, options ) );
};

Api.prototype.post = function ( parameters, options ) {
    return this.ajax( parameters, Object.assign( { type: 'POST' }, options ) );
};

/** 取 token（默认 csrf；与 MediaWiki 一样会缓存到 mw.user.tokens）。 */
Api.prototype.getToken = function ( type ) {
    type = type || 'csrf';
    var cached = user.tokens.get( type + 'Token' ) || ( type === 'csrf' ? user.tokens.get( 'csrfToken' ) : null );
    if ( cached ) {
        return window.jQuery.Deferred().resolve( cached ).promise();
    }
    var self = this;
    return this.get( { action: 'query', meta: 'tokens', type: type } ).then( function ( response ) {
        var tokens = ( response.query && response.query.tokens ) || {};
        var token = tokens[ type + 'token' ];
        if ( token ) {
            user.tokens.set( type + 'Token', token );
        }
        return token;
    } );
};

Api.prototype.postWithToken = function ( type, parameters ) {
    var self = this;
    return this.getToken( type ).then( function ( token ) {
        return self.post( Object.assign( { token: token }, parameters ) );
    } );
};

/** 中止本实例所有在途请求。 */
Api.prototype.abort = function () {
    this.pending.forEach( function ( request ) {
        try {
            request.abort();
        } catch ( error ) { /* 已结束 */ }
    } );
    this.pending = [];
};

/** 便捷：各种常用的“取页面内容”封装（MediaWiki 提供大量此类封装，这里给最常用的几个）。 */
Api.prototype.getCategories = function ( title ) {
    return this.get( {
        action: 'query', prop: 'categories', titles: title, cllimit: 'max'
    } ).then( function ( response ) {
        var pages = ( response.query && response.query.pages ) || {};
        var page = pages[ Object.keys( pages )[ 0 ] ] || {};
        return ( page.categories || [] ).map( function ( category ) {
            return category.title;
        } );
    } );
};

// ==================== mw.notify ====================
function notify( message, options ) {
    options = typeof options === 'string' ? { type: options } : ( options || {} );
    if ( !JQUERY_AVAILABLE ) {
        return null;
    }
    var $ = window.jQuery;
    var $area = $( '#mw-notification-area' );
    if ( !$area.length ) {
        $area = $( '<div>' ).attr( 'id', 'mw-notification-area' ).appendTo( document.body );
    }
    var text = message instanceof Message ? message.text() : String( message );
    var $notification = $( '<div>' )
        .addClass( 'mw-notification' )
        .addClass( 'mw-notification-type-' + ( options.type || 'info' ) );
    if ( options.title ) {
        $( '<div>' ).addClass( 'mw-notification-title' ).text( options.title ).appendTo( $notification );
    }
    $( '<div>' ).addClass( 'mw-notification-content' ).html( text ).appendTo( $notification );
    $area.append( $notification );
    var autoHide = options.autoHideSeconds !== undefined ? options.autoHideSeconds
        : ( options.autoHide === false ? 0 : 4 );
    function dismiss() {
        $notification.addClass( 'mw-notification-dismissed' );
        window.setTimeout( function () {
            $notification.remove();
        }, 300 );
    }
    $notification.on( 'click', dismiss );
    if ( autoHide > 0 ) {
        window.setTimeout( dismiss, autoHide * 1000 );
    }
    return $notification;
}

// ==================== 组装 mw 对象 ====================
var mw = {
    config: config,
    messages: mwMessages,
    message: message,
    Message: Message,
    hook: hook,
    loader: loader,
    util: util,
    user: user,
    cookie: cookie,
    storage: storage,
    Uri: Uri,
    Title: Title,
    html: html,
    Api: Api,
    notify: notify,
    log: log,
    errorLogger: {
        logError: logError,
        logDeprecation: function ( message ) {
            log.warn( message );
        }
    },
    track: track,
    trackSubscribe: trackSubscribe,
    trackUnsubscribe: function ( topic, handler ) {
        var handlers = trackHandlers.get( topic );
        if ( handlers ) {
            var index = handlers.indexOf( handler );
            if ( index !== -1 ) {
                handlers.splice( index, 1 );
            }
        }
    },
    requestIdleCallback: function ( callback, options ) {
        if ( window.requestIdleCallback ) {
            return window.requestIdleCallback( callback, options );
        }
        return window.setTimeout( function () {
            callback( { didTimeout: false, timeRemaining: function () {
                return 0;
            } } );
        }, 1 );
    },
    requestAnimationFrame: function ( callback ) {
        if ( window.requestAnimationFrame ) {
            return window.requestAnimationFrame( callback );
        }
        return window.setTimeout( callback, 16 );
    }
};

// 弃用别名（MediaWiki 里仍可用，带警告）
log.deprecate( mw, 'experimental', {}, '1.35', 'mw.util / mw.Api' );

window.mw = mw;

// 注入的初始消息
if ( window.wgMessages ) {
    mwMessages.set( window.wgMessages );
    delete window.wgMessages;
}

// 页面就绪：触发 wikipage.content（Gadgets 常用）
function fireContentHook() {
    try {
        mw.hook( 'wikipage.content' ).fire( util.$content() );
    } catch ( error ) {
        logError( error, 'wikipage.content' );
    }
}

if ( JQUERY_AVAILABLE ) {
    window.jQuery( function () {
        fireContentHook();
    } );
} else if ( document.readyState === 'loading' ) {
    document.addEventListener( 'DOMContentLoaded', fireContentHook );
} else {
    fireContentHook();
}

// 处理 RLQ（启动队列）：function 或 [模块名, 回调]
( function processRLQ() {
    var queue = window.RLQ || [];
    window.RLQ = [];
    queue.forEach( function ( entry ) {
        try {
            if ( typeof entry === 'function' ) {
                entry();
            } else if ( Array.isArray( entry ) && entry.length >= 2 ) {
                loader.using( entry[ 0 ] ).done( entry[ 1 ] );
            } else if ( Array.isArray( entry ) && typeof entry[ 0 ] === 'function' ) {
                entry[ 0 ]();
            }
        } catch ( error ) {
            logError( error, 'RLQ' );
        }
    } );
}() );

}() );
