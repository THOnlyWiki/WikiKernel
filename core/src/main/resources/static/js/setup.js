/*
 * 安装向导（/init）前端：
 *  - tab 切换（核心未完成时插件 tab 锁定）
 *  - 脏字段检测：任何核心 / 插件表单改动都会点亮对应 tab
 *  - 保存全部：点击任一保存按钮时，按「核心 → 插件」顺序保存所有有改动的表单
 *  - 完成初始化：先保存全部未保存项，再写入 YAML →（可选）自动重启 → 轮询服务就绪后回首页
 */
(function () {
    'use strict';

    var MESSAGES = window.SETUP_MESSAGES || {};
    var coreCompleted = window.SETUP_CORE_COMPLETED === true;
    var saving = false;

    var tabs = Array.prototype.slice.call(document.querySelectorAll('.setup-tab'));
    var panels = Array.prototype.slice.call(document.querySelectorAll('.setup-panel'));
    var forms = Array.prototype.slice.call(document.querySelectorAll('#setup-core-form, .setup-plugin-form'));
    var baselines = new Map();

    function activate(name) {
        tabs.forEach(function (tab) {
            tab.classList.toggle('active', tab.getAttribute('data-tab') === name);
        });
        panels.forEach(function (panel) {
            panel.hidden = panel.getAttribute('data-panel') !== name;
        });
    }

    function formTab(form) {
        if (!form) {
            return '';
        }
        if (form.id === 'setup-core-form') {
            return 'core';
        }
        var pluginId = form.getAttribute('data-plugin-id');
        return pluginId ? 'plugin:' + pluginId : '';
    }

    function serialize(form) {
        var body = new URLSearchParams();
        new FormData(form).forEach(function (value, key) {
            body.append(key, value);
        });
        return body.toString();
    }

    function markClean(form) {
        baselines.set(form, serialize(form));
    }

    function isDirty(form) {
        return baselines.get(form) !== serialize(form);
    }

    function dirtyForms() {
        return forms.filter(isDirty);
    }

    function updateDirtyUI() {
        var dirty = dirtyForms();
        var tabsWithDirty = new Set();
        dirty.forEach(function (form) {
            var tab = formTab(form);
            if (tab) {
                tabsWithDirty.add(tab);
            }
        });
        tabs.forEach(function (tab) {
            tab.classList.toggle('dirty', tabsWithDirty.has(tab.getAttribute('data-tab')));
        });
        var status = document.getElementById('setup-dirty-status');
        if (status) {
            if (dirty.length === 0) {
                status.textContent = MESSAGES.noChanges || '没有未保存的修改';
                status.className = 'setup-dirty-status';
            } else {
                var text = MESSAGES.dirtyStatus || '有 {0} 个标签页未保存';
                status.textContent = text.replace('{0}', String(tabsWithDirty.size));
                status.className = 'setup-dirty-status dirty';
            }
        }
        var saveAll = document.getElementById('setup-save-all');
        if (saveAll) {
            saveAll.disabled = dirty.length === 0 || saving;
        }
        var finish = document.getElementById('setup-finish');
        if (finish) {
            // 首次安装时允许「修改了核心设置但还没点保存」直接点完成：finish 会先保存全部
            finish.disabled = !coreCompleted && dirty.length === 0;
        }
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
        updateDirtyUI();
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

    function saveForm(form) {
        var url = form.id === 'setup-core-form'
            ? '/api/setup/core'
            : '/api/setup/plugin/' + encodeURIComponent(form.getAttribute('data-plugin-id'));
        return post(url, formBody(form)).then(function (result) {
            if (!result || result.code !== 200) {
                showMessage((result && result.message) || MESSAGES.operationFailed, false);
                return false;
            }
            markClean(form);
            if (form.id === 'setup-core-form') {
                coreCompleted = true;
                updateLock();
            }
            updateDirtyUI();
            return true;
        }).catch(function () {
            showMessage(MESSAGES.networkError, false);
            return false;
        });
    }

    /**
     * 保存所有有改动的表单。顺序固定为「核心 → 插件」：
     * 核心保存成功后插件 tab 才解锁，因此首次安装也不会跳过核心设置。
     */
    function saveAllChanges(quiet) {
        if (saving) {
            return Promise.resolve(false);
        }
        var dirty = dirtyForms();
        if (dirty.length === 0) {
            if (!quiet) {
                showMessage(MESSAGES.noChanges, true);
            }
            return Promise.resolve(true);
        }
        saving = true;
        var saveAll = document.getElementById('setup-save-all');
        if (saveAll) {
            saveAll.disabled = true;
        }
        showMessage(MESSAGES.savingAll, true);
        var chain = Promise.resolve(true);
        dirty.forEach(function (form) {
            chain = chain.then(function (ok) {
                return ok ? saveForm(form) : false;
            });
        });
        return chain.then(function (ok) {
            saving = false;
            updateDirtyUI();
            if (ok) {
                showMessage(MESSAGES.allSaved, true);
            }
            return ok;
        });
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

    // 初始化基线 + 监听所有字段
    forms.forEach(function (form) {
        markClean(form);
        form.addEventListener('input', updateDirtyUI);
        form.addEventListener('change', updateDirtyUI);
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            saveAllChanges();
        });
    });

    // 所有保存按钮都走「保存全部」：跨 tab 的修改不会因为只点了当前 tab 而丢失
    var saveAllButton = document.getElementById('setup-save-all');
    if (saveAllButton) {
        saveAllButton.addEventListener('click', function () {
            saveAllChanges();
        });
    }

    // 离开页面前提醒未保存
    window.addEventListener('beforeunload', function (event) {
        if (dirtyForms().length > 0) {
            event.preventDefault();
            event.returnValue = MESSAGES.unsavedWarning || '';
            return event.returnValue;
        }
    });

    function finishSetup() {
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
            setTimeout(function () {
                var attempts = 0;
                var timer = setInterval(function () {
                    attempts++;
                    if (attempts > 80) {
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
    }

    var finishButton = document.getElementById('setup-finish');
    if (finishButton) {
        finishButton.addEventListener('click', function () {
            if (!coreCompleted && dirtyForms().length === 0) {
                showMessage(MESSAGES.saveFirst, false);
                return;
            }
            // 先保存其它 tab 的脏数据，再 finish（finish 只读服务端内存）
            saveAllChanges(true).then(function (ok) {
                if (ok) {
                    finishSetup();
                } else {
                    finishButton.disabled = false;
                }
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
