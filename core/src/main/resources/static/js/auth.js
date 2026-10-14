/*
 * 登录 / 注册表单：fetch 调 /api/user/*，成功跳转，失败在表单内显示错误框。
 * 不依赖任何前端框架；未登录时表单的 action 也是真实地址，禁用 JS 时至少能看到服务器响应。
 */
(function () {
    'use strict';

    /* 由 head.html 内联注入（跟随 ?uselang= 切换语言），取不到时用中文兜底 */
    var MESSAGES = window.WIKI_MESSAGES || {};

    function showMessage(form, text, type) {
        var box = form.querySelector('.form-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message ' + (type || 'error');
        box.hidden = false;
    }

    /** 登录/注册成功后的跳转目标：仅允许站内路径，防止开放重定向。 */
    function returnTo(form) {
        var input = form.querySelector('input[name="returnto"]');
        var value = input ? input.value : '';
        if (value.charAt(0) === '/' && value.charAt(1) !== '/') {
            return value;
        }
        return '/';
    }

    function submit(form, validate) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            if (validate && !validate(form)) {
                return;
            }

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
                    if (form.getAttribute('data-admin-create') === 'true') {
                        // 管理员 / 行政员代为创建账号：不切换登录，留在本页显示成功
                        showMessage(form, result.message || '', 'success');
                        form.reset();
                        if (button) {
                            button.disabled = false;
                        }
                        return;
                    }
                    window.location.href = returnTo(form);
                    return;
                }
                showMessage(form, (result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed', 'error');
                if (button) {
                    button.disabled = false;
                }
            }).catch(function () {
                showMessage(form, MESSAGES.networkError || 'auth.networkError', 'error');
                if (button) {
                    button.disabled = false;
                }
            });
        });
    }

    var loginForm = document.getElementById('wiki-login-form');
    if (loginForm) {
        submit(loginForm);
    }

    var registerForm = document.getElementById('wiki-register-form');
    if (registerForm) {
        submit(registerForm, function (form) {
            // 注册时记录浏览器时区（时区偏好默认值，见 特殊:参数设置）
            var timeZone = form.querySelector('input[name="timeZone"]');
            if (timeZone && !timeZone.value) {
                try {
                    timeZone.value = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
                } catch (error) {
                    timeZone.value = '';
                }
            }
            var password = form.querySelector('input[name="password"]');
            var confirm = form.querySelector('input[name="confirmPassword"]');
            if (password && confirm && password.value !== confirm.value) {
                showMessage(form, MESSAGES.passwordMismatch || 'auth.passwordMismatch', 'error');
                confirm.focus();
                return false;
            }
            return true;
        });
    }
})();
