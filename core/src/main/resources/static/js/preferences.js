/*
 * 「特殊:参数设置」交互：
 *  - 标签切换（.pref-tab → .pref-section）与顶部搜索框过滤标签
 *  - 保存：整个表单 POST /api/user/preferences（成功刷新以应用皮肤 / 语言）
 *  - 重置：POST /api/user/preferences/reset（确认后）
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var form = document.getElementById('preferences-form');
    if (!form) {
        return;
    }

    var tabs = Array.prototype.slice.call(document.querySelectorAll('.pref-tab'));
    var sections = Array.prototype.slice.call(document.querySelectorAll('.pref-section'));

    function showTab(target) {
        tabs.forEach(function (tab) {
            tab.classList.toggle('selected', tab.getAttribute('data-target') === target);
        });
        sections.forEach(function (section) {
            section.hidden = section.getAttribute('data-tab') !== target;
        });
    }

    tabs.forEach(function (tab) {
        tab.addEventListener('click', function () {
            showTab(tab.getAttribute('data-target'));
        });
    });
    var hash = (location.hash || '').replace('#', '');
    if (hash && sections.some(function (section) {
        return section.getAttribute('data-tab') === hash;
    })) {
        showTab(hash);
    }

    var search = document.getElementById('pref-search');
    if (search) {
        search.addEventListener('input', function () {
            var keyword = search.value.trim().toLowerCase();
            tabs.forEach(function (tab) {
                var match = !keyword || tab.textContent.toLowerCase().indexOf(keyword) >= 0;
                tab.style.display = match ? '' : 'none';
            });
        });
    }

    /*
     * 国家 / 地区下拉框的输入搜索（渐进增强）：
     *  - 搜索框由本脚本注入到下拉框上方：无 JS 时页面仍是一个完整的原生下拉框，表单照常可用
     *    （不用 input + datalist，因为那样会丢掉地区代码的语义）
     *  - 页面载入时把这 249 项缓存进内存（代码 → 名称），之后按输入重建 <option>（只留匹配项）
     *  - 匹配本地化名称与地区代码，大小写不敏感的子串；清空输入还原全部地区
     *  - 「未指定」始终在最前；当前已选中的地区若不匹配，钉在「未指定」之后，避免搜索把已保存的值弄丢
     *  - Enter 选中第一个匹配项（并阻止提交整个表单）、Esc 清空还原；输入法组合期间不抢按键
     */
    (function () {
        var field = document.querySelector('[data-country-search]');
        // 钩子的值是下拉框的 id（模板里 data-country-search="pref-country"）
        var select = field
                ? document.getElementById(field.getAttribute('data-country-search') || '')
                : null;
        if (!field || !select) {
            return;
        }

        var all = [];
        var unspecified = { value: '', label: '' };
        for (var i = 0; i < select.options.length; i++) {
            var option = select.options[i];
            if (option.value === '') {
                unspecified = { value: '', label: option.textContent };
            } else {
                all.push({ value: option.value, label: option.textContent });
            }
        }

        var box = document.createElement('div');
        box.className = 'country-search';

        var input = document.createElement('input');
        input.type = 'search';
        input.id = 'pref-country-search';
        input.autocomplete = 'off';
        input.setAttribute('aria-controls', select.id);
        input.setAttribute('aria-label', MESSAGES.prefCountrySearch || 'preferences.countrySearch');
        input.placeholder = MESSAGES.prefCountrySearchPlaceholder
                || 'preferences.countrySearchPlaceholder';

        var count = document.createElement('p');
        count.id = 'pref-country-search-count';
        count.className = 'wiki-hint country-search-count';
        count.setAttribute('aria-live', 'polite');
        count.hidden = true;

        box.appendChild(input);
        box.appendChild(count);
        field.insertBefore(box, select);

        function entryOf(value) {
            for (var i = 0; i < all.length; i++) {
                if (all[i].value === value) {
                    return all[i];
                }
            }
            return { value: value, label: value };
        }

        function newOption(entry, pinned) {
            var option = document.createElement('option');
            option.value = entry.value;
            option.textContent = entry.label;
            if (pinned) {
                option.setAttribute('data-country-pinned', '1');
            }
            return option;
        }

        /** 按输入重建下拉框，返回第一个匹配项（没有匹配时返回 null）。 */
        function render(query) {
            var keyword = (query || '').trim().toLowerCase();
            var selected = select.value;
            var matches = [];
            for (var i = 0; i < all.length; i++) {
                var entry = all[i];
                if (!keyword
                        || entry.label.toLowerCase().indexOf(keyword) >= 0
                        || entry.value.toLowerCase().indexOf(keyword) >= 0) {
                    matches.push(entry);
                }
            }
            var pinned = null;
            if (selected !== '') {
                var inMatches = false;
                for (var j = 0; j < matches.length; j++) {
                    if (matches[j].value === selected) {
                        inMatches = true;
                        break;
                    }
                }
                if (!inMatches) {
                    // 已选中的地区不匹配当前输入：钉在「未指定」之后（下面再把它选回来）
                    pinned = entryOf(selected);
                }
            }

            select.textContent = '';
            select.appendChild(newOption(unspecified, false));
            if (pinned) {
                select.appendChild(newOption(pinned, true));
            }
            for (var k = 0; k < matches.length; k++) {
                select.appendChild(newOption(matches[k], false));
            }
            select.value = selected;

            if (!keyword) {
                count.hidden = true;
                count.textContent = '';
            } else {
                count.hidden = false;
                count.textContent = matches.length === 0
                        ? (MESSAGES.prefCountrySearchNone || 'preferences.countrySearchNone')
                        : (MESSAGES.prefCountrySearchCount || 'preferences.countrySearchCount')
                                .replace('{0}', String(matches.length));
            }
            // 没有输入或没有匹配项时，Enter 不动当前选择
            if (!keyword || matches.length === 0) {
                return null;
            }
            return matches[0];
        }

        var composing = false;
        // 空输入时 Enter 不改动选择（首次输入 / 组合结束时会更新）
        var firstMatch = null;

        input.addEventListener('compositionstart', function () {
            composing = true;
        });
        input.addEventListener('compositionend', function () {
            composing = false;
            firstMatch = render(input.value);
        });
        input.addEventListener('input', function () {
            if (composing) {
                return;
            }
            firstMatch = render(input.value);
        });
        input.addEventListener('keydown', function (event) {
            if (composing || event.isComposing) {
                return;
            }
            if (event.key === 'Escape' || event.key === 'Esc') {
                event.preventDefault();
                input.value = '';
                firstMatch = render('');
                return;
            }
            if (event.key === 'Enter') {
                // 搜索框在表单里：不拦住 Enter 会直接提交整个参数设置表单
                event.preventDefault();
                if (firstMatch) {
                    select.value = firstMatch.value;
                }
            }
        });
    })();

    function showMessage(text, success) {
        var box = document.getElementById('preferences-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        var button = form.querySelector('button[type="submit"]');
        if (button) {
            button.disabled = true;
        }
        fetch('/api/user/preferences', {
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
                showMessage(result.message || '', true);
                window.location.reload();
                return;
            }
            showMessage((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
            if (button) {
                button.disabled = false;
            }
        }).catch(function () {
            showMessage(MESSAGES.networkError || 'auth.networkError');
            if (button) {
                button.disabled = false;
            }
        });
    });

    var reset = document.getElementById('preferences-reset');
    if (reset) {        reset.addEventListener('click', function () {
            if (!window.confirm(MESSAGES.prefResetConfirm || 'preferences.resetConfirm')) {
                return;
            }
            reset.disabled = true;
            fetch('/api/user/preferences/reset', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest'
                }
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                if (result && result.code === 200) {
                    window.location.reload();
                    return;
                }
                showMessage((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
                reset.disabled = false;
            }).catch(function () {
                showMessage(MESSAGES.networkError || 'auth.networkError');
                reset.disabled = false;
            });
        });
    }

    // 时区：一键填入浏览器时区（参数设置 → 国际化）
    var timeZoneDetect = document.getElementById('pref-timezone-detect');
    if (timeZoneDetect) {
        timeZoneDetect.addEventListener('click', function () {
            var select = document.getElementById('pref-timezone');
            if (!select) {
                return;
            }
            var zone = '';
            try {
                zone = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
            } catch (error) {
                zone = '';
            }
            if (!zone) {
                showMessage(MESSAGES.timeZoneUnavailable || 'preferences.timezoneUnavailable', false);
                return;
            }
            var found = false;
            for (var i = 0; i < select.options.length; i++) {
                if (select.options[i].value === zone) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                var option = document.createElement('option');
                option.value = zone;
                option.textContent = zone;
                select.appendChild(option);
            }
            select.value = zone;
        });
    }

    // 发送邮箱确认邮件（参数设置 → 电子邮件选项）
    var confirmEmail = document.getElementById('email-confirm-button');
    if (confirmEmail) {
        confirmEmail.addEventListener('click', function () {
            confirmEmail.disabled = true;
            fetch('/api/user/email/confirm', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest'
                }
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                if (result && result.code === 200) {
                    showMessage(result.message || '', true);
                    window.location.reload();
                    return;
                }
                showMessage((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
                confirmEmail.disabled = false;
            }).catch(function () {
                showMessage(MESSAGES.networkError || 'auth.networkError');
                confirmEmail.disabled = false;
            });
        });
    }
    // 修改密码（参数设置 → 用户资料）
    var passwordForm = document.getElementById('preferences-password-form');
    if (passwordForm) {
        passwordForm.addEventListener('submit', function (event) {
            event.preventDefault();
            var message = document.getElementById('password-message');
            var button = passwordForm.querySelector('button[type="submit"]');

            function show(text, success) {
                if (!message) {
                    return;
                }
                message.textContent = text;
                message.className = 'form-message' + (success ? ' success' : '');
                message.hidden = false;
            }

            if (button) {
                button.disabled = true;
            }
            fetch('/api/user/preferences/password', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: new URLSearchParams(new FormData(passwordForm))
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                if (result && result.code === 200) {
                    show(result.message || '', true);
                    passwordForm.reset();
                } else {
                    show((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed', false);
                }
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
    }
})();
