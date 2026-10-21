/*
 * 特殊:ApiSandbox：Action API 请求构造器。
 * 只请求同源 /api.php；不执行响应中的 HTML/JS，所有结果按纯文本展示。
 */
(function () {
    'use strict';

    var form = document.getElementById('api-sandbox-form');
    if (!form) {
        return;
    }

    var endpoint = form.getAttribute('data-endpoint') || '/api.php';
    var actionSelect = document.getElementById('api-sandbox-action');
    var formatSelect = document.getElementById('api-sandbox-format');
    var version2 = document.getElementById('api-sandbox-formatversion');
    var autoToken = document.getElementById('api-sandbox-autotoken');
    var rows = document.getElementById('api-sandbox-params');
    var rowTemplate = document.getElementById('api-sandbox-param-row');
    var addButton = document.getElementById('api-sandbox-add-param');
    var resetButton = document.getElementById('api-sandbox-reset');
    var copyButton = document.getElementById('api-sandbox-copy-url');
    var submitButton = document.getElementById('api-sandbox-submit');
    var fileField = document.getElementById('api-sandbox-file-field');
    var fileInput = document.getElementById('api-sandbox-file');
    var methodEl = document.getElementById('api-sandbox-method');
    var requestUrlEl = document.getElementById('api-sandbox-request-url');
    var requestBodyEl = document.getElementById('api-sandbox-request-body');
    var statusEl = document.getElementById('api-sandbox-status');
    var errorEl = document.getElementById('api-sandbox-error');
    var responseEl = document.getElementById('api-sandbox-response');
    var lastRequestUrl = '';
    var lastController = null;
    var tokenCache = '';

    var WRITE_ACTIONS = ['edit', 'move', 'delete', 'protect', 'block', 'unblock', 'upload',
        'rollback', 'login', 'logout'];
    var RESERVED = ['action', 'format', 'formatversion', 'token', 'callback'];
    var PRESETS = {
        query: [['meta', 'siteinfo'], ['formatversion', '2']],
        parse: [['text', '== 示例 ==\n\nHello, WikiKernel!'], ['prop', 'text']],
        edit: [['title', '沙盒'], ['text', 'Hello, WikiKernel!'], ['summary', 'API sandbox test']],
        help: [],
        categorytree: [['category', '示例'], ['options', '{"depth":1}']],
        templatedata: [['titles', '模板:示例']]
    };

    function addRow(name, value) {
        var fragment = rowTemplate.content.cloneNode(true);
        var row = fragment.querySelector('.api-sandbox-param-row');
        row.querySelector('.api-sandbox-param-name').value = name || '';
        row.querySelector('.api-sandbox-param-value').value = value || '';
        row.querySelector('.api-sandbox-param-remove').addEventListener('click', function () {
            row.remove();
        });
        rows.appendChild(row);
    }

    function clearRows() {
        rows.textContent = '';
    }

    function currentAction() {
        return actionSelect.value || 'query';
    }

    function loadPreset() {
        clearRows();
        (PRESETS[currentAction()] || []).forEach(function (pair) {
            addRow(pair[0], pair[1]);
        });
        fileField.hidden = currentAction() !== 'upload';
    }

    function pairs() {
        var result = [['action', currentAction()], ['format', formatSelect.value]];
        if (version2.checked) {
            result.push(['formatversion', '2']);
        }
        Array.prototype.forEach.call(rows.querySelectorAll('.api-sandbox-param-row'), function (row) {
            var name = row.querySelector('.api-sandbox-param-name').value.trim();
            if (!name || RESERVED.indexOf(name) >= 0) {
                return;
            }
            result.push([name, row.querySelector('.api-sandbox-param-value').value]);
        });
        return result;
    }

    function toSearchParams(list) {
        var params = new URLSearchParams();
        list.forEach(function (pair) {
            params.append(pair[0], pair[1]);
        });
        return params;
    }

    function isWrite() {
        return WRITE_ACTIONS.indexOf(currentAction()) >= 0;
    }

    function csrfToken() {
        if (tokenCache) {
            return Promise.resolve(tokenCache);
        }
        if (window.mw && window.mw.user && window.mw.user.tokens) {
            var injected = window.mw.user.tokens.get('csrfToken');
            if (injected) {
                tokenCache = injected;
                return Promise.resolve(injected);
            }
        }
        var url = new URL(endpoint, window.location.href);
        url.search = new URLSearchParams({
            action: 'query', meta: 'tokens', type: 'csrf', format: 'json'
        }).toString();
        return fetch(url.toString(), { credentials: 'same-origin' })
            .then(function (response) { return response.json(); })
            .then(function (data) {
                var token = data && data.query && data.query.tokens && data.query.tokens.csrftoken;
                if (!token) {
                    throw new Error('No CSRF token');
                }
                tokenCache = token;
                return token;
            });
    }

    function showError(text) {
        if (!errorEl) {
            return;
        }
        errorEl.textContent = text || '';
        errorEl.hidden = !text;
    }

    function showRequest(url, method, list) {
        lastRequestUrl = url;
        methodEl.textContent = method;
        requestUrlEl.href = url;
        requestUrlEl.textContent = url;
        if (method === 'GET') {
            requestBodyEl.hidden = true;
            requestBodyEl.textContent = '';
            return;
        }
        requestBodyEl.hidden = false;
        requestBodyEl.textContent = list.map(function (pair) {
            return pair[0] + '=' + (pair[0] === 'token' ? '***' : pair[1]);
        }).join('\n');
    }

    function renderResponse(text, contentType, status, started) {
        var pretty = text;
        if (contentType.indexOf('json') >= 0) {
            try {
                var parsed = JSON.parse(text);
                pretty = JSON.stringify(parsed, null, 2);
                if (parsed && parsed.error) {
                    showError((parsed.error.code || 'error') + ': ' + (parsed.error.info || ''));
                } else {
                    showError('');
                }
            } catch (ignored) {
                showError('');
            }
        } else {
            showError('');
        }
        responseEl.textContent = pretty;
        statusEl.textContent = status + ' · ' + (Date.now() - started) + ' ms';
    }

    function execute() {
        var list = pairs();
        var method = isWrite() ? 'POST' : 'GET';
        var started = Date.now();
        showError('');
        if (lastController) {
            lastController.abort();
        }
        lastController = typeof AbortController === 'function' ? new AbortController() : null;
        var options = {
            method: method,
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' },
            signal: lastController ? lastController.signal : undefined
        };

        var prepare = isWrite() && autoToken.checked && currentAction() !== 'login'
            ? csrfToken().then(function (token) {
                if (!list.some(function (pair) { return pair[0] === 'token'; })) {
                    list.push(['token', token]);
                }
            })
            : Promise.resolve();

        prepare.then(function () {
            if (currentAction() === 'upload') {
                var data = new FormData();
                list.forEach(function (pair) { data.append(pair[0], pair[1]); });
                if (fileInput.files[0]) {
                    data.append('file', fileInput.files[0]);
                }
                options.body = data;
                showRequest(endpoint, method, list);
                return fetch(endpoint, options);
            }
            var params = toSearchParams(list);
            if (method === 'GET') {
                var url = new URL(endpoint, window.location.href);
                url.search = params.toString();
                showRequest(url.toString(), method, list);
                return fetch(url.toString(), options);
            }
            options.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
            options.body = params.toString();
            showRequest(endpoint, method, list);
            return fetch(endpoint, options);
        }).then(function (response) {
            return response.text().then(function (text) {
                renderResponse(text, response.headers.get('content-type') || '', response.status, started);
            });
        }).catch(function (error) {
            if (error && error.name === 'AbortError') {
                return;
            }
            showError(error && error.message ? error.message : 'Network error');
        }).finally(function () {
            if (submitButton) {
                submitButton.disabled = false;
            }
        });
    }

    addButton.addEventListener('click', function () {
        addRow('', '');
    });
    actionSelect.addEventListener('change', loadPreset);
    resetButton.addEventListener('click', loadPreset);
    copyButton.addEventListener('click', function () {
        if (!lastRequestUrl) {
            return;
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(lastRequestUrl);
        }
    });
    form.addEventListener('submit', function (event) {
        event.preventDefault();
        if (submitButton) {
            submitButton.disabled = true;
        }
        execute();
    });

    loadPreset();
})();
