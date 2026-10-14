/*
 * 「特殊:权限配置」的交互：
 *  - form.access-rule-form：目标 + 动作 + 允许范围（所有人 / 无人 / 指定用户组）→ POST /api/access-rules
 *  - .access-rule-delete：删除规则 → POST /api/access-rules/delete
 * 保存 / 删除成功后刷新页面。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    function showMessage(text) {
        var box = document.getElementById('access-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message';
        box.hidden = false;
    }

    function submit(url, body, form) {
        if (form) {
            Array.prototype.forEach.call(form.querySelectorAll('button'), function (button) {
                button.disabled = true;
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
                showMessage(result.message || '');
                window.location.reload();
                return;
            }
            showMessage((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
            if (form) {
                Array.prototype.forEach.call(form.querySelectorAll('button'), function (button) {
                    button.disabled = false;
                });
            }
        }).catch(function () {
            showMessage(MESSAGES.networkError || 'auth.networkError');
            if (form) {
                Array.prototype.forEach.call(form.querySelectorAll('button'), function (button) {
                    button.disabled = false;
                });
            }
        });
    }

    Array.prototype.forEach.call(document.querySelectorAll('form.access-rule-form'), function (form) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            var target = form.querySelector('[name="target"]');
            var action = form.querySelector('[name="action"]');
            if (!target || !action) {
                return;
            }
            var body = new URLSearchParams();
            body.append('target', target.value);
            body.append('action', action.value);

            var scope = form.querySelector('input[name="scope"]:checked');
            var scopeValue = scope ? scope.value : 'nobody';
            if (scopeValue === 'everyone') {
                body.append('groups', '*');
            } else if (scopeValue === 'groups') {
                Array.prototype.forEach.call(form.querySelectorAll('input[name="groups"]:checked'),
                    function (checkbox) {
                        body.append('groups', checkbox.value);
                    });
            }
            submit('/api/access-rules', body, form);
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.access-rule-delete'), function (button) {
        button.addEventListener('click', function () {
            var body = new URLSearchParams();
            body.append('id', button.getAttribute('data-id'));
            submit('/api/access-rules/delete', body, null);
        });
    });

    // 站点设置：提交整个表单（未勾选的复选框不会出现在 FormData 里 = false）
    var settingsForm = document.getElementById('site-settings-form');
    if (settingsForm) {
        settingsForm.addEventListener('submit', function (event) {
            event.preventDefault();
            submit('/api/site-settings', new URLSearchParams(new FormData(settingsForm)), settingsForm);
        });
    }
})();
