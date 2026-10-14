/*
 * 特殊:私信前端：
 *  - #message-form：提交到 POST /api/messages/send，成功后跳到发件箱
 *  - .message-delete：POST /api/messages/delete（软删除自己一侧），成功后刷新 / 回列表
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    function showMessage(form, text) {
        var box = form.querySelector('.form-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.hidden = false;
    }

    var form = document.getElementById('message-form');
    if (form) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            var button = form.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
            }
            var body = new URLSearchParams();
            new FormData(form).forEach(function (value, key) {
                body.append(key, value);
            });
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
                    // 会话视图回到会话（data-redirect），否则去发件箱
                    var redirect = form.getAttribute('data-redirect');
                    window.location.href = redirect || '/wiki/特殊:私信?box=sent';
                    return;
                }
                showMessage(form, (result && result.message) || MESSAGES.operationFailed || '操作失败，请稍后重试');
                if (button) {
                    button.disabled = false;
                }
            }).catch(function () {
                showMessage(form, MESSAGES.networkError || '网络错误，请稍后重试');
                if (button) {
                    button.disabled = false;
                }
            });
        });
    }

    Array.prototype.forEach.call(document.querySelectorAll('.message-delete'), function (button) {
        button.addEventListener('click', function () {
            if (!window.confirm(MESSAGES.messageDeleteConfirm)) {
                return;
            }
            var body = new URLSearchParams();
            body.append('id', button.getAttribute('data-id'));
            button.disabled = true;
            fetch('/api/messages/delete', {
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
                    var returning = document.querySelector('.message-view');
                    if (returning) {
                        window.location.href = '/wiki/特殊:私信';
                    } else {
                        window.location.reload();
                    }
                    return;
                }
                window.alert((result && result.message) || MESSAGES.operationFailed || '操作失败，请稍后重试');
                button.disabled = false;
            }).catch(function () {
                window.alert(MESSAGES.networkError || '网络错误，请稍后重试');
                button.disabled = false;
            });
        });
    });
})();
