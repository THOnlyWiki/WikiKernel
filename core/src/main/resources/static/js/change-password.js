/*
 * 特殊:修改密码（仿 MediaWiki 的 Special:ChangePassword）：
 * 把表单提交到 core 的改密接口 POST /api/user/preferences/password（会校验当前密码），
 * 成功后提示并回到页面里「返回参数设置」链接指向的地址（returnto，默认 特殊:参数设置）。
 * 参数设置页本身不再内嵌改密表单，而是放一个白色按钮链到本页（与线上一致）。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var form = document.getElementById('change-password-form');
    if (!form) {
        return;
    }

    var message = document.getElementById('change-password-message');
    var button = form.querySelector('button[type="submit"]');

    function show(text, success) {
        if (!message) {
            return;
        }
        message.textContent = text;
        message.className = 'form-message' + (success ? ' success' : '');
        message.hidden = false;
    }

    /** 改密成功后的去处：页面里 returnto 链接的 href（服务端已过滤成站内地址）。 */
    function returnTarget() {
        var link = document.querySelector('.change-password .auth-subtitle a');
        var href = link ? link.getAttribute('href') : null;
        if (href && href.indexOf('//') !== 0 && href.indexOf(':') < 0
                && href.indexOf('\\') < 0
                && !/[\u0000-\u001f\u007f]/.test(href)
                && !/%5c|%2f|%0d|%0a|%00|%09/i.test(href)) {
            return href;
        }
        return '/wiki/特殊:参数设置';
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        if (button) {
            button.disabled = true;
        }
        fetch('/api/user/preferences/password', {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: new URLSearchParams(new FormData(form))
        }).then(function (response) {
            return response.json();
        }).then(function (result) {
            if (result && result.code === 200) {
                show(result.message || MESSAGES.passwordChanged || '', true);
                form.reset();
                var target = returnTarget();
                setTimeout(function () {
                    window.location.href = target;
                }, 900);
                return;
            }
            show((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed', false);
            if (button) {
                button.disabled = false;
            }
        }).catch(function () {
            show(MESSAGES.networkError || 'auth.networkError', false);
            if (button) {
                button.disabled = false;
            }
        });
    });
})();
