/*
 * 「特殊:外观」皮肤偏好表单：提交到 POST /api/user/skin，成功后刷新页面（会话已即时更新）。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var form = document.getElementById('skin-preference-form');
    if (!form) {
        return;
    }

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
        var checked = form.querySelector('input[name="skin"]:checked');
        var body = new URLSearchParams();
        body.append('skin', checked ? checked.value : '');

        fetch('/api/user/skin', {
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
})();
