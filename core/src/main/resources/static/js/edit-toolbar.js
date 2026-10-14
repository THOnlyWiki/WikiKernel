/*
 * 编辑辅助工具栏（仿 MediaWiki WikiEditor 默认皮肤）：
 *  - 插入：粗体 / 斜体 / 内部链接 / 外部链接 / 参考文献 / 签名 / 标题 / 列表 / 缩进 / nowiki / 水平线 / 表格
 *  - 特殊字符面板；Ctrl+B / Ctrl+I 快捷键
 *  - 快速上传图片：弹窗选择文件 → POST /api/files/upload → 插入 [[文件:名称|thumb]]
 * 所有插入都会触发 input 事件，编辑草稿保护（edit-draft.js）随之自动保存。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var CONFIG = window.WIKI_CONFIG || {};
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

    function replaceRange(text, start, end, selectionStart, selectionEnd) {
        textarea.setRangeText(text, start, end, 'end');
        textarea.focus();
        if (typeof selectionStart === 'number') {
            textarea.setSelectionRange(selectionStart, typeof selectionEnd === 'number' ? selectionEnd : selectionStart);
        }
        notify();
    }

    /** 包裹选区（无选区时插入示例文本并选中，仿 MediaWiki）。 */
    function insertAround(before, after, sample) {
        var start = textarea.selectionStart;
        var end = textarea.selectionEnd;
        var selected = textarea.value.slice(start, end);
        var inner = selected || sample || '';
        var innerStart = start + before.length;
        replaceRange(before + inner + after, start, end, innerStart, innerStart + inner.length);
    }

    function insertText(text) {
        var start = textarea.selectionStart;
        var end = textarea.selectionEnd;
        replaceRange(text, start, end, start + text.length, start + text.length);
    }

    /** 当前选区覆盖的整行范围。 */
    function lineRange() {
        var value = textarea.value;
        var start = value.lastIndexOf('\n', textarea.selectionStart - 1) + 1;
        var end = value.indexOf('\n', textarea.selectionEnd);
        if (end < 0) {
            end = value.length;
        }
        return { start: start, end: end, text: value.slice(start, end) };
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

    function insertTable() {
        var header = msg('toolbarTableHeader');
        var cell = msg('toolbarTableCell');
        insertText('{| class="wikitable"\n|-\n! ' + header + ' !! ' + header
            + '\n|-\n| ' + cell + ' || ' + cell + '\n|}');
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
        nowiki: function () { insertAround('<nowiki>', '</nowiki>', ''); },
        line: function () {
            var start = textarea.selectionStart;
            var before = start > 0 && textarea.value.charAt(start - 1) !== '\n' ? '\n' : '';
            insertText(before + '----\n');
        },
        table: insertTable
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
        if (!menu.hidden && menu.classList.contains('toolbar-menu-special') && !menu.childElementCount) {
            buildSpecialChars(menu);
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

    function buildSpecialChars(menu) {
        SPECIAL_CHARS.forEach(function (character) {
            var button = document.createElement('button');
            button.type = 'button';
            button.textContent = character;
            button.addEventListener('click', function () {
                insertText(character); // 面板保持打开，方便连续插入
            });
            menu.appendChild(button);
        });
    }

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
    });

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
        dialog = document.createElement('div');
        dialog.className = 'wiki-dialog-overlay';
        dialog.id = 'wiki-file-dialog';
        dialog.hidden = true;
        dialog.innerHTML =
            '<div class="wiki-dialog" role="dialog" aria-modal="true">'
            + '<div class="wiki-dialog-title"><span></span>'
            + '<button type="button" class="wiki-dialog-close" aria-label="关闭">×</button></div>'
            + '<div class="wiki-dialog-body">'
            + '<label class="wiki-dialog-label" for="wiki-file-input"></label>'
            + '<input type="file" id="wiki-file-input" accept="image/*">'
            + '<div class="wiki-dialog-preview" hidden><img alt="预览"></div>'
            + '<label class="wiki-dialog-label" for="wiki-file-name"></label>'
            + '<input type="text" id="wiki-file-name">'
            + '<div class="wiki-dialog-status form-message" hidden></div>'
            + '</div>'
            + '<div class="wiki-dialog-actions">'
            + '<button type="button" class="wiki-button wiki-dialog-upload"></button>'
            + '<button type="button" class="wiki-button wiki-dialog-cancel"></button>'
            + '</div></div>';
        document.body.appendChild(dialog);

        dialog.querySelector('.wiki-dialog-title span').textContent = msg('uploadTitle');
        dialog.querySelector('label[for="wiki-file-input"]').textContent = msg('uploadChoose');
        dialog.querySelector('label[for="wiki-file-name"]').textContent = msg('uploadName');
        dialog.querySelector('.wiki-dialog-upload').textContent = msg('uploadConfirm');
        dialog.querySelector('.wiki-dialog-cancel').textContent = msg('uploadCancel');

        dialogInput = dialog.querySelector('#wiki-file-input');
        dialogName = dialog.querySelector('#wiki-file-name');
        dialogPreview = dialog.querySelector('.wiki-dialog-preview');
        dialogStatus = dialog.querySelector('.wiki-dialog-status');
        dialogUpload = dialog.querySelector('.wiki-dialog-upload');

        dialog.querySelector('.wiki-dialog-close').addEventListener('click', closeDialog);
        dialog.querySelector('.wiki-dialog-cancel').addEventListener('click', closeDialog);
        dialog.addEventListener('click', function (event) {
            if (event.target === dialog) {
                closeDialog();
            }
        });
        dialogInput.addEventListener('change', function () {
            var file = dialogInput.files && dialogInput.files[0];
            if (file && !dialogName.value) {
                dialogName.placeholder = file.name;
            }
            if (file && file.type && file.type.indexOf('image/') === 0) {
                dialogPreview.querySelector('img').src = URL.createObjectURL(file);
                dialogPreview.hidden = false;
            } else {
                dialogPreview.hidden = true;
            }
        });
        dialogUpload.addEventListener('click', upload);
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
        dialogPreview.querySelector('img').removeAttribute('src');
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

    /** 单文件上传（默认）：上传成功后插入 [[文件:名|thumb]] 并关闭对话框 */
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
                var link = '[[文件:' + result.data.name + '|thumb]]';
                var start = textarea.selectionStart;
                var end = textarea.selectionEnd;
                // 光标放在 ]] 之前，方便直接补 |说明
                replaceRange(link, start, end, start + link.length - 2, start + link.length - 2);
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


    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            closeMenus();
            closeDialog();
        }
    });
})();
