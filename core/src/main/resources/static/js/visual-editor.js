/*
 * 可视化编辑器（编辑页，仿 MediaWiki 的可视化编辑体验）：
 *  - 只从编辑框里的 Wikitext 出发：POST /api/pages/visual/render
 *    （服务端先分词再渲染，并剥掉目录 / 分类栏等「后生成」内容）
 *  - 独占段落的模板直接渲染成「预览块」，点击打开模板对话框：
 *    参数表单（服务端从模板源代码发现全部 {{{参数}}}）、源代码编辑、实时预览；
 *    行内模板 / 引用 / 文件 / 复杂表格等显示为源码块，点击弹窗编辑源码
 *  - 「应用更改」调用 /api/pages/visual/serialize 安全地序列化回 Wikitext；
 *    遇到不支持的结构会整体拒绝（绝不写回半成品），仍可用源代码编辑
 */
(function () {
    'use strict';

    var openButton = document.getElementById('ve-open');
    var panel = document.getElementById('visual-editor');
    if (!panel) {
        return;
    }
    var form = document.getElementById('wiki-edit-form');
    var textarea = form ? form.querySelector('textarea[name="content"]') : null;
    var surface = document.getElementById('visual-editor-surface');
    var applyButton = document.getElementById('ve-apply');
    var exitButton = document.getElementById('ve-exit');
    var messageBox = document.getElementById('visual-editor-message');
    var toolbar = document.getElementById('wiki-editor-toolbar');
    var preview = document.getElementById('wiki-preview');
    var submitButton = form ? form.querySelector('button[type="submit"]') : null;
    var title = form ? (form.getAttribute('data-title') || '') : '';
    var messages = window.WIKI_MESSAGES || {};
    var tokens = [];
    var active = false;
    var dirty = false;
    // ?veaction=edit 打开时页面加载即进入可视化编辑（与 MediaWiki 的 URL 参数一致）
    var autoOpen = panel.getAttribute('data-ve-auto') === '1';

    /** URL 反映当前模式：可视化 = ?veaction=edit，源代码 = ?action=edit（仿 MediaWiki）。 */
    function setUrl(param, value) {
        try {
            var url = new URL(window.location.href);
            if (value === null) {
                url.searchParams.delete(param);
            } else {
                url.searchParams.set(param, value);
            }
            window.history.replaceState(null, '', url.toString());
        } catch (error) {
            /* 老浏览器忽略 */
        }
    }

    function msg(key) {
        return messages[key] || key;
    }

    function showMessage(text, success) {
        if (!messageBox) {
            return;
        }
        messageBox.textContent = text || '';
        messageBox.className = 'form-message' + (success ? ' success' : '');
        messageBox.hidden = !text;
    }

    function post(path, params) {
        return fetch(path, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest',
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
            },
            body: new URLSearchParams(params)
        }).then(function (response) {
            return response.json();
        });
    }

    function setEditChromeVisible(visible) {
        if (textarea) {
            textarea.hidden = !visible;
        }
        if (toolbar) {
            toolbar.hidden = !visible;
        }
        if (preview) {
            preview.hidden = true;
        }
        if (submitButton) {
            submitButton.disabled = !visible;
        }
    }

    function close() {
        active = false;
        dirty = false;
        panel.hidden = true;
        surface.innerHTML = '';
        tokens = [];
        setEditChromeVisible(true);
        setUrl('veaction', null);
        setUrl('action', 'edit');
        showMessage('');
    }

    function summarize(source) {
        var value = String(source || '').replace(/\s+/g, ' ').trim();
        return value.length > 40 ? value.slice(0, 40) + '…' : value;
    }

    function open() {
        if (!textarea) {
            return;
        }
        try {
            // 让回车插入 <p> 而不是 <div>（不同浏览器默认不同；序列化两端都兼容）
            document.execCommand('defaultParagraphSeparator', false, 'p');
        } catch (error) {
            /* 部分浏览器不支持：忽略 */
        }
        openButton && (openButton.disabled = true);
        showMessage(msg('veLoading'), true);
        post('/api/pages/visual/render', { title: title, content: textarea.value })
            .then(function (result) {
                openButton && (openButton.disabled = false);
                if (!result || result.code !== 200) {
                    showMessage((result && result.message) || msg('veFailed'));
                    return;
                }
                tokens = (result.data && result.data.tokens) || [];
                surface.innerHTML = (result.data && result.data.html) || '';
                active = true;
                dirty = false;
                panel.hidden = false;
                setEditChromeVisible(false);
                setUrl('action', null);
                setUrl('veaction', 'edit');
                showMessage('');
                surface.focus();
            })
            .catch(function () {
                openButton && (openButton.disabled = false);
                showMessage(msg('veFailed'));
            });
    }

    function apply() {
        if (!active || !textarea) {
            return;
        }
        applyButton.disabled = true;
        showMessage(msg('veApplying'), true);
        post('/api/pages/visual/serialize', {
            title: title,
            html: surface.innerHTML,
            tokens: JSON.stringify(tokens)
        }).then(function (result) {
            applyButton.disabled = false;
            if (!result || result.code !== 200) {
                showMessage((result && result.message) || msg('veFailed'));
                return;
            }
            textarea.value = (result.data && result.data.text) || '';
            close();
            showMessage(msg('veApplied'), true);
        }).catch(function () {
            applyButton.disabled = false;
            showMessage(msg('veFailed'));
        });
    }

    // ==================== 源码块 / 模板对话框 ====================

    var dialog = null;
    var dialogState = { id: -1, template: false, source: '', name: '', label: '', exists: false,
        params: [], previewHtml: '', tab: 'params' };

    function buildDialog() {
        var box = document.createElement('div');
        box.className = 've-dialog';
        box.hidden = true;
        box.innerHTML = '<div class="ve-dialog-backdrop"></div>'
            + '<div class="ve-dialog-box" role="dialog" aria-modal="true">'
            + '<div class="ve-dialog-head"><span class="ve-dialog-title"></span>'
            + '<button type="button" class="ve-dialog-close" title="' + msg('veDialogClose') + '">×</button></div>'
            + '<div class="ve-dialog-tabs" hidden>'
            + '<button type="button" data-ve-tab="params"></button>'
            + '<button type="button" data-ve-tab="source"></button></div>'
            + '<div class="ve-dialog-body">'
            + '<div class="ve-dialog-pane ve-pane-params" hidden>'
            + '<table class="ve-param-table"><tbody></tbody></table>'
            + '<button type="button" class="ve-param-add wiki-button"></button>'
            + '<p class="wiki-hint ve-param-hint"></p>'
            + '</div>'
            + '<div class="ve-dialog-pane ve-pane-source" hidden>'
            + '<textarea class="ve-source-input" rows="10" spellcheck="false"></textarea>'
            + '</div>'
            + '<div class="ve-dialog-preview" hidden>'
            + '<div class="ve-dialog-preview-title"></div>'
            + '<div class="ve-preview-body wiki-content"></div>'
            + '</div>'
            + '</div>'
            + '<p class="ve-dialog-message form-message" hidden></p>'
            + '<div class="ve-dialog-actions">'
            + '<button type="button" class="ve-dialog-refresh wiki-button"></button>'
            + '<button type="button" class="ve-dialog-apply wiki-button"></button>'
            + '<button type="button" class="ve-dialog-cancel wiki-button"></button>'
            + '</div>'
            + '</div>';
        document.body.appendChild(box);

        box.querySelector('.ve-dialog-close').addEventListener('click', closeDialog);
        box.querySelector('.ve-dialog-cancel').addEventListener('click', closeDialog);
        box.querySelector('.ve-dialog-backdrop').addEventListener('click', closeDialog);
        box.querySelector('.ve-dialog-refresh').addEventListener('click', refreshCurrentTab);
        box.querySelector('.ve-dialog-apply').addEventListener('click', applyDialog);
        box.querySelector('.ve-param-add').addEventListener('click', function () {
            dialogState.params.push({ name: '', value: '', positional: false, present: true, known: false });
            renderParams();
            var rows = box.querySelectorAll('.ve-param-row');
            var last = rows[rows.length - 1];
            if (last) {
                var input = last.querySelector('.ve-param-name');
                input && input.focus();
            }
        });
        Array.prototype.forEach.call(box.querySelectorAll('[data-ve-tab]'), function (button) {
            button.addEventListener('click', function () {
                setTab(button.getAttribute('data-ve-tab'));
            });
        });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && dialog && !dialog.hidden) {
                closeDialog();
            }
        });
        return box;
    }

    function dialogMessage(text, success) {
        var box = dialog.querySelector('.ve-dialog-message');
        box.textContent = text || '';
        box.className = 've-dialog-message form-message' + (success ? ' success' : '');
        box.hidden = !text;
    }

    function openTokenDialog(chip) {
        var id = parseInt(chip.getAttribute('data-token'), 10);
        if (isNaN(id) || id < 0 || id >= tokens.length) {
            return;
        }
        if (!dialog) {
            dialog = buildDialog();
        }
        dialogState.id = id;
        dialogState.template = chip.classList.contains('ve-template');
        dialogState.source = tokens[id];
        dialogState.previewHtml = '';
        if (dialogState.template) {
            openTemplateDialog();
        } else {
            openSourceDialog();
        }
    }

    function showDialog(titleText) {
        dialog.querySelector('.ve-dialog-title').textContent = titleText;
        dialog.hidden = false;
    }

    function closeDialog() {
        if (dialog) {
            dialog.hidden = true;
        }
    }

    function openSourceDialog() {
        var box = dialog;
        dialogState.tab = 'source';
        box.querySelector('.ve-dialog-tabs').hidden = true;
        box.querySelector('.ve-pane-params').hidden = true;
        box.querySelector('.ve-pane-source').hidden = false;
        box.querySelector('.ve-dialog-preview').hidden = true;
        box.querySelector('.ve-dialog-refresh').hidden = true;
        box.querySelector('.ve-dialog-apply').hidden = false;
        box.querySelector('.ve-dialog-apply').textContent = msg('veDialogApply');
        box.querySelector('.ve-dialog-cancel').textContent = msg('veDialogCancel');
        box.querySelector('.ve-source-input').value = dialogState.source;
        dialogMessage('');
        showDialog(msg('veTokenTitle'));
        box.querySelector('.ve-source-input').focus();
    }

    function openTemplateDialog() {
        var box = dialog;
        box.querySelector('.ve-dialog-tabs').hidden = false;
        box.querySelector('[data-ve-tab="params"]').textContent = msg('veDialogParams');
        box.querySelector('[data-ve-tab="source"]').textContent = msg('veDialogSource');
        box.querySelector('.ve-dialog-refresh').hidden = false;
        box.querySelector('.ve-dialog-refresh').textContent = msg('veDialogPreview');
        box.querySelector('.ve-dialog-apply').hidden = false;
        box.querySelector('.ve-dialog-apply').textContent = msg('veDialogApply');
        box.querySelector('.ve-dialog-cancel').textContent = msg('veDialogCancel');
        box.querySelector('.ve-param-add').textContent = msg('veDialogAddParam');
        box.querySelector('.ve-param-hint').textContent = msg('veDialogParamHint');
        box.querySelector('.ve-dialog-preview-title').textContent = msg('veDialogPreviewTitle');
        box.querySelector('.ve-dialog-preview').hidden = false;
        setTab('params');
        dialogMessage(msg('veLoading'), true);
        showDialog(dialogState.name || '');
        requestTemplate({ source: dialogState.source });
    }

    function setTab(tab) {
        var box = dialog;
        dialogState.tab = tab;
        Array.prototype.forEach.call(box.querySelectorAll('[data-ve-tab]'), function (button) {
            button.classList.toggle('selected', button.getAttribute('data-ve-tab') === tab);
        });
        box.querySelector('.ve-pane-params').hidden = tab !== 'params';
        var sourcePane = box.querySelector('.ve-pane-source');
        sourcePane.hidden = tab !== 'source';
        if (tab === 'source') {
            box.querySelector('.ve-source-input').value = dialogState.source;
        }
    }

    /** 服务端模板对话框接口：解析调用（发现参数 + 预览）或按参数重建。 */
    function requestTemplate(params) {
        var body = { title: title };
        if (params.source !== undefined) {
            body.source = params.source;
        }
        if (params.params !== undefined) {
            body.name = dialogState.name;
            body.params = params.params;
        }
        return post('/api/pages/visual/template', body).then(function (result) {
            if (!result || result.code !== 200) {
                dialogMessage((result && result.message) || msg('veDialogPreviewFailed'), false);
                return false;
            }
            applyTemplateData(result.data || {});
            return true;
        }).catch(function () {
            dialogMessage(msg('veDialogPreviewFailed'), false);
            return false;
        });
    }

    function applyTemplateData(data) {
        dialogState.source = data.source || dialogState.source;
        dialogState.name = data.name || dialogState.name;
        dialogState.exists = !!data.exists;
        dialogState.params = data.params || [];
        dialogState.previewHtml = data.previewHtml || '';
        dialogState.label = dialogState.name.indexOf('#') === 0
            ? dialogState.name
            : msg('veTemplatePrefix') + dialogState.name;
        dialog.querySelector('.ve-source-input').value = dialogState.source;
        renderParams();
        dialog.querySelector('.ve-preview-body').innerHTML = dialogState.previewHtml;
        var missing = dialog.querySelector('.ve-dialog-missing');
        var showMissing = !dialogState.exists && dialogState.name.indexOf('#') !== 0;
        if (!missing) {
            missing = document.createElement('p');
            missing.className = 'wiki-warning ve-dialog-missing';
            dialog.querySelector('.ve-dialog-body').insertBefore(missing,
                dialog.querySelector('.ve-dialog-body').firstChild);
        }
        missing.hidden = !showMissing;
        missing.textContent = showMissing
            ? msg('veDialogTemplateMissing')
            : '';
        var head = dialog.querySelector('.ve-dialog-title');
        head.textContent = dialogState.label;
        dialogMessage('');
    }

    function renderParams() {
        var tbody = dialog.querySelector('.ve-param-table tbody');
        tbody.innerHTML = '';
        Array.prototype.forEach.call(dialogState.params, function (param, index) {
            var row = document.createElement('tr');
            row.className = 've-param-row' + (param.known ? ' ve-param-known' : '')
                + (param.present ? ' ve-param-present' : '');
            row.setAttribute('data-present', param.present ? '1' : '0');
            row.setAttribute('data-param', String(index));

            var nameCell = document.createElement('td');
            nameCell.className = 've-param-name-cell';
            var nameInput = document.createElement('input');
            nameInput.type = 'text';
            nameInput.className = 've-param-name';
            nameInput.value = param.name;
            nameInput.placeholder = msg('veDialogParamName');
            if (param.known) {
                nameInput.title = msg('veDialogParamKnown');
            }
            nameCell.appendChild(nameInput);
            row.appendChild(nameCell);

            var valueCell = document.createElement('td');
            valueCell.className = 've-param-value-cell';
            var valueInput = document.createElement('input');
            valueInput.type = 'text';
            valueInput.className = 've-param-value';
            valueInput.value = param.value || '';
            valueInput.placeholder = msg('veDialogParamUnset');
            valueCell.appendChild(valueInput);
            row.appendChild(valueCell);

            var actionCell = document.createElement('td');
            actionCell.className = 've-param-action-cell';
            var remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 've-param-remove';
            remove.title = msg('veDialogParamRemove');
            remove.textContent = '×';
            remove.addEventListener('click', function () {
                dialogState.params.splice(index, 1);
                renderParams();
            });
            actionCell.appendChild(remove);
            row.appendChild(actionCell);

            tbody.appendChild(row);
        });
    }

    /** 参数表单 → 参数数组（无名但有值的行 = 下一个位置参数）。 */
    function collectParams() {
        var rows = dialog.querySelectorAll('.ve-param-row');
        var next = 1;
        var used = {};
        var params = [];
        Array.prototype.forEach.call(rows, function (row) {
            var name = row.querySelector('.ve-param-name').value.trim();
            var value = row.querySelector('.ve-param-value').value;
            if (/^\d+$/.test(name)) {
                used[parseInt(name, 10)] = true;
            }
            params.push({
                name: name,
                value: value,
                positional: /^\d+$/.test(name),
                present: row.getAttribute('data-present') === '1'
            });
        });
        Array.prototype.forEach.call(params, function (param) {
            if (param.name || !param.value) {
                return;
            }
            while (used[next]) {
                next++;
            }
            param.name = String(next);
            param.positional = true;
            used[next] = true;
        });
        return params.filter(function (param) {
            return param.name || param.value;
        });
    }

    /** 预览 / 刷新：参数页 → 按表单重建源码；源代码页 → 解析源码。 */
    function refreshCurrentTab() {
        if (!dialogState.template) {
            return;
        }
        if (dialogState.tab === 'source') {
            return requestTemplate({ source: dialog.querySelector('.ve-source-input').value });
        }
        return requestTemplate({ params: JSON.stringify(collectParams()) });
    }

    function applyDialog() {
        var proceed = dialogState.template
            ? refreshCurrentTab()
            : Promise.resolve(true);
        proceed.then(function (ok) {
            if (ok === false) {
                return;
            }
            if (!dialogState.template) {
                dialogState.source = dialog.querySelector('.ve-source-input').value;
            }
            tokens[dialogState.id] = dialogState.source;
            updateChip();
            dirty = true;
            closeDialog();
        });
    }

    function updateChip() {
        var chip = surface.querySelector('.ve-token[data-token="' + dialogState.id + '"]');
        if (!chip) {
            return;
        }
        chip.setAttribute('title', dialogState.source);
        if (dialogState.template) {
            var render = chip.querySelector('.ve-template-render');
            if (render) {
                render.innerHTML = dialogState.previewHtml;
                var badge = chip.querySelector('.ve-template-badge');
                badge && (badge.textContent = dialogState.label);
            } else {
                chip.textContent = dialogState.label;
            }
        } else {
            chip.textContent = summarize(dialogState.source);
        }
    }

    if (openButton) {
        openButton.addEventListener('click', open);
    }
    applyButton.addEventListener('click', apply);
    exitButton.addEventListener('click', function () {
        if (dirty && !window.confirm(msg('veDiscardConfirm'))) {
            return;
        }
        close();
    });

    // 工具栏命令
    Array.prototype.forEach.call(panel.querySelectorAll('[data-ve-command]'), function (button) {
        button.addEventListener('mousedown', function (event) {
            event.preventDefault(); // 保持选区
        });
        button.addEventListener('click', function () {
            var command = button.getAttribute('data-ve-command');
            surface.focus();
            if (command === 'bold' || command === 'italic') {
                document.execCommand(command, false, null);
            } else if (command === 'h2') {
                document.execCommand('formatBlock', false, 'H2');
            } else if (command === 'h3') {
                document.execCommand('formatBlock', false, 'H3');
            } else if (command === 'ul') {
                document.execCommand('insertUnorderedList', false, null);
            } else if (command === 'ol') {
                document.execCommand('insertOrderedList', false, null);
            } else if (command === 'hr') {
                document.execCommand('insertHorizontalRule', false, null);
            } else if (command === 'link') {
                insertLink();
            }
            dirty = true;
        });
    });

    function insertLink() {
        var target = window.prompt(msg('toolbarLinkSample'), '');
        if (!target) {
            return;
        }
        var selection = window.getSelection();
        if (!selection || selection.isCollapsed) {
            return;
        }
        var href;
        if (/^(https?|ftp):\/\//i.test(target)) {
            href = target;
        } else {
            href = '/wiki/' + encodeURIComponent(target.replace(/ /g, '_'));
        }
        document.execCommand('createLink', false, href);
        // 浏览器可能直接写入 href 原值：统一修正为站内路径 / 原始 URL
        var anchor = selection.anchorNode;
        var element = anchor && (anchor.nodeType === 1 ? anchor : anchor.parentNode);
        while (element && element.tagName !== 'A') {
            element = element.parentNode;
        }
        if (element) {
            element.setAttribute('href', href);
        }
    }

    // 源码块 / 模板块：点击打开对话框（模板块里是渲染预览，链接不跳转）
    surface.addEventListener('click', function (event) {
        var chip = event.target && event.target.closest ? event.target.closest('.ve-token') : null;
        if (!chip) {
            return;
        }
        event.preventDefault();
        openTokenDialog(chip);
    });

    // 只接受纯文本粘贴：避免把渲染结果 / 外部 HTML 带进编辑内容
    surface.addEventListener('paste', function (event) {
        var text = event.clipboardData ? event.clipboardData.getData('text/plain') : '';
        if (text === null || text === undefined) {
            return;
        }
        event.preventDefault();
        document.execCommand('insertText', false, text);
        dirty = true;
    });

    surface.addEventListener('input', function () {
        dirty = true;
    });

    window.addEventListener('beforeunload', function (event) {
        if (active && dirty) {
            event.preventDefault();
            event.returnValue = '';
        }
    });

    // ?veaction=edit：页面加载即打开可视化编辑（URL 参数控制，与 MediaWiki 一致）
    if (autoOpen) {
        open();
    }
})();
