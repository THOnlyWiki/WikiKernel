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

    // core 表单里的分区（插件分区在表单外、同一内容区里，各自带保存按钮）
    var coreTabs = sections
        .filter(function (section) {
            return form.contains(section);
        })
        .map(function (section) {
            return section.getAttribute('data-tab');
        });

    function showTab(target) {
        tabs.forEach(function (tab) {
            tab.classList.toggle('selected', tab.getAttribute('data-target') === target);
        });
        sections.forEach(function (section) {
            section.hidden = section.getAttribute('data-tab') !== target;
        });
        // 插件分区：把 core 表单（含底部保存条）整个收起来，避免留一块空面板 + 多余的保存按钮
        form.hidden = coreTabs.indexOf(target) < 0;
        // 记住当前标签：保存（AJAX 后 reload）与刷新 / 分享链接都会回到同一标签
        if (target && window.history && window.history.replaceState) {
            window.history.replaceState(null, '', '#' + target);
        }
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
            var visible = [];
            tabs.forEach(function (tab) {
                var match = !keyword || tab.textContent.toLowerCase().indexOf(keyword) >= 0;
                tab.style.display = match ? '' : 'none';
                if (match) {
                    visible.push(tab);
                }
            });
            var hint = document.getElementById('pref-search-empty');
            if (!visible.length) {
                // 没有匹配的分区：给一条提示（否则标签列整个空掉，看起来像页面坏了）
                if (!hint) {
                    hint = document.createElement('p');
                    hint.id = 'pref-search-empty';
                    hint.className = 'wiki-hint';
                    search.parentElement.appendChild(hint);
                }
                hint.textContent = MESSAGES.prefTabNoMatch || '没有匹配的设置分区。';
                hint.hidden = false;
                return;
            }
            if (hint) {
                hint.hidden = true;
            }
            // 过滤后当前标签被隐藏时，自动切到第一个匹配的分区（内容与标签列保持一致）
            var selected = document.querySelector('.pref-tab.selected');
            if (keyword && selected && selected.style.display === 'none') {
                showTab(visible[0].getAttribute('data-target'));
            }
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
})();
