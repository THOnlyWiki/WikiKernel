/*
 * 移动页面 / 保护页面表单：提交到 /api/pages/*，成功后跳到返回的页面 URL。
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

    function submit(form) {
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
                    var url = result.data && result.data.url ? result.data.url : '/';
                    window.location.href = url;
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
        });
    }

    var moveForm = document.getElementById('wiki-move-form');
    if (moveForm) {
        submit(moveForm);
    }

    var protectForm = document.getElementById('wiki-protect-form');
    if (protectForm) {
        submit(protectForm);
    }
})();
