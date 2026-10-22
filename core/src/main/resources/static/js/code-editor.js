/*
 * JS/CSS/HTML（微件）代码页面的预览与编辑器（CodeMirror 5，本地内置）。
 *  - 预览：pre.wiki-code[data-language] → 只读高亮代码块（可选择 / 复制，光标隐藏；右上角有「复制」按钮）
 *  - 编辑：#wiki-edit-form textarea[data-language] → 代码编辑器（语法高亮 + 自动补全）
 * 补全：JavaScript 用 CodeMirror 内置作用域提示；CSS 用常用属性列表；HTML/微件页用标签与属性列表；
 *      输入字母/连字符时自动弹出，Ctrl+空格手动触发。
 */
(function () {
    'use strict';

    if (typeof CodeMirror === 'undefined') {
        return;
    }

    var CSS_PROPERTIES = [
        'align-content', 'align-items', 'align-self', 'animation', 'animation-delay', 'animation-duration',
        'background', 'background-attachment', 'background-clip', 'background-color', 'background-image',
        'background-position', 'background-repeat', 'background-size', 'border', 'border-bottom',
        'border-bottom-color', 'border-bottom-style', 'border-bottom-width', 'border-collapse', 'border-color',
        'border-left', 'border-radius', 'border-right', 'border-spacing', 'border-style', 'border-top',
        'border-width', 'bottom', 'box-shadow', 'box-sizing', 'caption-side', 'clear', 'clip', 'color',
        'column-count', 'column-gap', 'content', 'cursor', 'direction', 'display', 'flex', 'flex-basis',
        'flex-direction', 'flex-flow', 'flex-grow', 'flex-shrink', 'flex-wrap', 'float', 'font', 'font-family',
        'font-size', 'font-style', 'font-variant', 'font-weight', 'gap', 'grid', 'grid-area', 'grid-column',
        'grid-gap', 'grid-row', 'grid-template-columns', 'grid-template-rows', 'height', 'justify-content',
        'left', 'letter-spacing', 'line-height', 'list-style', 'list-style-type', 'margin', 'margin-bottom',
        'margin-left', 'margin-right', 'margin-top', 'max-height', 'max-width', 'min-height', 'min-width',
        'object-fit', 'opacity', 'outline', 'overflow', 'overflow-x', 'overflow-y', 'padding', 'padding-bottom',
        'padding-left', 'padding-right', 'padding-top', 'position', 'right', 'text-align', 'text-decoration',
        'text-indent', 'text-overflow', 'text-shadow', 'text-transform', 'top', 'transform', 'transition',
        'transition-delay', 'transition-duration', 'user-select', 'vertical-align', 'visibility', 'white-space',
        'width', 'word-break', 'word-wrap', 'z-index'
    ];

    var JS_KEYWORDS = [
        'var', 'let', 'const', 'function', 'return', 'if', 'else', 'for', 'while', 'do', 'switch', 'case',
        'break', 'continue', 'new', 'typeof', 'instanceof', 'in', 'of', 'this', 'null', 'true', 'false',
        'undefined', 'try', 'catch', 'finally', 'throw', 'class', 'extends', 'super', 'async', 'await',
        'yield', 'delete', 'void', 'document', 'window', 'console', 'JSON', 'Math', 'Object', 'Array',
        'String', 'Number', 'Boolean', 'Promise', 'setTimeout', 'setInterval'
    ];

    // 类型注解相关的关键字（TypeScript 页面用；JS 页面不提示这些，避免噪音）
    var TS_KEYWORDS = JS_KEYWORDS.concat([
        'interface', 'type', 'enum', 'namespace', 'declare', 'readonly', 'abstract', 'implements',
        'satisfies', 'keyof', 'infer', 'asserts', 'public', 'private', 'protected', 'override',
        'any', 'unknown', 'never', 'string', 'number', 'boolean', 'object', 'symbol', 'bigint',
        'Record', 'Partial', 'Readonly', 'Pick', 'Omit', 'Array', 'Promise'
    ]);

    function modeOf(language) {
        if (language === 'css') {
            return 'css';
        }
        if (language === 'html') {
            return 'htmlmixed';
        }
        if (language === 'lua') {
            return 'lua';
        }
        if (language === 'typescript') {
            // CodeMirror 5 的 JavaScript 模式自带 TypeScript 支持（text/typescript MIME），不需要额外模式文件
            return 'text/typescript';
        }
        return 'javascript';
    }

    /** 光标前的当前单词（词字符：字母/数字/_/$/-）。 */
    function wordAt(cm) {
        var cursor = cm.getCursor();
        var token = cm.getTokenAt(cursor);
        var text = token.string.slice(0, cursor.ch - token.start);
        var word = text.replace(/^[^A-Za-z0-9_$-]+/, '');
        return { word: word, start: token.start + (text.length - word.length), cursor: cursor };
    }

    function buildHint(info, items) {
        var seen = {};
        var list = [];
        items.forEach(function (item) {
            if (item && !seen[item] && item.indexOf(info.word) === 0 && item !== info.word) {
                seen[item] = 1;
                list.push(item);
            }
        });
        if (!list.length) {
            return null;
        }
        return { list: list, from: CodeMirror.Pos(info.cursor.line, info.start), to: info.cursor };
    }

    /** CSS：常用属性 + 文档里的词。 */
    function cssHint(cm) {
        var info = wordAt(cm);
        if (!info.word) {
            return null;
        }
        var items = CSS_PROPERTIES.slice();
        var any = CodeMirror.hint.anyword ? CodeMirror.hint.anyword(cm) : null;
        if (any && any.list) {
            items = items.concat(any.list);
        }
        return buildHint(info, items);
    }

    /** JS：关键字 + 内置作用域提示 + 文档里的词（内置 hint 常常拿不到顶层变量，用 anyword 兜底）。 */
    function jsHint(cm) {
        var info = wordAt(cm);
        if (!info.word) {
            return null;
        }
        var items = JS_KEYWORDS.slice();
        var builtin = CodeMirror.hint.javascript ? CodeMirror.hint.javascript(cm) : null;
        if (builtin && builtin.list) {
            items = items.concat(builtin.list);
        }
        var any = CodeMirror.hint.anyword ? CodeMirror.hint.anyword(cm) : null;
        if (any && any.list) {
            items = items.concat(any.list);
        }
        return buildHint(info, items);
    }

    /** TypeScript：JS 关键字 + 类型相关关键字 + 文档里的词。 */
    function tsHint(cm) {
        var info = wordAt(cm);
        if (!info.word) {
            return null;
        }
        var items = TS_KEYWORDS.slice();
        var builtin = CodeMirror.hint.javascript ? CodeMirror.hint.javascript(cm) : null;
        if (builtin && builtin.list) {
            items = items.concat(builtin.list);
        }
        var any = CodeMirror.hint.anyword ? CodeMirror.hint.anyword(cm) : null;
        if (any && any.list) {
            items = items.concat(any.list);
        }
        return buildHint(info, items);
    }

    /** HTML / 微件页：标签名 + 常见属性 + 文档里的词。 */
    var HTML_ITEMS = [
        'a', 'abbr', 'article', 'aside', 'audio', 'b', 'blockquote', 'br', 'button', 'canvas', 'caption',
        'code', 'col', 'colgroup', 'data', 'datalist', 'dd', 'del', 'details', 'div', 'dl', 'dt', 'em',
        'embed', 'fieldset', 'figcaption', 'figure', 'footer', 'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
        'head', 'header', 'hr', 'html', 'i', 'iframe', 'img', 'input', 'ins', 'kbd', 'label', 'legend',
        'li', 'link', 'main', 'map', 'mark', 'meta', 'nav', 'noscript', 'object', 'ol', 'optgroup',
        'option', 'output', 'p', 'param', 'picture', 'pre', 'progress', 'q', 's', 'samp', 'script',
        'section', 'select', 'small', 'source', 'span', 'strong', 'style', 'sub', 'summary', 'sup',
        'table', 'tbody', 'td', 'template', 'tfoot', 'th', 'thead', 'time', 'title', 'tr', 'track', 'u',
        'ul', 'video',
        'class', 'id', 'style', 'href', 'src', 'alt', 'title', 'width', 'height', 'name', 'value', 'type',
        'loading', 'referrerpolicy', 'allow', 'allowfullscreen', 'frameborder', 'target', 'rel', 'media',
        'async', 'defer', 'charset', 'content', 'http-equiv', 'property', 'lang', 'dir', 'role', 'hidden',
        'data-'
    ];

    function htmlHint(cm) {
        var info = wordAt(cm);
        if (!info.word) {
            return null;
        }
        var items = HTML_ITEMS.slice();
        var any = CodeMirror.hint.anyword ? CodeMirror.hint.anyword(cm) : null;
        if (any && any.list) {
            items = items.concat(any.list);
        }
        return buildHint(info, items);
    }

    function hint(cm) {
        var mode = cm.getOption('mode');
        if (mode === 'css') {
            return cssHint(cm);
        }
        if (mode === 'htmlmixed') {
            return htmlHint(cm);
        }
        if (mode === 'text/typescript' || mode === 'application/typescript') {
            return tsHint(cm);
        }
        return jsHint(cm);
    }

    /** 注册成命令，便于测试与外部调用（编辑器里 Ctrl+空格 也走它）。 */
    CodeMirror.commands.wikiAutocomplete = function (cm) {
        cm.showHint({ hint: hint, completeSingle: false });
    };

    var SEARCH_MATCH_LIMIT = 500;

    /**
     * 编辑器内查找：CodeMirror 默认没有 Ctrl+F；这里提供一个轻量的源代码查找条。
     * 支持 Ctrl+F / Cmd+F 打开、Enter 下一个、Shift+Enter 上一个、Esc 关闭。
     * 不依赖额外的 CodeMirror addon，预览区同样可用。
     */
    function installSearch(cm) {
        var wrapper = cm.getWrapperElement();
        var existing = wrapper.querySelector('.wiki-code-search');
        if (existing) {
            existing.hidden = false;
            var existingInput = existing.querySelector('input');
            existingInput.focus();
            existingInput.select();
            return;
        }

        var bar = document.createElement('div');
        bar.className = 'wiki-code-search';
        bar.hidden = false;

        var input = document.createElement('input');
        input.type = 'search';
        input.placeholder = '查找源代码…';
        input.setAttribute('aria-label', '查找源代码');

        var count = document.createElement('span');
        count.className = 'wiki-code-search-count';
        count.setAttribute('aria-live', 'polite');

        var prev = document.createElement('button');
        prev.type = 'button';
        prev.className = 'wiki-code-search-button';
        prev.title = '上一个（Shift+Enter）';
        prev.textContent = '↑';

        var next = document.createElement('button');
        next.type = 'button';
        next.className = 'wiki-code-search-button';
        next.title = '下一个（Enter）';
        next.textContent = '↓';

        var close = document.createElement('button');
        close.type = 'button';
        close.className = 'wiki-code-search-button';
        close.title = '关闭（Esc）';
        close.textContent = '×';

        bar.appendChild(input);
        bar.appendChild(count);
        bar.appendChild(prev);
        bar.appendChild(next);
        bar.appendChild(close);
        wrapper.appendChild(bar);

        var marks = [];
        var matches = [];
        var current = -1;

        function clearMarks() {
            marks.forEach(function (mark) {
                mark.clear();
            });
            marks = [];
            matches = [];
            current = -1;
        }

        function updateCount(limited) {
            count.textContent = matches.length
                ? ((current >= 0 ? current + 1 : 0) + '/' + matches.length + (limited ? '+' : ''))
                : '0';
        }

        function select(index, keepInputFocus) {
            if (!matches.length) {
                current = -1;
                updateCount(false);
                return;
            }
            current = (index % matches.length + matches.length) % matches.length;
            var match = matches[current];
            var from = cm.posFromIndex(match.start);
            var to = cm.posFromIndex(match.end);
            cm.setSelection(from, to);
            cm.scrollIntoView({ from: from, to: to }, 80);
            updateCount(match.limited);
            if (keepInputFocus) {
                input.focus();
            }
        }

        function refresh(keepCurrent) {
            var query = input.value;
            clearMarks();
            if (!query) {
                updateCount(false);
                return;
            }
            var value = cm.getValue();
            var haystack = value.toLowerCase();
            var needle = query.toLowerCase();
            var start = 0;
            var limited = false;
            while (start <= haystack.length - needle.length) {
                var found = haystack.indexOf(needle, start);
                if (found < 0) {
                    break;
                }
                if (matches.length >= SEARCH_MATCH_LIMIT) {
                    limited = true;
                    break;
                }
                var end = found + needle.length;
                matches.push({ start: found, end: end, limited: limited });
                marks.push(cm.markText(cm.posFromIndex(found), cm.posFromIndex(end), {
                    className: 'wiki-search-match'
                }));
                start = end;
            }
            if (!matches.length) {
                current = -1;
                updateCount(limited);
                return;
            }
            if (keepCurrent && current >= 0) {
                select(Math.min(current, matches.length - 1), true);
            } else {
                select(0, true);
            }
            if (limited) {
                count.textContent = matches.length + '+ 条匹配';
            }
        }

        function step(delta) {
            if (!matches.length) {
                return;
            }
            select(current + delta, true);
        }

        function closeSearch() {
            clearMarks();
            bar.hidden = true;
            cm.focus();
        }

        input.addEventListener('input', function () {
            refresh(false);
        });
        input.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                event.preventDefault();
                closeSearch();
                return;
            }
            if (event.key === 'Enter') {
                event.preventDefault();
                step(event.shiftKey ? -1 : 1);
            }
        });
        prev.addEventListener('click', function () {
            step(-1);
            input.focus();
        });
        next.addEventListener('click', function () {
            step(1);
            input.focus();
        });
        close.addEventListener('click', closeSearch);
        bar.addEventListener('mousedown', function (event) {
            event.stopPropagation();
        });
        cm.on('change', function () {
            if (!bar.hidden) {
                refresh(true);
            }
        });

        input.value = cm.getSelection() || '';
        refresh(false);
        input.focus();
        input.select();
    }

    CodeMirror.commands.wikiCodeFind = function (cm) {
        installSearch(cm);
        return true;
    };

    function commonOptions(mode, readOnly) {
        var extraKeys = {
            'Ctrl-F': 'wikiCodeFind',
            'Cmd-F': 'wikiCodeFind'
        };
        if (!readOnly) {
            extraKeys['Ctrl-Space'] = 'wikiAutocomplete';
            extraKeys.Tab = function (cm) {
                cm.replaceSelection('  ', 'end');
            };
        }
        return {
            mode: mode,
            lineNumbers: true,
            indentUnit: 2,
            tabSize: 2,
            indentWithTabs: false,
            readOnly: readOnly,
            matchBrackets: true,
            autoCloseBrackets: !readOnly,
            extraKeys: extraKeys
        };
    }

    // ---------- 预览（只读高亮：可选择 / 复制，光标隐藏） ----------
    var MESSAGES = window.WIKI_MESSAGES || {};

    /** 复制文本：优先 Clipboard API，失败回退到临时 textarea + execCommand。 */
    function copyText(text, done, failed) {
        var fallback = function () {
            var area = document.createElement('textarea');
            area.value = text;
            area.setAttribute('readonly', 'readonly');
            area.style.position = 'fixed';
            area.style.left = '-9999px';
            document.body.appendChild(area);
            area.select();
            var ok = false;
            try {
                ok = document.execCommand('copy');
            } catch (error) {
                ok = false;
            }
            document.body.removeChild(area);
            (ok ? done : failed)();
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done, fallback);
        } else {
            fallback();
        }
    }

    document.querySelectorAll('pre.wiki-code[data-language]').forEach(function (pre) {
        var language = pre.getAttribute('data-language');
        var container = document.createElement('div');
        container.className = 'wiki-code-view';
        pre.parentNode.replaceChild(container, pre);

        // readOnly: true（而不是 'nocursor'）：能获得焦点，才可以用鼠标拖选 / Ctrl+A/C 复制；
        // 光标由 CSS 隐藏（.wiki-code-view .CodeMirror-cursor），观感仍是静态预览。
        var cm = CodeMirror(container, commonOptions(modeOf(language), true));
        cm.setValue(pre.textContent);
        // 按行数自适应高度（最多 70vh，超出滚动）
        var height = Math.max(80, Math.min(Math.round(window.innerHeight * 0.7), cm.lineCount() * cm.defaultTextHeight() + 20));
        cm.setSize(null, height);

        // 一键复制（全选复制失败时的兜底入口）
        var copyButton = document.createElement('button');
        copyButton.type = 'button';
        copyButton.className = 'wiki-button wiki-code-copy';
        var label = MESSAGES.codeCopy || 'code.copy';
        copyButton.textContent = label;
        copyButton.addEventListener('click', function () {
            copyText(cm.getValue(), function () {
                copyButton.textContent = MESSAGES.codeCopied || 'code.copied';
                setTimeout(function () { copyButton.textContent = label; }, 1500);
            }, function () {
                copyButton.textContent = MESSAGES.codeCopyFailed || 'code.copyFailed';
                setTimeout(function () { copyButton.textContent = label; }, 2000);
            });
        });
        container.appendChild(copyButton);
    });

    // ---------- 编辑（wikitext / 代码页：可选语法高亮 + 补全） ----------
    // 注意：wikitext 页的 data-language 是 null，Thymeleaf 会整个省略该属性，所以按 name="content" 取
    var textarea = document.querySelector('#wiki-edit-form textarea[name="content"]');
    if (textarea) {
        var language = (textarea.getAttribute('data-language') || '').trim();
        var isWikiText = language === '';
        var HIGHLIGHT_KEY = 'wikitext-highlight';
        var editor = null;
        var syncTimer = null;

        function highlightPreferred() {
            try {
                return window.localStorage.getItem(HIGHLIGHT_KEY) === '1';
            } catch (e) {
                return false;
            }
        }

        function rememberHighlight(on) {
            try {
                window.localStorage.setItem(HIGHLIGHT_KEY, on ? '1' : '0');
            } catch (e) { /* 隐私模式忽略 */ }
        }

        /** 把 CodeMirror 的改动同步回 textarea（草稿保护 / 表单提交都读 textarea）。 */
        function syncBack() {
            if (!editor) {
                return;
            }
            editor.save();
            clearTimeout(syncTimer);
            syncTimer = setTimeout(function () {
                textarea.dispatchEvent(new Event('input', { bubbles: true }));
            }, 250);
        }

        function createEditor() {
            if (editor) {
                return editor;
            }
            editor = CodeMirror.fromTextArea(textarea,
                commonOptions(isWikiText ? 'wikitext' : modeOf(language), false));
            editor.getWrapperElement().className +=
                ' wiki-code-editor' + (isWikiText ? ' wiki-wikitext-editor' : '');
            editor.setSize(null, Math.round(window.innerHeight * 0.6));
            if (!isWikiText) {
                editor.on('inputRead', function (cm, change) {
                    var typed = change.text[0] || '';
                    if (/[a-zA-Z-]/.test(typed)) {
                        cm.showHint({ hint: hint, completeSingle: false });
                    }
                });
            }
            editor.on('change', syncBack);
            return editor;
        }

        function destroyEditor() {
            if (!editor) {
                return;
            }
            editor.toTextArea(); // 值回写 textarea，并移除 CodeMirror 的 DOM
            editor = null;
        }

        // 代码页始终高亮（保持原有行为）；wikitext 按用户开关（默认关，MediaWiki 也是可选项）
        if (!isWikiText || highlightPreferred()) {
            createEditor();
        }

        /**
         * 编辑器桥：工具栏（edit-toolbar.js）在 CodeMirror 激活时改走这里，
         * 保证插入的内容立刻可见（否则只会写进隐藏的 textarea）。
         */
        window.wikiCodeEditor = {
            active: function () {
                return !!editor;
            },
            isWikiText: isWikiText,
            focus: function () {
                if (editor) {
                    editor.focus();
                } else {
                    textarea.focus();
                }
            },
            getValue: function () {
                return editor ? editor.getValue() : textarea.value;
            },
            getSelectionRange: function () {
                if (!editor) {
                    return { start: textarea.selectionStart, end: textarea.selectionEnd };
                }
                return {
                    start: editor.indexFromPos(editor.getCursor('from')),
                    end: editor.indexFromPos(editor.getCursor('to'))
                };
            },
            /** 以「字符偏移」替换（与 textarea.setRangeText 同一坐标系）；未激活返回 false。 */
            replaceRange: function (text, start, end, selectionStart, selectionEnd) {
                if (!editor) {
                    return false;
                }
                editor.replaceRange(text, editor.posFromIndex(start), editor.posFromIndex(end));
                if (typeof selectionStart === 'number') {
                    editor.setSelection(editor.posFromIndex(selectionStart),
                        editor.posFromIndex(typeof selectionEnd === 'number' ? selectionEnd : selectionStart));
                }
                editor.focus();
                syncBack();
                return true;
            },
            /** 开关语法高亮，返回切换后的状态（true = 已开启）。 */
            toggle: function () {
                if (editor) {
                    destroyEditor();
                    rememberHighlight(false);
                    textarea.focus();
                    return false;
                }
                createEditor();
                rememberHighlight(true);
                editor.focus();
                return true;
            }
        };

        var form = document.getElementById('wiki-edit-form');
        if (form) {
            // fromTextArea 已处理提交同步，这里再保险一次
            form.addEventListener('submit', function () {
                if (editor) {
                    editor.save();
                }
            });
        }
    }
})();
