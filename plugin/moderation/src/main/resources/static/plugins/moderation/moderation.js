/*
 * 「特殊:审核」交互：通过 / 拒绝待审更改（POST /api/moderation/approve|reject）。
 * 每行独立提交：成功后整页刷新（列表 / 计数变化），失败显示行内消息。
 * 从 core 的 /js/moderation.js 迁到插件（/plugins/moderation/moderation.js，由插件注入）。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var rows = document.querySelectorAll('.moderation-row');
    if (!rows.length) {
        return;
    }

    function show(row, text, success) {
        var box = row.querySelector('.moderation-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message moderation-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    function review(row, action) {
        var id = row.getAttribute('data-id');
        if (!id) {
            return;
        }
        var input = row.querySelector('.moderation-comment-input');
        var buttons = row.querySelectorAll('button');
        buttons.forEach(function (button) {
            button.disabled = true;
        });
        var body = new URLSearchParams();
        body.set('id', id);
        body.set('comment', input ? input.value : '');

        fetch('/api/moderation/' + action, {
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
                show(row, result.message || '', true);
                window.location.reload();
                return;
            }
            show(row, (result && result.message) || MESSAGES.operationFailed || '操作失败，请稍后重试', false);
            buttons.forEach(function (button) {
                button.disabled = false;
            });
        }).catch(function () {
            show(row, MESSAGES.networkError || '网络错误，请稍后重试', false);
            buttons.forEach(function (button) {
                button.disabled = false;
            });
        });
    }

    rows.forEach(function (row) {
        var approve = row.querySelector('.moderation-approve');
        var reject = row.querySelector('.moderation-reject');
        if (approve) {
            approve.addEventListener('click', function () {
                review(row, 'approve');
            });
        }
        if (reject) {
            reject.addEventListener('click', function () {
                review(row, 'reject');
            });
        }
    });
})();
