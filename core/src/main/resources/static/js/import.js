/*
 * 特殊:导入页面 / Special:Import：
 *  - #wiki-import-form：multipart 提交到 /api/import（自动识别 MediaWiki XML / WikiKernel XML / JSON）
 *  - 结果提示写进 .form-message（feedback.js 会统一自动淡出）
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    function showMessage(box, text, success) {
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    var form = document.getElementById('wiki-import-form');
    if (!form) {
        return;
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        var button = form.querySelector('button[type="submit"]');
        var box = form.querySelector('.form-message');
        var input = form.querySelector('input[type="file"]');

        if (!input || !input.files || input.files.length === 0) {
            showMessage(box, MESSAGES.uploadEmpty || 'files.empty', false);
            return;
        }
        if (button) {
            button.disabled = true;
        }
        showMessage(box, MESSAGES.uploading || 'toolbar.uploading', false);

        fetch(form.action, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: new FormData(form)
        }).then(function (response) {
            return response.json();
        }).then(function (result) {
            if (button) {
                button.disabled = false;
            }
            var success = !!(result && result.code === 200);
            var text = (result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed';
            var errors = result && result.data && result.data.errors;
            if (success && errors && errors.length) {
                text = text + ' ' + errors.join('；');
            }
            showMessage(box, text, success);
        }).catch(function () {
            if (button) {
                button.disabled = false;
            }
            showMessage(box, MESSAGES.networkError || 'auth.networkError', false);
        });
    });
})();
