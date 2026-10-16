/*
 * 编辑辅助工具栏（仿 MediaWiki WikiEditor 默认皮肤）：
 *  - 插入：粗体 / 斜体 / 内部链接 / 外部链接 / 参考文献 / 签名 / 标题 / 列表 / 缩进 /
 *          nowiki / 水平线 / 大号 / 小号 / 上标 / 下标 / 表格（对话框，仿 MW 的「插入表格」）
 *  - 特殊字符面板；Ctrl+B / Ctrl+I 快捷键
 *  - 快速上传图片：弹窗选择文件（带预览）→ POST /api/files/upload → 插入 [[文件:名称|thumb]]
 *    （multi-upload 插件装好后：对话框支持多选、编辑器支持拖入文件）
 *  - 语法高亮开关：切换 CodeMirror（wikitext 模式）——见 code-editor.js 的 window.wikiCodeEditor
 * 所有插入都会触发 input 事件，编辑草稿保护（edit-draft.js）随之自动保存。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var toolbar = document.getElementById('wiki-editor-toolbar');
    var form = document.getElementById('wiki-edit-form');
    if (!toolbar || !form) {
        return;
    }
    var textarea = form.querySelector('textarea[name="content"]');
    if (!textarea) {
        return;
    }

    function msg(key) {
        return MESSAGES[key] || key;
    }

    /** 消息模板里的 {0} / {1} 占位符（对应 messages 的 {0} / {1}） */
    function formatMessage(template, first, second) {
        return String(template || '')
            .replace('{0}', first == null ? '' : first)
            .replace('{1}', second == null ? '' : second);
    }

    function notify() {
        textarea.dispatchEvent(new Event('input', { bubbles: true }));
    }

    // ---------- 编辑器适配：CodeMirror 激活时走桥（否则直接用 textarea） ----------

    function bridge() {
        var editor = window.wikiCodeEditor;
        return editor && editor.active() ? editor : null;
    }

    function value() {
        var editor = bridge();
        return editor ? editor.getValue() : textarea.value;
    }

    function selectionRange() {
        var editor = bridge();
        return editor ? editor.getSelectionRange()
            : { start: textarea.selectionStart, end: textarea.selectionEnd };
    }

    function replaceRange(text, start, end, selectionStart, selectionEnd) {
        var editor = bridge();
        if (editor && editor.replaceRange(text, start, end, selectionStart, selectionEnd)) {
            notify();
            return;
        }
        textarea.setRangeText(text, start, end, 'end');
        textarea.focus();
        if (typeof selectionStart === 'number') {
            textarea.setSelectionRange(selectionStart,
                typeof selectionEnd === 'number' ? selectionEnd : selectionStart);
        }
        notify();
    }

    /** 包裹选区（无选区时插入示例文本并选中，仿 MediaWiki）。 */
    function insertAround(before, after, sample) {
        var range = selectionRange();
        var selected = value().slice(range.start, range.end);
        var inner = selected || sample || '';
        var innerStart = range.start + before.length;
        replaceRange(before + inner + after, range.start, range.end, innerStart, innerStart + inner.length);
    }

    function insertText(text) {
        var range = selectionRange();
        replaceRange(text, range.start, range.end, range.start + text.length, range.start + text.length);
    }

    /** 当前选区覆盖的整行范围。 */
    function lineRange() {
        var content = value();
        var range = selectionRange();
        var start = content.lastIndexOf('\n', range.start - 1) + 1;
        var end = content.indexOf('\n', range.end);
        if (end < 0) {
            end = content.length;
        }
        return { start: start, end: end, text: content.slice(start, end) };
    }

    /** 给选中行加前缀；若都已带前缀则去掉（再点一次取消）。 */
    function toggleLinePrefix(prefix) {
        var range = lineRange();
        var lines = range.text.split('\n');
        var allPrefixed = lines.every(function (line) {
            return line.indexOf(prefix) === 0;
        });
        var updated = lines.map(function (line) {
            return allPrefixed ? line.slice(prefix.length) : prefix + line;
        }).join('\n');
        replaceRange(updated, range.start, range.end, range.start, range.start + updated.length);
    }

    /** 标题：给选中行套 = 级别 =（已有的标题符号先去掉）。 */
    function applyHeading(level) {
        var marks = new Array(level + 1).join('=');
        var sample = msg('toolbarHeadingSample');
        var range = lineRange();
        var updated = range.text.split('\n').map(function (line) {
            var text = line.replace(/^\s*={1,6}\s*/, '').replace(/\s*={1,6}\s*$/, '').trim();
            return marks + ' ' + (text || sample) + ' ' + marks;
        }).join('\n');
        replaceRange(updated, range.start, range.end, range.start, range.start + updated.length);
    }

    var ACTIONS = {
        bold: function () { insertAround("'''", "'''", msg('toolbarBoldSample')); },
        italic: function () { insertAround("''", "''", msg('toolbarItalicSample')); },
        link: function () { insertAround('[[', ']]', msg('toolbarLinkSample')); },
        external: function () {
            insertAround('[https://example.com ', ']', msg('toolbarExternalSample'));
        },
        ref: function () { insertAround('<ref>', '</ref>', ''); },
        signature: function () { insertText('--~~~~'); },
        bullets: function () { toggleLinePrefix('* '); },
        numbers: function () { toggleLinePrefix('# '); },
        indent: function () { toggleLinePrefix(': '); },
        big: function () { insertAround('<big>', '</big>', msg('toolbarBigSample')); },
        small: function () { insertAround('<small>', '</small>', msg('toolbarSmallSample')); },
        sup: function () { insertAround('<sup>', '</sup>', msg('toolbarSupSample')); },
        sub: function () { insertAround('<sub>', '</sub>', msg('toolbarSubSample')); },
        nowiki: function () { insertAround('<nowiki>', '</nowiki>', ''); },
        line: function () {
            var start = selectionRange().start;
            var before = start > 0 && value().charAt(start - 1) !== '\n' ? '\n' : '';
            insertText(before + '----\n');
        },
        table: function () { openTableDialog(); },
        highlight: function () { toggleHighlight(); }
    };

    // ---------- 下拉菜单（标题 / 特殊字符） ----------

    var dropdowns = Array.prototype.slice.call(toolbar.querySelectorAll('.toolbar-dropdown'));

    function closeMenus() {
        dropdowns.forEach(function (dropdown) {
            dropdown.querySelector('.toolbar-menu').hidden = true;
        });
    }

    function toggleMenu(button) {
        var dropdown = button.closest('.toolbar-dropdown');
        var menu = dropdown.querySelector('.toolbar-menu');
        var open = menu.hidden;
        closeMenus();
        menu.hidden = !open;
        var body = menu.querySelector('.toolbar-menu-body');
        if (!menu.hidden && menu.classList.contains('toolbar-menu-special') && body && !body.childElementCount) {
            buildSpecialChars(body);
        }
    }

    /** 特殊字符面板（仿 MediaWiki 的符号列表）。 */
    var SPECIAL_CHARS = [
        '–', '—', '…', '·', '•', '°', '±', '×', '÷', '≠', '≤', '≥', '≈', '∞', '√',
        '←', '→', '↑', '↓', '↔', '⇒', '⇐',
        '«', '»', '“', '”', '‘', '’', '„', '‹', '›',
        '§', '¶', '†', '‡', '©', '®', '™', 'µ', '¹', '²', '³', '½', '¼', '¾',
        '€', '£', '¥', '¢',
        '、', '。', '！', '？', '；', '：', '（', '）', '《', '》', '「', '」', '『', '』', '【', '】', '〔', '〕', '～'
    ];

    function buildSpecialChars(body) {
        SPECIAL_CHARS.forEach(function (character) {
            var button = document.createElement('button');
            button.type = 'button';
            button.setAttribute('role', 'menuitem');
            button.textContent = character;
            button.addEventListener('click', function () {
                insertText(character); // 面板保持打开，方便连续插入
            });
            body.appendChild(button);
        });
    }

    // ---------- 语法高亮开关 ----------

    function highlightButton() {
        return toolbar.querySelector('.toolbar-highlight-toggle');
    }

    function paintHighlightButton() {
        var button = highlightButton();
        var editor = window.wikiCodeEditor;
        if (!button || !editor) {
            return;
        }
        var on = editor.active();
        button.classList.toggle('active', on);
        button.setAttribute('aria-pressed', on ? 'true' : 'false');
        button.title = (on ? msg('toolbarHighlightOn') : msg('toolbarHighlightOff'))
            + '（' + msg('toolbarHighlight') + '）';
    }

    function toggleHighlight() {
        var editor = window.wikiCodeEditor;
        if (!editor) {
            return;
        }
        editor.toggle();
        paintHighlightButton();
    }

    // ---------- 事件 ----------

    toolbar.addEventListener('click', function (event) {
        var button = event.target.closest ? event.target.closest('button') : null;
        if (!button || !toolbar.contains(button)) {
            return;
        }
        var heading = button.getAttribute('data-heading');
        if (heading) {
            applyHeading(parseInt(heading, 10));
            closeMenus();
            return;
        }
        var action = button.getAttribute('data-action');
        if (action === 'file') {
            openDialog();
            closeMenus();
            return;
        }
        if (action === 'heading-menu' || action === 'special-menu') {
            toggleMenu(button);
            return;
        }
        if (ACTIONS[action]) {
            ACTIONS[action]();
            closeMenus();
        }
    });

    document.addEventListener('click', function (event) {
        if (!toolbar.contains(event.target)) {
            closeMenus();
        }
    });

    // 快捷键：Ctrl+B / Ctrl+I（仿 MediaWiki）
    textarea.addEventListener('keydown', function (event) {
        shortcut(event);
    });
    // CodeMirror 激活时键盘事件在它自己的输入区里
    document.addEventListener('keydown', function (event) {
        if (event.target && event.target.closest && event.target.closest('.CodeMirror')) {
            shortcut(event);
        }
    });

    function shortcut(event) {
        if (!(event.ctrlKey || event.metaKey) || event.altKey) {
            return;
        }
        var key = (event.key || '').toLowerCase();
        if (key === 'b') {
            event.preventDefault();
            ACTIONS.bold();
        } else if (key === 'i') {
            event.preventDefault();
            ACTIONS.italic();
        }
    }

    // ---------- 对话框基础设施（上传图片 / 插入表格共用） ----------

    function dialogShell(id, titleKey) {
        var overlay = document.createElement('div');
        overlay.className = 'wiki-dialog-overlay';
        overlay.id = id;
        overlay.hidden = true;
        overlay.innerHTML =
            '<div class="wiki-dialog" role="dialog" aria-modal="true">'
            + '<div class="wiki-dialog-title"><span></span>'
            + '<button type="button" class="wiki-dialog-close" aria-label="关闭">×</button></div>'
            + '<div class="wiki-dialog-body"></div>'
            + '<div class="wiki-dialog-actions"></div></div>';
        overlay.querySelector('.wiki-dialog-title span').textContent = msg(titleKey);
        document.body.appendChild(overlay);
        overlay.querySelector('.wiki-dialog-close').addEventListener('click', function () {
            overlay.hidden = true;
        });
        overlay.addEventListener('click', function (event) {
            if (event.target === overlay) {
                overlay.hidden = true;
            }
        });
        return overlay;
    }

    // ---------- 快速上传图片弹窗 ----------

    var dialog = null;
    var dialogInput = null;
    var dialogName = null;
    var dialogPreview = null;
    var dialogStatus = null;
    var dialogUpload = null;

    function buildDialog() {
        if (dialog) {
            return;
        }
        dialog = dialogShell('wiki-file-dialog', 'uploadTitle');
        var body = dialog.querySelector('.wiki-dialog-body');
        body.innerHTML =
            '<div class="wiki-dialog-field">'
            + '<label class="wiki-dialog-label" for="wiki-file-input"></label>'
            + '<input type="file" id="wiki-file-input" accept="image/*">'
            + '</div>'
            + '<div class="wiki-dialog-preview" hidden></div>'
            + '<div class="wiki-dialog-field">'
            + '<label class="wiki-dialog-label" for="wiki-file-name"></label>'
            + '<input type="text" id="wiki-file-name">'
            + '</div>'
            + '<div class="wiki-dialog-status form-message" hidden></div>';

        dialog.querySelector('label[for="wiki-file-input"]').textContent = msg('uploadChoose');
        dialog.querySelector('label[for="wiki-file-name"]').textContent = msg('uploadName');
        var actions = dialog.querySelector('.wiki-dialog-actions');
        actions.innerHTML =
            '<button type="button" class="wiki-button wiki-dialog-upload"></button>'
            + '<button type="button" class="wiki-button wiki-dialog-cancel"></button>';
        dialog.querySelector('.wiki-dialog-upload').textContent = msg('uploadConfirm');
        dialog.querySelector('.wiki-dialog-cancel').textContent = msg('uploadCancel');

        dialogInput = dialog.querySelector('#wiki-file-input');
        dialogName = dialog.querySelector('#wiki-file-name');
        dialogPreview = dialog.querySelector('.wiki-dialog-preview');
        dialogStatus = dialog.querySelector('.wiki-dialog-status');
        dialogUpload = dialog.querySelector('.wiki-dialog-upload');

        dialog.querySelector('.wiki-dialog-cancel').addEventListener('click', closeDialog);
        dialogInput.addEventListener('change', function () {
            var file = dialogInput.files && dialogInput.files[0];
            if (file && !dialogName.value) {
                dialogName.placeholder = file.name;
            }
            showPreview(file);
        });
        dialogUpload.addEventListener('click', upload);
    }

    /** 选择文件后立即预览（图片文件；非图片给文件名标签）。 */
    function showPreview(file) {
        if (!dialogPreview) {
            return;
        }
        dialogPreview.innerHTML = '';
        dialogPreview.hidden = true;
        if (!file) {
            return;
        }
        if (file.type && file.type.indexOf('image/') === 0) {
            var figure = document.createElement('figure');
            var image = document.createElement('img');
            image.alt = file.name;
            image.src = URL.createObjectURL(file);
            var caption = document.createElement('figcaption');
            caption.textContent = file.name;
            figure.appendChild(image);
            figure.appendChild(caption);
            dialogPreview.appendChild(figure);
            dialogPreview.hidden = false;
        } else {
            var note = document.createElement('div');
            note.className = 'wiki-dialog-file-note';
            note.textContent = msg('toolbarFilePreview') + ' ' + file.name;
            dialogPreview.appendChild(note);
            dialogPreview.hidden = false;
        }
    }

    function showStatus(text, error) {
        if (!dialogStatus) {
            return;
        }
        dialogStatus.textContent = text;
        dialogStatus.className = 'wiki-dialog-status form-message' + (error ? '' : ' success');
        dialogStatus.hidden = !text;
    }

    function openDialog() {
        buildDialog();
        dialogInput.value = '';
        dialogInput.placeholder = '';
        dialogName.value = '';
        dialogName.placeholder = '';
        dialogPreview.hidden = true;
        dialogPreview.innerHTML = '';
        showStatus('', false);
        dialog.hidden = false;
        dialogInput.focus();
    }

    function closeDialog() {
        if (dialog) {
            dialog.hidden = true;
        }
    }

    function upload() {
        uploadSingle();
    }

    /** 单文件上传（默认）：上传成功后插入 [[文件:名|thumb]] 并关闭对话框。
     *  multi-upload 插件加载时会接管这个按钮（多选 + 批量插入）。 */
    function uploadSingle() {
        var file = dialogInput.files && dialogInput.files[0];
        if (!file) {
            showStatus(msg('toolbarFileEmpty'), true);
            return;
        }
        dialogUpload.disabled = true;
        dialogUpload.textContent = msg('uploading');
        showStatus('', false);
        var body = new FormData();
        body.append('file', file);
        if (dialogName.value.trim()) {
            body.append('name', dialogName.value.trim());
        }
        fetch('/api/files/upload', {
            method: 'POST',
            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
            body: body
        }).then(function (response) {
            return response.json().catch(function () {
                return null;
            });
        }).then(function (result) {
            dialogUpload.disabled = false;
            dialogUpload.textContent = msg('uploadConfirm');
            if (result && result.code === 200 && result.data && result.data.pending) {
                // 审核：文件已进队列，不插入链接（通过前文件不存在）
                showStatus(result.message || MESSAGES.moderationEditPending || 'moderation.editPendingBackend', false);
                return;
            }
            if (result && result.code === 200 && result.data && result.data.name) {
                insertFileLink(result.data.name);
                closeDialog();
                return;
            }
            showStatus((result && result.message) || msg('uploadFailed'), true);
        }).catch(function () {
            dialogUpload.disabled = false;
            dialogUpload.textContent = msg('uploadConfirm');
            showStatus(msg('networkError'), true);
        });
    }

    /** 在光标处插入 [[文件:名|thumb]]（光标停在 ]] 之前，方便补 |说明）。 */
    function insertFileLink(name) {
        var link = '[[文件:' + name + '|thumb]]';
        var range = selectionRange();
        replaceRange(link, range.start, range.end,
            range.start + link.length - 2, range.start + link.length - 2);
    }

    // ---------- 插入表格对话框（仿 MediaWiki 的「插入表格」） ----------

    var tableDialog = null;
    var tableRows = null;
    var tableCols = null;
    var tableHeader = null;
    var tableCaption = null;
    var tableWikitable = null;

    function buildTableDialog() {
        if (tableDialog) {
            return;
        }
        tableDialog = dialogShell('wiki-table-dialog', 'toolbarTableTitle');
        var body = tableDialog.querySelector('.wiki-dialog-body');
        body.innerHTML =
            '<div class="wiki-dialog-grid">'
            + '<label class="wiki-dialog-label" for="wiki-table-rows"></label>'
            + '<input type="number" id="wiki-table-rows" min="1" max="30" value="3">'
            + '<label class="wiki-dialog-label" for="wiki-table-cols"></label>'
            + '<input type="number" id="wiki-table-cols" min="1" max="20" value="3">'
            + '<label class="wiki-dialog-label" for="wiki-table-header"></label>'
            + '<select id="wiki-table-header">'
            + '<option value="none"></option>'
            + '<option value="row"></option>'
            + '<option value="col"></option>'
            + '<option value="both"></option>'
            + '</select>'
            + '</div>'
            + '<div class="wiki-dialog-field">'
            + '<label class="wiki-dialog-label" for="wiki-table-caption"></label>'
            + '<input type="text" id="wiki-table-caption">'
            + '<div class="wiki-hint"></div>'
            + '</div>'
            + '<label class="wiki-dialog-check"><input type="checkbox" id="wiki-table-wikitable" checked>'
            + '<span></span></label>';

        body.querySelector('label[for="wiki-table-rows"]').textContent = msg('toolbarTableRows');
        body.querySelector('label[for="wiki-table-cols"]').textContent = msg('toolbarTableCols');
        body.querySelector('label[for="wiki-table-header"]').textContent = msg('toolbarTableHeader');
        body.querySelector('label[for="wiki-table-caption"]').textContent = msg('toolbarTableCaption');
        body.querySelector('.wiki-hint').textContent = msg('toolbarTableCaptionHint');
        body.querySelector('.wiki-dialog-check span').textContent = msg('toolbarTableWikitable');
        var options = body.querySelectorAll('#wiki-table-header option');
        options[0].textContent = msg('toolbarTableHeaderNone');
        options[1].textContent = msg('toolbarTableHeaderRow');
        options[2].textContent = msg('toolbarTableHeaderCol');
        options[3].textContent = msg('toolbarTableHeaderBoth');

        var actions = tableDialog.querySelector('.wiki-dialog-actions');
        actions.innerHTML =
            '<button type="button" class="wiki-button wiki-table-insert"></button>'
            + '<button type="button" class="wiki-button wiki-dialog-cancel"></button>';
        actions.querySelector('.wiki-table-insert').textContent = msg('toolbarTableInsert');
        actions.querySelector('.wiki-dialog-cancel').textContent = msg('uploadCancel');
        actions.querySelector('.wiki-dialog-cancel').addEventListener('click', function () {
            tableDialog.hidden = true;
        });
        actions.querySelector('.wiki-table-insert').addEventListener('click', insertTableFromDialog);

        tableRows = body.querySelector('#wiki-table-rows');
        tableCols = body.querySelector('#wiki-table-cols');
        tableHeader = body.querySelector('#wiki-table-header');
        tableCaption = body.querySelector('#wiki-table-caption');
        tableWikitable = body.querySelector('#wiki-table-wikitable');
    }

    function openTableDialog() {
        buildTableDialog();
        tableRows.value = '3';
        tableCols.value = '3';
        tableHeader.value = 'none';
        tableCaption.value = '';
        tableWikitable.checked = true;
        tableDialog.hidden = false;
        tableRows.focus();
    }

    /** 生成 MediaWiki 风格的表格 wikitext（行间 |-，单元格 ||，表头 !）。 */
    function buildTable(rows, cols, header, caption, wikitable) {
        var headerText = msg('toolbarTableHeader');
        var cellText = msg('toolbarTableCell');
        var lines = [];
        lines.push('{|' + (wikitable ? ' class="wikitable"' : ''));
        if (caption) {
            lines.push('|+ ' + caption);
        }
        for (var row = 0; row < rows; row++) {
            lines.push('|-');
            var rowIsHeader = header === 'row' || header === 'both';
            var cells = [];
            for (var col = 0; col < cols; col++) {
                var isHeader = (rowIsHeader && row === 0) || (header === 'col' && col === 0)
                    || (header === 'both' && col === 0);
                // MediaWiki 风格：每行第一个单元格带 ! / | 前缀，其余用 !! / || 连接（不再带前缀）
                var prefix = col === 0 ? (isHeader ? '! ' : '| ') : '';
                cells.push(prefix + (isHeader ? headerText : cellText));
            }
            lines.push(cells.join(row === 0 && rowIsHeader ? ' !! ' : ' || '));
        }
        lines.push('|}');
        return lines.join('\n');
    }

    function insertTableFromDialog() {
        var rows = Math.max(1, Math.min(30, parseInt(tableRows.value, 10) || 3));
        var cols = Math.max(1, Math.min(20, parseInt(tableCols.value, 10) || 3));
        var text = buildTable(rows, cols, tableHeader.value, tableCaption.value.trim(), tableWikitable.checked);
        var range = selectionRange();
        var start = range.start;
        var prefix = start > 0 && value().charAt(start - 1) !== '\n' ? '\n' : '';
        replaceRange(prefix + text + '\n', start, range.end, start + prefix.length + text.length + 1,
            start + prefix.length + text.length + 1);
        tableDialog.hidden = true;
    }

    // ---------- 拖入文件（multi-upload 插件接管时由其处理，这里兜底单选） ----------

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            closeMenus();
            closeDialog();
            if (tableDialog) {
                tableDialog.hidden = true;
            }
        }
    });

    // 初始状态
    paintHighlightButton();
    if (window.wikiCodeEditor) {
        // 桥在 code-editor.js 里晚于本脚本初始化时也能反映状态
        setTimeout(paintHighlightButton, 0);
    }
})();
