/*
 * 安装向导（/init）前端：
 *  - tab 切换（核心未完成时插件 tab 锁定）
 *  - 核心设置 / 插件设置保存
 *  - 完成初始化：写入 YAML →（可选）自动重启 → 轮询 /api/version 后回首页
 */
(function () {
    'use strict';

    var MESSAGES = window.SETUP_MESSAGES || {};
    var coreCompleted = window.SETUP_CORE_COMPLETED === true;

    var tabs = Array.prototype.slice.call(document.querySelectorAll('.setup-tab'));
    var panels = Array.prototype.slice.call(document.querySelectorAll('.setup-panel'));

    function activate(name) {
        tabs.forEach(function (tab) {
            tab.classList.toggle('active', tab.getAttribute('data-tab') === name);
        });
        panels.forEach(function (panel) {
            panel.hidden = panel.getAttribute('data-panel') !== name;
        });
    }

    function updateLock() {
        tabs.forEach(function (tab) {
            var isPlugin = tab.getAttribute('data-tab').indexOf('plugin:') === 0;
            tab.classList.toggle('locked', isPlugin && !coreCompleted);
        });
        var finish = document.getElementById('setup-finish');
        if (finish) {
            finish.disabled = !coreCompleted;
        }
    }

    tabs.forEach(function (tab) {
        tab.addEventListener('click', function () {
            if (tab.classList.contains('locked')) {
                return;
            }
            activate(tab.getAttribute('data-tab'));
        });
    });

    function showMessage(text, success) {
        var box = document.getElementById('setup-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }

    function post(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
            body: body
        }).then(function (response) {
            return response.json();
        });
    }

    function formBody(form) {
        var body = new URLSearchParams();
        new FormData(form).forEach(function (value, key) {
            body.append(key, value);
        });
        return body;
    }

    // 数据库字段显隐
    var dbType = document.getElementById('setup-db-type');
    function updateDbFields() {
        var type = dbType ? dbType.value : '';
        document.querySelectorAll('[data-db]').forEach(function (element) {
            var targets = (element.getAttribute('data-db') || '').split(/\s+/);
            element.hidden = targets.indexOf(type) < 0;
        });
    }
    if (dbType) {
        dbType.addEventListener('change', updateDbFields);
        updateDbFields();
    }

    // 邮件字段显隐
    var emailEnabled = document.getElementById('setup-email-enabled');
    function updateEmailFields() {
        var on = emailEnabled && emailEnabled.checked;
        document.querySelectorAll('[data-email]').forEach(function (element) {
            element.hidden = !on;
        });
    }
    if (emailEnabled) {
        emailEnabled.addEventListener('change', updateEmailFields);
        updateEmailFields();
    }

    // 缓存字段显隐：总开关关闭时不展开配置项；后端只用进程内缓存 / 不缓存时不需要 Redis 连接
    var cacheEnabled = document.getElementById('setup-cache-enabled');
    var cacheBackend = document.getElementById('setup-cache-backend');
    function updateCacheFields() {
        var on = !!(cacheEnabled && cacheEnabled.checked);
        var backend = cacheBackend ? cacheBackend.value : 'auto';
        var redis = on && (backend === 'auto' || backend === 'redis');
        document.querySelectorAll('[data-cache]').forEach(function (element) {
            element.hidden = !on;
        });
        document.querySelectorAll('[data-cache-redis]').forEach(function (element) {
            element.hidden = !redis;
        });
    }
    if (cacheEnabled) {
        cacheEnabled.addEventListener('change', updateCacheFields);
    }
    if (cacheBackend) {
        cacheBackend.addEventListener('change', updateCacheFields);
    }
    updateCacheFields();

    // 核心设置
    var coreForm = document.getElementById('setup-core-form');
    if (coreForm) {
        coreForm.addEventListener('submit', function (event) {
            event.preventDefault();
            var button = coreForm.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
            }
            post('/api/setup/core', formBody(coreForm)).then(function (result) {
                if (button) {
                    button.disabled = false;
                }
                if (result && result.code === 200) {
                    coreCompleted = true;
                    updateLock();
                    showMessage(result.message || MESSAGES.saved, true);
                    var firstPlugin = document.querySelector('.setup-tab[data-tab^="plugin:"]');
                    if (firstPlugin) {
                        activate(firstPlugin.getAttribute('data-tab'));
                    }
                } else {
                    showMessage((result && result.message) || MESSAGES.operationFailed, false);
                }
            }).catch(function () {
                if (button) {
                    button.disabled = false;
                }
                showMessage(MESSAGES.networkError, false);
            });
        });
    }

    // 插件设置
    Array.prototype.forEach.call(document.querySelectorAll('.setup-plugin-form'), function (form) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            var pluginId = form.getAttribute('data-plugin-id');
            var button = form.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
            }
            post('/api/setup/plugin/' + encodeURIComponent(pluginId), formBody(form)).then(function (result) {
                if (button) {
                    button.disabled = false;
                }
                if (result && result.code === 200) {
                    showMessage(result.message || MESSAGES.saved, true);
                } else {
                    showMessage((result && result.message) || MESSAGES.operationFailed, false);
                }
            }).catch(function () {
                if (button) {
                    button.disabled = false;
                }
                showMessage(MESSAGES.networkError, false);
            });
        });
    });

    // 完成初始化
    var finishButton = document.getElementById('setup-finish');
    if (finishButton) {
        finishButton.addEventListener('click', function () {
            finishButton.disabled = true;
            showMessage(MESSAGES.finishing, true);
            post('/api/setup/finish', new URLSearchParams()).then(function (result) {
                if (!result || result.code !== 200) {
                    finishButton.disabled = false;
                    showMessage((result && result.message) || MESSAGES.operationFailed, false);
                    return;
                }
                showMessage(result.message || MESSAGES.finished, true);
                var restarting = result.data && result.data.restarting;
                if (!restarting) {
                    return;
                }
                // 等待旧进程退出后再开始轮询，避免在关闭窗口里导航失败
                setTimeout(function () {
                    var attempts = 0;
                    var timer = setInterval(function () {
                        attempts++;
                        if (attempts > 60) {
                            clearInterval(timer);
                            finishButton.disabled = false;
                            return;
                        }
                        fetch('/api/version', { cache: 'no-store' }).then(function (response) {
                            if (response.ok) {
                                clearInterval(timer);
                                window.location.replace('/');
                            }
                        }).catch(function () {
                            /* 服务重启中，继续轮询 */
                        });
                    }, 1500);
                }, 2500);
            }).catch(function () {
                finishButton.disabled = false;
                showMessage(MESSAGES.networkError, false);
            });
        });
    }

    // 已安装后的 /init 解锁（密钥页）
    var unlockForm = document.getElementById('setup-unlock-form');
    if (unlockForm) {
        unlockForm.addEventListener('submit', function (event) {
            event.preventDefault();
            var button = unlockForm.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
            }
            post('/api/setup/unlock', formBody(unlockForm)).then(function (result) {
                if (result && result.code === 200) {
                    window.location.reload();
                    return;
                }
                if (button) {
                    button.disabled = false;
                }
                showMessage((result && result.message) || MESSAGES.operationFailed, false);
            }).catch(function () {
                if (button) {
                    button.disabled = false;
                }
                showMessage(MESSAGES.networkError, false);
            });
        });
    }

    updateLock();
})();
