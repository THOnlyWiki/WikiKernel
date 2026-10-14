/*
 * AI 厂商密钥表单（「参数设置 → AI 代理」= 我的密钥；「特殊:AI代理?keys=1」= 站点密钥）：
 * 每个厂商一行（.ai-key-row），「保存 / 清除」按钮 POST /api/ai-keys，
 * 保存后即时生效（不用重启），响应只带尾号掩码；密钥框永远不回填真实密钥。
 * 另外处理站点级开关「允许普通用户使用」（[data-allow-normal] → POST /api/ai-keys/allow-normal）。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var forms = document.querySelectorAll('[data-keys-scope]');
    var allowForms = document.querySelectorAll('[data-allow-normal]');
    if (!forms.length && !allowForms.length) {
        return;
    }

    function showMessage(form, text, success) {
        var box = form.querySelector('.ai-keys-message');
        if (!box) {
            return;
        }
        box.textContent = text || '';
        box.className = 'form-message ai-keys-message' + (success ? ' success' : '');
        box.hidden = !text;
    }

    function stateLabel(row, data) {
        var state = row.querySelector('.ai-key-state');
        if (!state) {
            return;
        }
        var configured = data && data.configured;
        var suffix = row.getAttribute('data-configured-label') || '';
        var missing = row.getAttribute('data-missing-label') || '';
        state.textContent = configured ? suffix.replace('{0}', data.mask || '') : missing;
        state.className = 'ai-key-state' + (configured ? ' ok' : '');
    }

    function submit(row, form, clear) {
        var scope = form.getAttribute('data-keys-scope');
        var provider = row.getAttribute('data-provider');
        var keyInput = row.querySelector('.ai-key-input');
        var baseInput = row.querySelector('.ai-base-input');
        var modelInput = row.querySelector('.ai-model-input');
        var proxyInput = row.querySelector('.ai-proxy-input');
        var body = new URLSearchParams();
        body.append('scope', scope);
        body.append('provider', provider);
        if (clear) {
            body.append('clear', '1');
        } else if (keyInput && keyInput.value) {
            body.append('apiKey', keyInput.value);
        }
        if (baseInput) {
            body.append('baseUrl', baseInput.value);
        }
        if (modelInput) {
            body.append('model', modelInput.value);
        }
        if (proxyInput) {
            body.append('proxy', proxyInput.value);
        }
        Array.prototype.forEach.call(row.querySelectorAll('button'), function (button) {
            button.disabled = true;
        });
        fetch('/api/ai-keys', {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: body
        }).then(function (response) {
            return response.json();
        }).then(function (result) {
            Array.prototype.forEach.call(row.querySelectorAll('button'), function (button) {
                button.disabled = false;
            });
            if (!result || result.code !== 200) {
                showMessage(form, (result && result.message) || MESSAGES.operationFailed
                    || '操作失败，请稍后重试', false);
                return;
            }
            var data = result.data || {};
            if (keyInput) {
                keyInput.value = '';
                var keep = form.getAttribute('data-keep-label');
                var normal = form.getAttribute('data-default-placeholder');
                if (data.configured) {
                    if (keep) {
                        keyInput.setAttribute('placeholder', keep);
                    }
                } else if (normal) {
                    keyInput.setAttribute('placeholder', normal);
                }
            }
            if (proxyInput && data.proxy !== undefined) {
                proxyInput.value = data.proxy || '';
            }
            if (baseInput && data.baseUrl !== undefined) {
                baseInput.value = data.baseUrl || '';
            }
            if (modelInput && data.model !== undefined) {
                modelInput.value = data.model || '';
            }
            stateLabel(row, data);
            showMessage(form, result.message || '', true);
        }).catch(function () {
            Array.prototype.forEach.call(row.querySelectorAll('button'), function (button) {
                button.disabled = false;
            });
            showMessage(form, MESSAGES.networkError || '网络错误，请稍后重试', false);
        });
    }

    Array.prototype.forEach.call(forms, function (form) {
        Array.prototype.forEach.call(form.querySelectorAll('.ai-key-row'), function (row) {
            var save = row.querySelector('.ai-key-save');
            var clear = row.querySelector('.ai-key-clear');
            if (save) {
                save.addEventListener('click', function () {
                    submit(row, form, false);
                });
            }
            if (clear) {
                clear.addEventListener('click', function () {
                    var confirmText = form.getAttribute('data-keys-confirm-clear');
                    if (confirmText && !window.confirm(confirmText)) {
                        return;
                    }
                    submit(row, form, true);
                });
            }
        });
    });

    /** 站点级「允许普通用户使用」开关（管理员；POST /api/ai-keys/allow-normal）。 */
    Array.prototype.forEach.call(allowForms, function (form) {
        var box = form.querySelector('input[name="allowNormal"]');
        var button = form.querySelector('button');
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            var body = new URLSearchParams();
            body.append('allow', box && box.checked ? '1' : '0');
            if (button) {
                button.disabled = true;
            }
            fetch('/api/ai-keys/allow-normal', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: body
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                if (button) {
                    button.disabled = false;
                }
                if (!result || result.code !== 200) {
                    showMessage(form, (result && result.message) || MESSAGES.operationFailed
                        || '操作失败，请稍后重试', false);
                    return;
                }
                showMessage(form, result.message || '', true);
            }).catch(function () {
                if (button) {
                    button.disabled = false;
                }
                showMessage(form, MESSAGES.networkError || '网络错误，请稍后重试', false);
            });
        });
    });
})();
