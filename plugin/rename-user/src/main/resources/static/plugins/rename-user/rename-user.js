/*
 * 「特殊:重命名用户」交互：表单 POST /api/user/rename（需要「重命名用户」权限），
 * 提交前按模板文案（__OLD__ / __NEW__ 占位）确认，成功后跳到新用户页。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var form = document.getElementById('rename-user-form');
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

    function inputValue(id) {
        var input = document.getElementById(id);
        return input ? input.value.trim() : '';
    }

    function confirmText(template, oldName, newName) {
        if (!template) {
            return '';
        }
        // 用 split / join 替换，避免用户名里的 $ 被当成 replace 的替换模式
        return template.split('__OLD__').join(oldName).split('__NEW__').join(newName);
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();

        var prompt = confirmText(form.getAttribute('data-confirm-template'),
            inputValue('rename-user-old'), inputValue('rename-user-new'));
        if (prompt && !window.confirm(prompt)) {
            return;
        }

        var button = form.querySelector('button[type="submit"]');
        if (button) {
            button.disabled = true;
        }

        var body = new URLSearchParams(new FormData(form));

        fetch('/api/user/rename', {
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
            showMessage((result && result.message) || MESSAGES.operationFailed || '操作失败，请稍后重试');
            if (button) {
                button.disabled = false;
            }
        }).catch(function () {
            showMessage(MESSAGES.networkError || '网络错误，请稍后重试');
            if (button) {
                button.disabled = false;
            }
        });
    });
})();