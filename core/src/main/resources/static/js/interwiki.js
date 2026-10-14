/*
 * 「特殊:跨Wiki」交互（仿 MediaWiki 的 Interwiki）：
 *  - #wiki-interwiki-form：新建 / 更新前缀 → POST /api/interwiki/save，成功后回到列表
 *  - .interwiki-delete：删除前缀 → POST /api/interwiki/delete（先确认）
 *  - #wiki-interwiki-import-form：从文本导入 → POST /api/interwiki/import
 * 三种操作都需要管理员 / 行政员，权限由服务端校验。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    function showMessage(form, text, success) {
        var box = form.querySelector('.form-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    function submit(form, url, extra) {
        var button = form.querySelector('button[type="submit"]');
        if (button) {
            button.disabled = true;
        }
        var body = new URLSearchParams();
        new FormData(form).forEach(function (value, key) {
            body.append(key, value);
        });
        if (extra) {
            Object.keys(extra).forEach(function (key) {
                body.append(key, extra[key]);
            });
        }
        fetch(url, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: body
        }).then(function (response) {
            return response.json();
        }).then(function (result) {
            if (result && result.code === 200) {
                var redirect = result.data && result.data.redirectUrl;
                if (redirect) {
                    window.location.href = redirect;
                    return;
                }
                showMessage(form, (result && result.message) || MESSAGES.interwikiSaved || 'interwiki.saved', true);
                return;
            }
            showMessage(form, (result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
            if (button) {
                button.disabled = false;
            }
        }).catch(function () {
            showMessage(form, MESSAGES.networkError || 'auth.networkError');
            if (button) {
                button.disabled = false;
            }
        });
    }

    var form = document.getElementById('wiki-interwiki-form');
    if (form) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            submit(form, form.action);
        });
    }

    var importForm = document.getElementById('wiki-interwiki-import-form');
    if (importForm) {
        importForm.addEventListener('submit', function (event) {
            event.preventDefault();
            submit(importForm, importForm.action);
        });
    }

    Array.prototype.forEach.call(document.querySelectorAll('.interwiki-delete'), function (button) {
        button.addEventListener('click', function () {
            var prefix = button.getAttribute('data-prefix') || '';
            var prompt = (MESSAGES.interwikiDeleteConfirm || 'interwiki.deleteConfirm')
                .split('$1').join(prefix);
            if (!window.confirm(prompt)) {
                return;
            }
            var body = new URLSearchParams();
            body.append('id', button.getAttribute('data-id'));
            button.disabled = true;
            fetch('/api/interwiki/delete', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: body
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                if (result && result.code === 200) {
                    window.location.href = (result.data && result.data.redirectUrl)
                        || '/wiki/特殊:跨Wiki?deleted=1';
                    return;
                }
                window.alert((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
                button.disabled = false;
            }).catch(function () {
                window.alert(MESSAGES.networkError || 'auth.networkError');
                button.disabled = false;
            });
        });
    });
})();
