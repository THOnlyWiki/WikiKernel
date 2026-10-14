/*
 * 「特殊:替换文本」的交互：
 *  - #wiki-replace-text-form：勾选的页面 → POST /api/replace-text/apply（成功后刷新页面）
 *  - #replace-text-select-all：全选 / 取消全选
 *  - #replace-text-replace-all：勾选全部结果并提交（替换全部匹配）
 * 确认文案由模板通过 data-confirm-template 注入。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    var form = document.getElementById('wiki-replace-text-form');
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

    function boxes() {
        return form.querySelectorAll('input[name="titles"]');
    }

    function setButtonsDisabled(disabled) {
        Array.prototype.forEach.call(form.querySelectorAll('button'), function (button) {
            button.disabled = disabled;
        });
    }

    function submit() {
        var confirmText = form.getAttribute('data-confirm-template');
        if (confirmText && !window.confirm(confirmText)) {
            return;
        }
        setButtonsDisabled(true);

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
                showMessage(result.message || '', true);
                window.location.reload();
                return;
            }
            showMessage((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed', false);
            setButtonsDisabled(false);
        }).catch(function () {
            showMessage(MESSAGES.networkError || 'auth.networkError', false);
            setButtonsDisabled(false);
        });
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        submit();
    });

    var selectAll = document.getElementById('replace-text-select-all');
    if (selectAll) {
        selectAll.addEventListener('change', function () {
            Array.prototype.forEach.call(boxes(), function (box) {
                box.checked = selectAll.checked;
            });
        });
    }

    var replaceAll = document.getElementById('replace-text-replace-all');
    if (replaceAll) {
        replaceAll.addEventListener('click', function () {
            Array.prototype.forEach.call(boxes(), function (box) {
                box.checked = true;
            });
            if (selectAll) {
                selectAll.checked = true;
            }
            submit();
        });
    }
})();
