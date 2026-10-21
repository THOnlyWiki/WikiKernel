/*
 * 滥用过滤器交互（特殊:滥用过滤器 / 特殊:滥用日志）：
 *  - 条件编辑器：CodeMirror + 简易 abusefilter 语法高亮（本地内置，无 CDN）
 *  - 保存过滤器 / 恢复历史版本 / 测试过滤器 / 隐藏日志条目（fetch @RestController）
 * 页面不存在对应元素时脚本不做任何事。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    function showMessage(box, text) {
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message success';
        box.hidden = false;
    }

    function showError(box, text) {
        if (!box) {
            return;
        }
        box.textContent = text || MESSAGES.operationFailed || '操作失败，请稍后重试';
        box.className = 'form-message';
        box.hidden = false;
    }

    function post(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: body
        }).then(function (response) {
            return response.json();
        });
    }

    // ==================== 条件编辑器（CodeMirror 简易语法高亮） ====================

    var editors = {};
    var plainMode = {};

    if (typeof CodeMirror !== 'undefined') {
        CodeMirror.defineMode('abusefilter', function () {
            var KEYWORDS = /^(in|contains|like|rlike|irlike|and|or|not|true|false|null)$/;
            var FUNCTIONS = /^(lcase|ucase|length|count|contains_any|contains_all|equals_to_any|strpos|substr|replace|norm|ccnorm|rmwhitespace|specialratio|ip_in_range|urlencode|int|float|string|bool|rescape)$/;
            return {
                token: function (stream) {
                    if (stream.eatSpace()) {
                        return null;
                    }
                    if (stream.match(/^\/\/.*/)) {
                        return 'comment';
                    }
                    if (stream.match(/^'(?:[^'\\]|\\.)*'/) || stream.match(/^"(?:[^"\\]|\\.)*"/)) {
                        return 'string';
                    }
                    if (stream.match(/^\d+(\.\d+)?/)) {
                        return 'number';
                    }
                    if (stream.match(/^(:=|==|!=|<=|>=|\*\*|[+\-*/%<>=!&|^(),;])/)) {
                        return 'operator';
                    }
                    var ch = stream.next();
                    if (/[A-Za-z_]/.test(ch)) {
                        stream.eatWhile(/[A-Za-z0-9_]/);
                        var word = stream.current();
                        if (KEYWORDS.test(word)) {
                            return 'keyword';
                        }
                        if (FUNCTIONS.test(word)) {
                            return 'builtin';
                        }
                        return 'variable';
                    }
                    return null;
                }
            };
        });
        ['abuse-filter-pattern', 'abuse-test-pattern'].forEach(function (id) {
            var textarea = document.getElementById(id);
            if (!textarea) {
                return;
            }
            editors[id] = CodeMirror.fromTextArea(textarea, {
                mode: 'abusefilter',
                lineNumbers: true,
                matchBrackets: true,
                lineWrapping: true
            });
        });
    }

    function syncEditor(id) {
        var editor = editors[id];
        if (editor && !plainMode[id]) {
            editor.save();
        }
    }

    // ==================== 条件编辑器工具条：插入 / 切换 / 语法检查 ====================

    var insertSelect = document.getElementById('abuse-filter-insert');
    var toggleEditorButton = document.getElementById('abuse-filter-toggle-editor');
    var checkButton = document.getElementById('abuse-filter-check');
    var checkMessage = document.getElementById('abuse-filter-check-message');
    var patternTextarea = document.getElementById('abuse-filter-pattern');
    var patternEditor = editors['abuse-filter-pattern'];

    function insertIntoPattern(value) {
        if (!value || !patternTextarea) {
            return;
        }
        if (patternEditor && !plainMode['abuse-filter-pattern']) {
            patternEditor.replaceSelection(value);
            patternEditor.focus();
            return;
        }
        var start = patternTextarea.selectionStart == null ? patternTextarea.value.length : patternTextarea.selectionStart;
        var end = patternTextarea.selectionEnd == null ? start : patternTextarea.selectionEnd;
        patternTextarea.value = patternTextarea.value.slice(0, start) + value
            + patternTextarea.value.slice(end);
        patternTextarea.selectionStart = patternTextarea.selectionEnd = start + value.length;
        patternTextarea.focus();
    }

    if (insertSelect) {
        insertSelect.addEventListener('change', function () {
            insertIntoPattern(insertSelect.value);
            insertSelect.value = '';
        });
    }

    if (toggleEditorButton && patternTextarea) {
        if (!patternEditor) {
            toggleEditorButton.hidden = true;
        } else {
            toggleEditorButton.addEventListener('click', function () {
                var wrapper = patternEditor.getWrapperElement();
                if (!plainMode['abuse-filter-pattern']) {
                    patternEditor.save();
                    wrapper.style.display = 'none';
                    patternTextarea.style.display = '';
                    patternTextarea.hidden = false;
                    plainMode['abuse-filter-pattern'] = true;
                    patternTextarea.focus();
                } else {
                    patternEditor.setValue(patternTextarea.value);
                    wrapper.style.display = '';
                    patternTextarea.style.display = 'none';
                    plainMode['abuse-filter-pattern'] = false;
                    patternEditor.refresh();
                    patternEditor.focus();
                }
            });
        }
    }

    if (checkButton && patternTextarea) {
        checkButton.addEventListener('click', function () {
            syncEditor('abuse-filter-pattern');
            var box = checkMessage;
            if (box) {
                box.hidden = true;
                box.textContent = '';
                box.className = 'abuse-check-message';
            }
            var body = new URLSearchParams();
            body.append('pattern', patternTextarea.value);
            post('/api/abuse-filters/check', body).then(function (result) {
                if (!box) {
                    return;
                }
                box.hidden = false;
                if (result && result.code === 200) {
                    box.textContent = result.message || MESSAGES.abuseSyntaxOK || '语法检查通过。';
                    box.className = 'abuse-check-message success';
                } else {
                    box.textContent = (result && result.message) || MESSAGES.operationFailed || '操作失败';
                    box.className = 'abuse-check-message error';
                }
            }).catch(function () {
                if (box) {
                    box.hidden = false;
                    box.textContent = MESSAGES.networkError || '网络错误';
                    box.className = 'abuse-check-message error';
                }
            });
        });
    }

    // ==================== 警告 / 阻止消息预览 ====================

    var descriptionInput = document.getElementById('abuse-filter-description');
    var previewForm = document.getElementById('abuse-filter-form');

    function encodePageTitle(title) {
        return String(title || '').split('/').map(encodeURIComponent).join('/');
    }

    // 系统消息页统一放在 WikiKernel: 命名空间；裸标题（如 Abusefilter-warning）自动补齐。
    function messagePageTitle(title) {
        title = String(title || '').trim();
        if (!title) {
            return '';
        }
        var prefix = 'WikiKernel:';
        if (title.slice(0, prefix.length).toLowerCase() === prefix.toLowerCase()) {
            return prefix + title.slice(prefix.length);
        }
        return title.indexOf(':') > 0 ? title : prefix + title;
    }

    function updateMessagePageLink(button) {
        var pageName = button.getAttribute('data-page');
        var pageInput = previewForm ? previewForm.querySelector('[name="' + pageName + '"]') : null;
        var link = button.parentElement
            ? button.parentElement.querySelector('.abuse-message-page-link') : null;
        if (!link || !pageInput) {
            return;
        }
        var title = messagePageTitle(pageInput.value);
        if (!title) {
            link.hidden = true;
            link.setAttribute('href', '#');
            return;
        }
        link.hidden = false;
        link.setAttribute('href', '/wiki/' + encodePageTitle(title) + '?action=edit');
    }

    Array.prototype.forEach.call(document.querySelectorAll('.abuse-message-preview'), function (button) {
        var target = document.getElementById(button.getAttribute('data-target'));
        var messageInput = previewForm
            ? previewForm.querySelector('[name="' + button.getAttribute('data-message') + '"]') : null;
        var pageInput = previewForm
            ? previewForm.querySelector('[name="' + button.getAttribute('data-page') + '"]') : null;
        if (!target || !messageInput || !pageInput) {
            return;
        }
        var showText = MESSAGES.abusePreviewShow || '显示预览';
        var hideText = MESSAGES.abusePreviewHide || '隐藏预览';
        updateMessagePageLink(button);
        pageInput.addEventListener('input', function () {
            updateMessagePageLink(button);
        });
        button.addEventListener('click', function () {
            if (!target.hidden) {
                target.hidden = true;
                button.textContent = showText;
                return;
            }
            var body = new URLSearchParams();
            body.append('message', messageInput.value);
            body.append('page', pageInput.value);
            body.append('fallback', descriptionInput ? descriptionInput.value : '');
            post('/api/abuse-filters/message-preview', body).then(function (result) {
                if (!result || result.code !== 200) {
                    target.textContent = (result && result.message)
                        || MESSAGES.abusePreviewFailed || '无法加载预览。';
                } else {
                    target.textContent = (result.data && result.data.text) || result.message
                        || MESSAGES.abusePreviewEmpty || '（没有可预览的文案）';
                }
                target.hidden = false;
                button.textContent = hideText;
            }).catch(function () {
                target.textContent = MESSAGES.networkError || '网络错误';
                target.hidden = false;
                button.textContent = hideText;
            });
        });
    });

    // ==================== 保存过滤器 ====================

    var saveForm = document.getElementById('abuse-filter-form');
    if (saveForm) {
        saveForm.addEventListener('submit', function (event) {
            event.preventDefault();
            syncEditor('abuse-filter-pattern');
            var button = saveForm.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
            }
            var body = new URLSearchParams(new FormData(saveForm));
            post('/api/abuse-filters/save', body).then(function (result) {
                if (result && result.code === 200) {
                    var id = result.data;
                    if (!id || String(saveForm.getAttribute('data-id')) === '0') {
                        window.location.href = '/wiki/' + encodeURIComponent('特殊:滥用过滤器')
                            + '?edit=' + id;
                        return;
                    }
                    var box = saveForm.querySelector('.form-message');
                    showMessage(box, result.message || '');
                    saveForm.setAttribute('data-id', id);
                    var hidden = saveForm.querySelector('[name="id"]');
                    if (hidden) {
                        hidden.value = id;
                    }
                } else {
                    showError(saveForm.querySelector('.form-message'), result && result.message);
                }
                if (button) {
                    button.disabled = false;
                }
            }).catch(function () {
                showError(saveForm.querySelector('.form-message'), MESSAGES.networkError);
                if (button) {
                    button.disabled = false;
                }
            });
        });
    }

    // ==================== 恢复历史版本 ====================

    Array.prototype.forEach.call(document.querySelectorAll('.abuse-filter-restore'), function (button) {
        button.addEventListener('click', function () {
            var body = new URLSearchParams();
            body.append('id', button.getAttribute('data-id'));
            body.append('revision', button.getAttribute('data-revision'));
            post('/api/abuse-filters/restore', body).then(function (result) {
                if (result && result.code === 200) {
                    window.location.reload();
                    return;
                }
                window.alert((result && result.message) || MESSAGES.operationFailed || '操作失败');
            }).catch(function () {
                window.alert(MESSAGES.networkError || '网络错误');
            });
        });
    });

    // ==================== 测试过滤器 ====================

    var testForm = document.getElementById('abuse-filter-test-form');
    if (testForm) {
        testForm.addEventListener('submit', function (event) {
            event.preventDefault();
            syncEditor('abuse-test-pattern');
            var messageBox = document.getElementById('abuse-test-message');
            var results = document.getElementById('abuse-test-results');
            var button = testForm.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
            }
            var body = new URLSearchParams(new FormData(testForm));
            post('/api/abuse-filters/test', body).then(function (result) {
                if (button) {
                    button.disabled = false;
                }
                if (!result || result.code !== 200) {
                    showError(messageBox, result && result.message);
                    return;
                }
                showMessage(messageBox, result.message || '');
                renderResults(results, result.data || []);
            }).catch(function () {
                if (button) {
                    button.disabled = false;
                }
                showError(messageBox, MESSAGES.networkError);
            });
        });
    }

    function renderResults(box, rows) {
        if (!box) {
            return;
        }
        if (!rows.length) {
            box.textContent = '';
            return;
        }
        var html = '<table class="wikitable abuse-test-table"><tr>'
            + '<th>#</th><th>' + escapeHtml(MESSAGES.abuseTestPage) + '</th>'
            + '<th>' + escapeHtml(MESSAGES.abuseTestUser) + '</th>'
            + '<th>' + escapeHtml(MESSAGES.abuseTestTime) + '</th>'
            + '<th>' + escapeHtml(MESSAGES.abuseTestSummary) + '</th>'
            + '<th>' + escapeHtml(MESSAGES.abuseTestResult) + '</th></tr>';
        rows.forEach(function (row) {
            var cls = row.matched ? 'abuse-test-matched' : 'abuse-test-not-matched';
            var mark = row.matched ? '✓' : '✗';
            html += '<tr class="' + cls + '">'
                + '<td>' + row.revisionId + '</td>'
                + '<td><a href="/wiki/' + encodeURIComponent(row.pageTitle) + '?oldid=' + row.revisionId
                + '">' + escapeHtml(row.pageTitle) + '</a></td>'
                + '<td>' + escapeHtml(row.userName || '') + '</td>'
                + '<td>' + escapeHtml(row.timeText || '') + '</td>'
                + '<td>' + escapeHtml(row.summary || '') + '</td>'
                + '<td>' + mark + '</td></tr>';
        });
        html += '</table>';
        box.innerHTML = html;
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // ==================== 隐藏 / 显示日志条目 ====================

    Array.prototype.forEach.call(document.querySelectorAll('.abuse-log-hide'), function (button) {
        button.addEventListener('click', function () {
            var body = new URLSearchParams();
            body.append('id', button.getAttribute('data-id'));
            body.append('hided', button.getAttribute('data-hided'));
            post('/api/abuse-log/hide', body).then(function (result) {
                if (result && result.code === 200) {
                    window.location.reload();
                    return;
                }
                window.alert((result && result.message) || MESSAGES.operationFailed || '操作失败');
            }).catch(function () {
                window.alert(MESSAGES.networkError || '网络错误');
            });
        });
    });
})();
