/*
 * 编辑预览（仿 MediaWiki「显示预览」）：
 * 点「显示预览」把编辑框内容 POST 到 /api/pages/preview 服务端渲染（不落盘），
 * 工具栏实时预览复用同一接口，在输入停顿后刷新编辑区旁的预览面板。
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

    function source() {
        return window.wikiCodeEditor ? window.wikiCodeEditor.getValue() : textarea.value;
    }

    /** 预览目录的内容是后插入的，需要单独绑定折叠按钮。 */
    function renderContent(target, html) {
        target.innerHTML = html;
        target.querySelectorAll('.toc .toctoggle').forEach(function (toggle) {
            toggle.addEventListener('click', function () {
                var toc = toggle.closest ? toggle.closest('.toc') : null;
                if (toc) {
                    toc.classList.toggle('toc-hidden');
                }
            });
        });
    }

    function showError(target, text) {
        target.replaceChildren();
        var error = document.createElement('div');
        error.className = 'form-message';
        error.textContent = text;
        target.appendChild(error);
    }

    async function fetchPreview(text, signal) {
        var response = await fetch('/api/pages/preview', {
            method: 'POST',
            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
            body: new URLSearchParams({ title: title, content: text }),
            signal: signal
        });
        var result = await response.json().catch(function () { return null; });
        if (!result || result.code !== 200 || !result.data || typeof result.data.html !== 'string') {
            throw new Error((result && result.message) || msg('previewFailed'));
        }
        return result.data.html;
    }

    button.addEventListener('click', async function () {
        var originalText = button.textContent;
        button.disabled = true;
        button.textContent = msg('previewLoading');
        try {
            renderContent(contentBox, await fetchPreview(source()));
        } catch (error) {
            showError(contentBox, error instanceof TypeError ? msg('networkError') : error.message);
        } finally {
            button.disabled = false;
            button.textContent = originalText;
        }
        if (!document.getElementById('visual-editor') || document.getElementById('visual-editor').hidden) {
            preview.hidden = false;
            preview.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    });

    var workspace = document.getElementById('wk-edit-workspace');
    var livePanel = document.getElementById('wk-live-preview');
    var toggle = document.getElementById('wk-live-preview-toggle');
    if (!workspace || !livePanel || !toggle) {
        return;
    }
    var viewTabs = workspace.querySelector('.wk-preview-tabs');
    var liveContent = livePanel.querySelector('.wk-live-preview-content');
    var liveStatus = livePanel.querySelector('.wk-live-preview-status');
    var refresh = document.getElementById('wk-live-preview-refresh');
    var timer;
    var request;
    var renderedText;

    function cancelPending() {
        clearTimeout(timer);
        if (request) {
            request.abort();
            request = null;
        }
        liveStatus.textContent = '';
        liveContent.removeAttribute('aria-busy');
    }

    async function updateLive(force) {
        cancelPending();
        var text = source();
        if (livePanel.hidden || (!force && text === renderedText)) {
            return;
        }
        var controller = new AbortController();
        request = controller;
        liveStatus.textContent = msg('previewLoading');
        liveContent.setAttribute('aria-busy', 'true');
        try {
            var html = await fetchPreview(text, controller.signal);
            if (request === controller) {
                renderContent(liveContent, html);
                renderedText = text;
            }
        } catch (error) {
            if (request === controller) {
                showError(liveContent, error instanceof TypeError ? msg('networkError') : error.message);
                renderedText = undefined;
            }
        } finally {
            if (request === controller) {
                request = null;
                liveStatus.textContent = '';
                liveContent.removeAttribute('aria-busy');
            }
        }
    }

    function showView(view) {
        workspace.dataset.previewView = view;
        viewTabs.querySelectorAll('[data-preview-view]').forEach(function (item) {
            item.setAttribute('aria-pressed', String(item.dataset.previewView === view));
        });
        if (view === 'source' && !workspace.hidden && window.wikiCodeEditor) {
            window.wikiCodeEditor.setVisible(true);
        }
    }

    function setLive(open) {
        livePanel.hidden = !open;
        viewTabs.hidden = !open;
        workspace.classList.toggle('wk-live-open', open);
        toggle.setAttribute('aria-pressed', String(open));
        showView(open ? 'preview' : 'source');
        if (open) {
            updateLive(false);
        } else {
            cancelPending();
        }
    }

    toggle.addEventListener('click', function () { setLive(livePanel.hidden); });
    refresh.addEventListener('click', function () { updateLive(true); });
    viewTabs.addEventListener('click', function (event) {
        var tab = event.target.closest('[data-preview-view]');
        if (tab) {
            showView(tab.dataset.previewView);
        }
    });
    textarea.addEventListener('input', function () {
        if (!livePanel.hidden) {
            cancelPending();
            timer = setTimeout(function () { updateLive(false); }, 650);
        }
    });
    form.addEventListener('wiki-edit-mode-change', function (event) {
        button.disabled = event.detail.visual;
        if (event.detail.visual) {
            setLive(false);
        }
    });
})();
