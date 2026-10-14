/*
 * 编辑预览（仿 MediaWiki「显示预览」）：
 * 点「显示预览」把编辑框内容 POST 到 /api/pages/preview 服务端渲染（不落盘），
 * 结果显示在编辑框上方（含目录折叠、脚注、分类栏、模板 / 微件等）；
 * 未登录 / 无编辑权限时显示服务端返回的错误提示。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var form = document.getElementById('wiki-edit-form');
    var button = document.getElementById('wiki-preview-button');
    var preview = document.getElementById('wiki-preview');
    if (!form || !button || !preview) {
        return;
    }
    var textarea = form.querySelector('textarea[name="content"]');
    var contentBox = document.getElementById('wiki-preview-content');
    if (!textarea || !contentBox) {
        return;
    }
    var title = form.getAttribute('data-title') || '';

    function msg(key) {
        return MESSAGES[key] || key;
    }

    /** 预览区里的目录折叠（toc.js 只在页面加载时绑定一次，预览内容是后插入的，这里补绑）。 */
    function bindTocToggles() {
        Array.prototype.forEach.call(preview.querySelectorAll('.toc .toctoggle'), function (toggle) {
            toggle.addEventListener('click', function () {
                var toc = toggle.closest ? toggle.closest('.toc') : null;
                if (toc) {
                    toc.classList.toggle('toc-hidden');
                }
            });
        });
    }

    function showError(text) {
        contentBox.innerHTML = '';
        var error = document.createElement('div');
        error.className = 'form-message';
        error.textContent = text;
        contentBox.appendChild(error);
        preview.hidden = false;
        preview.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    button.addEventListener('click', function () {
        var originalText = button.textContent;
        button.disabled = true;
        button.textContent = msg('previewLoading');
        var body = new URLSearchParams();
        body.append('title', title);
        body.append('content', textarea.value);
        fetch('/api/pages/preview', {
            method: 'POST',
            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
            body: body
        }).then(function (response) {
            return response.json().catch(function () {
                return null;
            });
        }).then(function (result) {
            button.disabled = false;
            button.textContent = originalText;
            if (result && result.code === 200 && result.data && typeof result.data.html === 'string') {
                contentBox.innerHTML = result.data.html;
                preview.hidden = false;
                bindTocToggles();
                preview.scrollIntoView({ behavior: 'smooth', block: 'start' });
                return;
            }
            showError((result && result.message) || msg('previewFailed'));
        }).catch(function () {
            button.disabled = false;
            button.textContent = originalText;
            showError(msg('networkError'));
        });
    });
})();
