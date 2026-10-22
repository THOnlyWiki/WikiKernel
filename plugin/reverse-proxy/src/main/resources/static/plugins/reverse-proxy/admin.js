/*
 * 特殊:反向代理 管理页脚本。
 *  - 表单提交到 /api/reverse-proxy/save（CSRF 自定义头）
 *  - 表格行“编辑”把 data-* 填回表单
 *  - 删除前确认
 * 页面本身由服务端渲染；保存 / 删除后带 ?saved=1 / ?deleted=1 刷新。
 */
(function () {
    'use strict';

    var form = document.getElementById('reverse-proxy-form');
    if (!form) {
        return;
    }

    var MESSAGES = window.WIKI_MESSAGES || {};
    var csrfInput = document.getElementById('reverse-proxy-csrf');
    var messageBox = form.querySelector('.reverse-proxy-message');
    var submitButton = form.querySelector('button[type="submit"]');
    var tokenPromise = null;
    var token = '';

    function message(text, success) {
        if (!messageBox) {
            return;
        }
        messageBox.textContent = text;
        messageBox.className = 'form-message reverse-proxy-message' + (success ? ' success' : '');
        messageBox.hidden = false;
    }

    function clearMessage() {
        if (messageBox) {
            messageBox.hidden = true;
            messageBox.textContent = '';
        }
    }

    function ensureToken() {
        if (token) {
            return Promise.resolve(token);
        }
        if (tokenPromise) {
            return tokenPromise;
        }
        tokenPromise = fetch(form.getAttribute('data-csrf-url'), {
            method: 'GET',
            credentials: 'same-origin',
            headers: {'Accept': 'application/json'}
        }).then(function (response) {
            return response.json();
        }).then(function (result) {
            if (!result || result.code !== 200 || !result.data || !result.data.token) {
                throw new Error((result && result.message) || MESSAGES.reverseProxyCsrfFailed || 'CSRF failed');
            }
            token = result.data.token;
            if (csrfInput) {
                csrfInput.value = token;
            }
            return token;
        }).catch(function (error) {
            tokenPromise = null;
            throw error;
        });
        return tokenPromise;
    }

    function post(url, body) {
        return ensureToken().then(function (value) {
            return fetch(url, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {
                    'Accept': 'application/json',
                    'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                    'X-Requested-With': 'XMLHttpRequest',
                    'X-Reverse-Proxy-CSRF': value
                },
                body: body.toString()
            });
        }).then(function (response) {
            return response.json();
        });
    }

    function reloadWith(flag) {
        var path = window.location.pathname;
        window.location.href = path + '?' + flag + '=1';
    }

    function field(id) {
        return form.querySelector('#' + id);
    }

    function setChecked(id, value) {
        var element = field(id);
        if (element) {
            element.checked = value === true || value === 'true';
        }
    }

    function resetForm() {
        form.reset();
        field('reverse-proxy-original-slug').value = '';
        field('reverse-proxy-slug').readOnly = false;
        field('reverse-proxy-methods').value = 'GET,HEAD';
        field('reverse-proxy-timeout').value = '30';
        field('reverse-proxy-access').value = 'admin';
        setChecked('reverse-proxy-enabled', true);
        setChecked('reverse-proxy-rewrite-html', false);
        setChecked('reverse-proxy-sandbox', true);
        setChecked('reverse-proxy-allow-private', false);
        clearMessage();
    }

    function editRoute(button) {
        var data = button.dataset || {};
        field('reverse-proxy-original-slug').value = data.slug || '';
        field('reverse-proxy-slug').value = data.slug || '';
        field('reverse-proxy-upstream').value = data.upstream || '';
        field('reverse-proxy-label').value = data.label || '';
        field('reverse-proxy-access').value = data.access || 'admin';
        field('reverse-proxy-methods').value = data.methods || 'GET,HEAD';
        field('reverse-proxy-timeout').value = data.timeout || '30';
        setChecked('reverse-proxy-enabled', data.enabled);
        setChecked('reverse-proxy-rewrite-html', data.rewriteHtml);
        setChecked('reverse-proxy-sandbox', data.sandbox);
        setChecked('reverse-proxy-allow-private', data.allowPrivate);
        clearMessage();
        field('reverse-proxy-slug').focus();
        form.scrollIntoView({behavior: 'smooth', block: 'start'});
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        clearMessage();
        var slug = field('reverse-proxy-slug').value.trim();
        var upstream = field('reverse-proxy-upstream').value.trim();
        if (!slug || !upstream) {
            message(MESSAGES.reverseProxyRequired || MESSAGES.reverseProxyBadRequest || 'Required', false);
            return;
        }
        var body = new URLSearchParams();
        body.set('originalSlug', field('reverse-proxy-original-slug').value);
        body.set('slug', slug);
        body.set('upstream', upstream);
        body.set('label', field('reverse-proxy-label').value);
        body.set('access', field('reverse-proxy-access').value);
        body.set('methods', field('reverse-proxy-methods').value);
        body.set('timeoutSeconds', field('reverse-proxy-timeout').value);
        body.set('enabled', field('reverse-proxy-enabled').checked ? 'true' : 'false');
        body.set('rewriteHtml', field('reverse-proxy-rewrite-html').checked ? 'true' : 'false');
        body.set('sandbox', field('reverse-proxy-sandbox').checked ? 'true' : 'false');
        body.set('allowPrivateTargets', field('reverse-proxy-allow-private').checked ? 'true' : 'false');

        if (submitButton) {
            submitButton.disabled = true;
        }
        post(form.getAttribute('data-save-url'), body).then(function (result) {
            if (result && result.code === 200) {
                message((result.message || MESSAGES.reverseProxySaved || 'Saved'), true);
                window.setTimeout(function () {
                    reloadWith('saved');
                }, 250);
                return;
            }
            message((result && result.message) || MESSAGES.reverseProxyOperationFailed || 'Failed', false);
            if (submitButton) {
                submitButton.disabled = false;
            }
        }).catch(function (error) {
            message((error && error.message) || MESSAGES.reverseProxyNetworkError || 'Network error', false);
            if (submitButton) {
                submitButton.disabled = false;
            }
        });
    });

    var resetButton = document.getElementById('reverse-proxy-reset');
    if (resetButton) {
        resetButton.addEventListener('click', resetForm);
    }

    Array.prototype.forEach.call(document.querySelectorAll('.reverse-proxy-edit'), function (button) {
        button.addEventListener('click', function () {
            editRoute(button);
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.reverse-proxy-delete'), function (button) {
        button.addEventListener('click', function () {
            var slug = button.getAttribute('data-slug') || '';
            if (!slug) {
                return;
            }
            if (!window.confirm(MESSAGES.reverseProxyDeleteConfirm || 'Delete this route?')) {
                return;
            }
            button.disabled = true;
            var body = new URLSearchParams();
            body.set('slug', slug);
            post(form.getAttribute('data-delete-url'), body).then(function (result) {
                if (result && result.code === 200) {
                    reloadWith('deleted');
                    return;
                }
                window.alert((result && result.message) || MESSAGES.reverseProxyOperationFailed || 'Failed');
                button.disabled = false;
            }).catch(function (error) {
                window.alert((error && error.message) || MESSAGES.reverseProxyNetworkError || 'Network error');
                button.disabled = false;
            });
        });
    });

    // 提前取令牌，避免用户第一次提交时多一次等待；失败时提交仍会重试。
    ensureToken().catch(function () {
        // 页面会显示错误；这里不打断浏览
    });
})();
