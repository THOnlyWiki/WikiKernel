/*
 * 移动页面 / 保护页面 / 删除页面表单：提交到 /api/pages/*，成功后跳到返回的页面 URL。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    function showMessage(form, text, success) {
        var box = form.querySelector('.form-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    function protectionPicker(select, template) {
        var label = select.labels[0];
        var picker = template.content.firstElementChild.cloneNode(true);
        var modes = picker.querySelector('.wiki-group-modes');
        var optionsBox = picker.querySelector('.wiki-group-options');
        var search = optionsBox.querySelector('input');
        var choices = optionsBox.querySelector('.wiki-filter-choices');
        var empty = optionsBox.querySelector('.wiki-group-no-match');
        var scope = new Set(['__inherit__', '*', '__deny__']);
        var buttons = new Map();
        var groups = new Map();
        var options = Array.from(select.options);
        var selectedScope = options.find(function (option) { return option.selected && scope.has(option.value); });
        var current = selectedScope ? selectedScope.value : '';

        picker.querySelector('legend').textContent = label.textContent;
        modes.setAttribute('aria-label', label.textContent);
        optionsBox.id = select.id + '-choices';
        options.forEach(function (option) {
            if (scope.has(option.value)) {
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'wiki-button';
                button.textContent = option.textContent;
                buttons.set(option.value, button);
                modes.appendChild(button);
                return;
            }
            var item = document.createElement('label');
            item.className = 'wiki-filter-choice';
            var checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.checked = option.selected;
            checkbox.addEventListener('change', sync);
            var name = document.createElement('span');
            name.textContent = option.textContent;
            item.append(checkbox, name);
            choices.appendChild(item);
            groups.set(option.value, checkbox);
        });

        var custom = picker.querySelector('.wiki-group-custom');
        custom.setAttribute('aria-controls', optionsBox.id);
        buttons.set('', custom);
        modes.appendChild(custom);
        buttons.forEach(function (button, key) {
            button.addEventListener('click', function () {
                current = key;
                sync();
            });
        });
        search.hidden = groups.size < 8;
        search.addEventListener('input', function () {
            var query = search.value.trim().toLowerCase();
            var matches = false;
            groups.forEach(function (checkbox) {
                var label = checkbox.parentElement;
                label.hidden = !label.textContent.toLowerCase().includes(query);
                if (!label.hidden) matches = true;
            });
            empty.hidden = matches;
        });
        search.addEventListener('keydown', function (event) {
            if (event.key === 'Enter' && !event.isComposing) event.preventDefault();
        });

        function sync() {
            optionsBox.hidden = current !== '';
            buttons.forEach(function (button, key) {
                button.setAttribute('aria-pressed', String(key === current));
            });
            options.forEach(function (option) {
                var checkbox = groups.get(option.value);
                option.selected = current ? option.value === current : Boolean(checkbox && checkbox.checked);
            });
        }

        sync();
        select.hidden = true;
        label.hidden = true;
        select.after(picker);
        return function validate() {
            if (select.selectedOptions.length) return true;
            showMessage(select.form, picker.dataset.selectGroup);
            var firstVisible = choices.querySelector('label:not([hidden]) input');
            (firstVisible || (search.hidden ? custom : search)).focus();
            return false;
        };
    }

    function submit(form, checks) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            if (checks && !checks.every(function (validate) { return validate(); })) return;

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
                    var url = result.data && result.data.url ? result.data.url : '/';
                    window.location.href = url;
                    return;
                }
                showMessage(form, (result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
                if (button) {
                    button.disabled = false;
                }
            }).catch(function () {
                showMessage(form, MESSAGES.networkError || 'auth.networkError');
                if (button) {
                    button.disabled = false;
                }
            });
        });
    }

    var moveForm = document.getElementById('wiki-move-form');
    if (moveForm) {
        submit(moveForm);
    }

    var protectForm = document.getElementById('wiki-protect-form');
    if (protectForm) {
        var pickerTemplate = document.getElementById('wiki-protection-picker');
        var validators = pickerTemplate ? Array.from(protectForm.querySelectorAll('.wiki-protection-groups')).map(function (select) {
            return protectionPicker(select, pickerTemplate);
        }) : [];
        if (validators.length) {
            protectForm.querySelector('.protect-groups-fallback').hidden = true;
            protectForm.querySelector('.protect-groups-touch').hidden = false;
        }
        submit(protectForm, validators);
    }

    var deleteForm = document.getElementById('wiki-delete-form');
    if (deleteForm) {
        submit(deleteForm);
    }
})();
