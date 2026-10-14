/*
 * 特殊:用户权限 / 用户页权限编辑器：勾选用户组后提交到 POST /api/user/permissions。
 * 仿 MediaWiki：每个勾选的组下面显示「期限」下拉（不会过期 / 1天 / 1周 / 1个月 / 3个月 / 6个月 / 1年 / 其他时间），
 * 选「其他时间」再显示 datetime-local；表单底部可填「原因」。
 * 只读模式（data-can-edit != "true"，即当前用户不是管理员/行政员）时脚本直接跳过。
 * 提示文案来自服务端（AjaxResult.message），网络错误用 head.html 注入的 WIKI_MESSAGES。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    var form = document.getElementById('wiki-permissions-form');
    if (!form || form.getAttribute('data-can-edit') !== 'true') {
        return;
    }

    var rows = Array.prototype.slice.call(form.querySelectorAll('.permission-item'));

    /** 同步一行：未勾选隐藏期限；期限选「其他时间」时显示 datetime-local。 */
    function syncRow(row) {
        var check = row.querySelector('.permission-check');
        var expiry = row.querySelector('.permission-expiry');
        if (check && expiry) {
            expiry.hidden = !check.checked;
        }
        var select = row.querySelector('.permission-expiry-select');
        var other = row.querySelector('.permission-expiry-other');
        if (select && other) {
            other.hidden = select.value !== 'other';
        }
    }

    rows.forEach(function (row) {
        var check = row.querySelector('.permission-check');
        var select = row.querySelector('.permission-expiry-select');
        if (check) {
            check.addEventListener('change', function () {
                syncRow(row);
            });
        }
        if (select) {
            select.addEventListener('change', function () {
                syncRow(row);
            });
        }
        syncRow(row);
    });

    function showMessage(text, success) {
        var box = form.querySelector('.form-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();

        var button = form.querySelector('button[type="submit"]');
        if (button) {
            button.disabled = true;
        }

        var body = new URLSearchParams();
        var username = form.querySelector('input[name="username"]');
        body.append('username', username ? username.value : '');
        rows.forEach(function (row) {
            var check = row.querySelector('.permission-check');
            if (!check || !check.checked) {
                return;
            }
            var name = check.value;
            body.append('permissions', name);
            var select = row.querySelector('.permission-expiry-select');
            var other = row.querySelector('.permission-expiry-other');
            var value = '';
            if (select) {
                if (select.value === 'other') {
                    value = other ? other.value : '';
                } else {
                    value = select.value;
                }
            }
            body.append('expiry.' + name, value);
        });
        var reason = form.querySelector('input[name="reason"]');
        if (reason && reason.value.trim() !== '') {
            body.append('reason', reason.value.trim());
        }

        fetch(form.action, {
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
                showMessage(result.message || '', true);
            } else {
                showMessage((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed', false);
            }
            if (button) {
                button.disabled = false;
            }
        }).catch(function () {
            showMessage(MESSAGES.networkError || 'auth.networkError', false);
            if (button) {
                button.disabled = false;
            }
        });
    });
})();
