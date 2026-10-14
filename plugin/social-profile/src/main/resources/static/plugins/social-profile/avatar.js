/*
 * 用户页头像上传（本人可见，按钮在头像正下方）：
 *  - 点击「上传头像」→ 打开文件选择框；选中图片后立即上传到 POST /api/user/avatar
 *  - 成功后直接用返回的 URL 更新头像（文件名带时间戳，没有缓存问题）
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    var input = document.getElementById('avatar-input');
    var button = document.getElementById('avatar-upload');
    var box = document.getElementById('avatar-message');
    var image = document.querySelector('.wiki-avatar');
    if (!input || !button) {
        return;
    }

    function showMessage(text, success) {
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    /** 预加载新头像后淡入替换（避免生硬跳变）。 */
    function swapAvatar(image, url) {
        image.style.opacity = '0';
        var next = new Image();
        var apply = function () {
            image.src = url;
            image.style.opacity = '1';
        };
        next.onload = apply;
        next.onerror = apply;
        next.src = url;
    }

    function upload(file) {
        var body = new FormData();
        body.append('file', file);
        button.disabled = true;
        fetch('/api/user/avatar', {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: body
        }).then(function (response) {
            return response.json();
        }).then(function (result) {
            button.disabled = false;
            input.value = '';
            if (result && result.code === 200) {
                var url = result.data && result.data.url;
                if (url && image) {
                    swapAvatar(image, url);
                }
                showMessage(result.message || MESSAGES.avatarSaved, true);
                return;
            }
            showMessage((result && result.message) || MESSAGES.operationFailed || '操作失败，请稍后重试');
        }).catch(function () {
            button.disabled = false;
            input.value = '';
            showMessage(MESSAGES.networkError || '网络错误，请稍后重试');
        });
    }

    button.addEventListener('click', function () {
        input.click();
    });

    input.addEventListener('change', function () {
        var file = input.files && input.files[0];
        if (file) {
            upload(file);
        }
    });
})();
