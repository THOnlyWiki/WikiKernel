/*
 * 日志页的「隐藏 / 显示」按钮（仅管理员 / 行政员会看到）。
 * 提交到 POST /api/logs/hide，成功后刷新页面。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var forms = document.querySelectorAll('.log-hide-form');
    if (!forms.length) {
        return;
    }

    function showMessage(text) {
        var box = document.getElementById('log-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message';
        box.hidden = false;
    }

    forms.forEach(function (form) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            var button = form.querySelector('button');
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
                    window.location.reload();
                    return;
                }
                showMessage((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
                if (button) {
                    button.disabled = false;
                }
            }).catch(function () {
                showMessage(MESSAGES.networkError || 'auth.networkError');
                if (button) {
                    button.disabled = false;
                }
            });
        });
    });
})();
