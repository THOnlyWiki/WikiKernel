/*
 * 「特殊:用户合并」交互：表单 POST /api/user-merge（核查员专用），结果显示在页面消息框。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var form = document.getElementById('user-merge-form');
    if (!form) {
        return;
    }

    var box = document.getElementById('user-merge-message');

    function show(text) {
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message';
        box.hidden = false;
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        var button = form.querySelector('button[type="submit"]');
        if (button) {
            button.disabled = true;
        }
        var body = new URLSearchParams(new FormData(form));
        var deleteOld = document.getElementById('user-merge-delete');
        body.set('deleteOld', deleteOld && deleteOld.checked ? 'true' : 'false');

        fetch('/api/user-merge', {
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
                show(result.message || '');
                return;
            }
            show((result && result.message) || MESSAGES.operationFailed || '操作失败，请稍后重试');
            if (button) {
                button.disabled = false;
            }
        }).catch(function () {
            show(MESSAGES.networkError || '网络错误，请稍后重试');
            if (button) {
                button.disabled = false;
            }
        });
    });
})();