/*
 * 「特殊:大量删除」交互：
 *  - 全选复选框切换所有行
 *  - 「删除所选页面」提交勾选的 titles；未勾选时也提交，由服务端返回 nuke.selectedNone 提示
 *  - 「删除全部匹配页面」提交当前列出的所有 titles
 *  - 提交前用模板注入的 data-confirm-template 做 confirm()，成功后刷新
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var form = document.getElementById('wiki-nuke-form');
    if (!form) {
        return;
    }

    var checkboxes = form.querySelectorAll('input[name="titles"]');
    var selectAll = document.getElementById('nuke-select-all');

    if (selectAll) {
        selectAll.addEventListener('change', function () {
            Array.prototype.forEach.call(checkboxes, function (box) {
                box.checked = selectAll.checked;
            });
        });
    }

    function showMessage(text) {
        var box = form.querySelector('.form-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message';
        box.hidden = false;
    }

    function selectedTitles() {
        return Array.prototype.filter.call(checkboxes, function (box) {
            return box.checked;
        }).map(function (box) {
            return box.value;
        });
    }

    function allTitles() {
        return Array.prototype.map.call(checkboxes, function (box) {
            return box.value;
        });
    }

    /** 用按钮上的 data-confirm-template 生成确认文案（__N__ = 本次提交的页面数）。 */
    function confirmDelete(button, count) {
        var template = button.getAttribute('data-confirm-template') || '';
        return window.confirm(template.replace('__N__', count));
    }

    function submit(titles, button) {
        var body = new URLSearchParams();
        titles.forEach(function (title) {
            body.append('titles', title);
        });
        var reason = form.querySelector('input[name="reason"]');
        if (reason) {
            body.append('reason', reason.value);
        }

        button.disabled = true;
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
                window.location.reload();
                return;
            }
            showMessage((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
            button.disabled = false;
        }).catch(function () {
            showMessage(MESSAGES.networkError || 'auth.networkError');
            button.disabled = false;
        });
    }

    var selectedButton = document.getElementById('nuke-delete-selected');
    if (selectedButton) {
        selectedButton.addEventListener('click', function (event) {
            event.preventDefault();
            var titles = selectedTitles();
            if (titles.length > 0 && !confirmDelete(selectedButton, titles.length)) {
                return;
            }
            submit(titles, selectedButton);
        });
    }

    var allButton = document.getElementById('nuke-delete-all');
    if (allButton) {
        allButton.addEventListener('click', function (event) {
            event.preventDefault();
            var titles = allTitles();
            if (!confirmDelete(allButton, titles.length)) {
                return;
            }
            submit(titles, allButton);
        });
    }
})();
