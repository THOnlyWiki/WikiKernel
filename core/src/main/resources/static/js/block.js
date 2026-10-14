/*
 * 封禁功能前端：
 *  - #wiki-block-form：提交到 /api/blocks，成功后跳到封禁列表
 *  - .block-unblock：POST /api/blocks/unblock，成功后刷新
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

    var form = document.getElementById('wiki-block-form');
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
                    // 封禁后留在封禁页（带成功提示；同页可解封），不再跳封禁列表
                    var url = result.data && result.data.redirectUrl ? result.data.redirectUrl : '/wiki/特殊:封禁';
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

    Array.prototype.forEach.call(document.querySelectorAll('.block-unblock'), function (button) {
        button.addEventListener('click', function () {
            if (!window.confirm(MESSAGES.blockUnblockConfirm || 'block.unblockConfirm')) {
                return;
            }
            var body = new URLSearchParams();
            body.append('id', button.getAttribute('data-id'));
            button.disabled = true;
            fetch('/api/blocks/unblock', {
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
                    // 解封后刷新并带上 «已解封» 提示（移除上一次的 «已封禁» 提示参数）
                    var url = new URL(window.location.href);
                    url.searchParams.delete('blocked');
                    url.searchParams.set('unblocked', '1');
                    window.location.href = url.toString();
                    return;
                }
                window.alert((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
                button.disabled = false;
            }).catch(function () {
                window.alert(MESSAGES.networkError || 'auth.networkError');
                button.disabled = false;
            });
        });
    });
})();
