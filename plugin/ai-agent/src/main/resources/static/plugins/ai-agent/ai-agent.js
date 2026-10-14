/*
 * 「特殊:AI代理」交互：
 *  - #ai-agent-send：把输入框内容发给 POST /api/ai-agent/chat（可带 AI 厂商），渲染回复（Markdown）/ 步骤 / 待确认提案；
 *  - **多条目提案**：#ai-agent-proposal-list 列出本次会话的全部待确认提案（正文 + 它引用的模板等），
 *    可点条目切换审阅、「确认提交这个」逐个提交，或点「全部确认提交」一键提交（POST /api/ai-agent/apply-all）；
 *  - 刷新还原：GET /api/ai-agent/session 取回对话记录、步骤轨迹与全部待确认提案；
 *  - 提案面板：预览（服务端渲染）/ 差异 / Wikitext（**可直接改**）三个页签；
 *  - 所有动态文案由模板注入到 #ai-agent-root 的 data-msg-* 属性（随界面语言切换）。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var root = document.getElementById('ai-agent-root');
    if (!root) {
        return;
    }

    var msg = function (name) { return root.getAttribute('data-msg-' + name) || ''; };
    var messages = document.getElementById('ai-agent-messages');
    var status = document.getElementById('ai-agent-status');
    var input = document.getElementById('ai-agent-input');
    var providerSelect = document.getElementById('ai-agent-provider');
    var sendButton = document.getElementById('ai-agent-send');
    var stepList = document.getElementById('ai-agent-step-list');
    var stepCount = document.getElementById('ai-agent-step-count');
    var listBox = document.getElementById('ai-agent-proposal-list');
    var listItems = document.getElementById('ai-agent-proposal-items');
    var listCount = document.getElementById('ai-agent-proposal-count');
    var applyAllButton = document.getElementById('ai-agent-apply-all');
    var panel = document.getElementById('ai-agent-proposal');
    var panelTitle = document.getElementById('ai-agent-proposal-title');
    var panelMeta = document.getElementById('ai-agent-proposal-meta');
    var conflictBox = document.getElementById('ai-agent-conflict');
    var commentInput = document.getElementById('ai-agent-comment');
    var sourceInput = document.getElementById('ai-agent-source');
    var sourceStatus = document.getElementById('ai-agent-source-status');
    var refreshPreviewButton = document.getElementById('ai-agent-refresh-preview');
    var applyButton = document.getElementById('ai-agent-apply');
    var regenerateButton = document.getElementById('ai-agent-regenerate');
    var discardButton = document.getElementById('ai-agent-discard');
    var demoNotice = document.getElementById('ai-agent-demo-notice');

    /** 演示厂商 id（模板从厂商目录注入到 data-demo-providers；不在脚本里写死）。 */
    var demoProviders = String(root.getAttribute('data-demo-providers') || '')
        .split(',')
        .map(function (value) { return value.trim(); })
        .filter(function (value) { return value !== ''; });

    var session = '';
    var proposals = [];
    var currentProposal = null;
    var busy = false;
    // 演示厂商（type=mock）锁定态：输入框 disabled + 发送按钮置灰 + 提示条可见
    var demoLocked = false;
    // 非演示厂商下的原始 placeholder（切回真实厂商时要还原）
    var defaultPlaceholder = input ? (input.getAttribute('placeholder') || '') : '';

    function showStatus(text, success) {
        status.textContent = text || '';
        status.className = 'form-message' + (success ? ' success' : '');
        status.hidden = !text;
    }

    function clearStatus() {
        status.hidden = true;
        status.textContent = '';
    }

    function addMessage(role, text, html) {
        var bubble = document.createElement('div');
        bubble.className = 'ai-agent-message ai-agent-message-' + role;
        var label = document.createElement('div');
        label.className = 'ai-agent-message-role';
        label.textContent = role === 'assistant' ? 'AI' : (msg('you'));
        var body = document.createElement('div');
        body.className = 'ai-agent-message-body';
        if (html) {
            body.className += ' ai-agent-md';
            body.innerHTML = html;
        } else {
            body.textContent = text || '';
        }
        bubble.appendChild(label);
        bubble.appendChild(body);
        messages.appendChild(bubble);
        messages.scrollTop = messages.scrollHeight;
        return body;
    }

    function renderSteps(steps) {
        if (!steps || !steps.length) {
            return;
        }
        stepList.innerHTML = '';
        var labels = {
            model: msg('kind-model'),
            tool: msg('kind-tool'),
            notice: msg('kind-notice'),
            final: msg('kind-final'),
            user: msg('you')
        };
        steps.forEach(function (step) {
            var item = document.createElement('li');
            var kind = document.createElement('span');
            kind.className = 'ai-agent-step-kind';
            kind.textContent = labels[step.kind] || step.kind;
            var detail = document.createElement('span');
            detail.textContent = step.detail;
            item.appendChild(kind);
            item.appendChild(detail);
            stepList.appendChild(item);
        });
        if (stepCount) {
            var template = msg('steps-count');
            stepCount.textContent = template ? template.replace('{0}', steps.length) : '';
        }
    }

    function setBusy(value) {
        busy = value;
        // 忙闲切换后演示锁仍要生效（演示厂商下发送按钮始终保持禁用）
        sendButton.disabled = value || demoLocked;
        applyButton.disabled = value;
        regenerateButton.disabled = value;
        discardButton.disabled = value;
        if (applyAllButton) {
            applyAllButton.disabled = value;
        }
    }

    /** 忙闲 + 演示锁合并后的发送按钮状态（setBusy 之外的状态变化走这里）。 */
    function syncSendButton() {
        sendButton.disabled = busy || demoLocked;
    }

    /** 当前选中的厂商是不是演示厂商（回复是固定示例，不是真实 AI）。 */
    function isDemoProvider() {
        return !!providerSelect && demoProviders.indexOf(providerSelect.value) !== -1;
    }

    /**
     * 演示厂商锁定：**锁住提示词输入**，免得用户以为这是免费 API、写一大堆内容只拿到固定示例。
     * 用 disabled（可以连焦点与 Ctrl+Enter 一起禁掉；textarea 不参与表单提交，页面也没有表单提交）。
     * 选中非演示厂商（切换回真实厂商）时全部还原。
     */
    function applyDemoLock() {
        demoLocked = isDemoProvider();
        if (input) {
            input.disabled = demoLocked;
            input.placeholder = demoLocked
                ? (msg('demo-lock-placeholder') || defaultPlaceholder)
                : defaultPlaceholder;
        }
        if (demoNotice) {
            demoNotice.hidden = !demoLocked;
        }
        syncSendButton();
    }

    function showSourceStatus(text, success) {
        if (!sourceStatus) {
            return;
        }
        sourceStatus.textContent = text || '';
        sourceStatus.className = 'wiki-hint' + (success ? ' success' : '');
        sourceStatus.hidden = !text;
    }

    function proposalById(id) {
        for (var index = 0; index < proposals.length; index++) {
            if (proposals[index].id === id) {
                return proposals[index];
            }
        }
        return null;
    }

    /** 渲染「待确认提案」列表（多条目），并把 keepId（若有）或第一条显示在下方面板。 */
    function renderProposals(list, keepId) {
        proposals = list || [];
        if (!proposals.length) {
            listBox.hidden = true;
            listItems.innerHTML = '';
            renderProposal(null, true);
            return;
        }
        listBox.hidden = false;
        var keep = keepId && proposalById(keepId) ? keepId : proposals[0].id;
        listItems.innerHTML = '';
        proposals.forEach(function (proposal, index) {
            var item = document.createElement('li');
            item.className = 'ai-agent-proposal-item';
            item.setAttribute('data-proposal', proposal.id);
            var title = document.createElement('span');
            title.className = 'ai-agent-proposal-item-title';
            title.textContent = (index + 1) + '. ' + proposal.title;
            var meta = document.createElement('span');
            meta.className = 'ai-agent-proposal-item-meta';
            var parts = [proposal.creating ? (msg('new')) : (msg('edit')),
                proposal.bytes + ' B'];
            if (proposal.missingTemplates && proposal.missingTemplates.length) {
                parts.push((msg('proposal-missing-short'))
                    .replace('{0}', proposal.missingTemplates.length));
            }
            meta.textContent = parts.join(' · ');
            var apply = document.createElement('button');
            apply.type = 'button';
            apply.className = 'wiki-button ai-agent-proposal-item-apply';
            apply.textContent = msg('apply-this');
            item.appendChild(title);
            item.appendChild(meta);
            if (proposal.conflict) {
                var conflict = document.createElement('span');
                conflict.className = 'ai-agent-proposal-item-conflict';
                conflict.textContent = msg('proposal-conflict-short');
                item.appendChild(conflict);
            }
            item.appendChild(apply);
            listItems.appendChild(item);
        });
        var countTemplate = msg('proposal-count-text');
        listCount.textContent = countTemplate ? countTemplate.replace('{0}', proposals.length) : '';
        renderProposal(proposalById(keep), true);
    }

    function renderProposal(proposal, silent) {
        currentProposal = proposal;
        if (listItems) {
            Array.prototype.forEach.call(listItems.querySelectorAll('.ai-agent-proposal-item'), function (item) {
                item.classList.toggle('selected', proposal !== null
                    && item.getAttribute('data-proposal') === proposal.id);
            });
        }
        if (!proposal) {
            panel.hidden = true;
            if (sourceInput) {
                sourceInput.value = '';
            }
            showSourceStatus('');
            return;
        }
        panel.hidden = false;
        panelTitle.textContent = proposal.title;
        var parts = [];
        parts.push(proposal.creating ? (msg('new')) : (msg('edit')));
        parts.push('· ' + (msg('base')) + ' ' + (proposal.baseRevisionId == null ? '—' : proposal.baseRevisionId));
        parts.push('· ' + (msg('current')) + ' ' + (proposal.currentRevisionId == null ? '—' : proposal.currentRevisionId));
        parts.push('· ' + (msg('bytes')) + ' ' + proposal.bytes);
        if (proposal.missingTemplates && proposal.missingTemplates.length) {
            parts.push('· ' + (msg('proposal-missing'))
                .replace('{0}', proposal.missingTemplates.join(msg('list-separator'))));
        }
        panelMeta.textContent = parts.join(' ');
        conflictBox.hidden = !proposal.conflict;
        if (proposal.conflict) {
            conflictBox.textContent = (msg('conflict'))
                .replace('{0}', proposal.baseRevisionId).replace('{1}', proposal.currentRevisionId);
        }
        panel.querySelector('[data-panel="preview"]').innerHTML = proposal.previewHtml || '';
        panel.querySelector('[data-panel="diff"]').innerHTML = proposal.diffHtml
            || '<p class="wiki-hint">' + msg('no-old-version') + '</p>';
        if (sourceInput) {
            sourceInput.value = proposal.content || '';
        }
        showSourceStatus('');
        commentInput.value = proposal.comment || '';
        selectTab('preview');
        if (!silent) {
            panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    }

    function selectTab(name) {
        Array.prototype.forEach.call(panel.querySelectorAll('.ai-agent-tab'), function (tab) {
            tab.classList.toggle('selected', tab.getAttribute('data-tab') === name);
        });
        Array.prototype.forEach.call(panel.querySelectorAll('[data-panel]'), function (view) {
            view.hidden = view.getAttribute('data-panel') !== name;
        });
    }

    panel.addEventListener('click', function (event) {
        var target = event.target;
        var tab = target && target.closest ? target.closest('.ai-agent-tab') : null;
        if (tab) {
            selectTab(tab.getAttribute('data-tab'));
        }
    });

    // 列表：点条目切换审阅，点「确认提交这个」单独提交
    listItems.addEventListener('click', function (event) {
        var target = event.target;
        if (!target || !target.closest) {
            return;
        }
        var item = target.closest('.ai-agent-proposal-item');
        if (!item) {
            return;
        }
        var proposal = proposalById(item.getAttribute('data-proposal'));
        if (!proposal) {
            return;
        }
        if (target.closest('.ai-agent-proposal-item-apply')) {
            applyProposal(proposal);
            return;
        }
        renderProposal(proposal);
    });

    function post(path, fields) {
        var body = new URLSearchParams();
        Object.keys(fields).forEach(function (key) {
            if (fields[key] !== null && fields[key] !== undefined) {
                body.append(key, fields[key]);
            }
        });
        return fetch(path, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: body
        }).then(function (response) {
            return response.json();
        });
    }

    function chat(text) {
        if (busy || !text) {
            return;
        }
        // 兜底：绕开界面（例如直接调用本函数）也不能把内容发给演示厂商——它只会回固定示例
        if (isDemoProvider()) {
            applyDemoLock();
            showStatus(msg('demo-lock-notice') || '', false);
            return;
        }
        clearStatus();
        addMessage('user', text);
        var pending = addMessage('assistant', msg('thinking'));
        setBusy(true);
        post('/api/ai-agent/chat', {
            session: session,
            provider: providerSelect ? providerSelect.value : '',
            message: text
        }).then(function (result) {
            setBusy(false);
            if (!result || result.code !== 200) {
                var failed = (result && result.message) || (msg('failed'));
                pending.textContent = failed;
                showStatus(failed, false);
                return;
            }
            var data = result.data || {};
            session = data.session || session;
            if (data.messageHtml) {
                pending.className = 'ai-agent-message-body ai-agent-md';
                pending.innerHTML = data.messageHtml;
            } else {
                pending.textContent = data.message || '';
            }
            messages.scrollTop = messages.scrollHeight;
            renderSteps(data.steps || []);
            var list = data.proposals || (data.proposal ? [data.proposal] : []);
            // 新一轮对话后默认选中最新提出的提案（列表最后一个）
            renderProposals(list, list.length ? list[list.length - 1].id : '');
        }).catch(function () {
            setBusy(false);
            pending.textContent = MESSAGES.networkError || '网络错误，请稍后重试';
            showStatus(MESSAGES.networkError || '网络错误，请稍后重试', false);
        });
    }

    sendButton.addEventListener('click', function () {
        // 演示厂商：按钮本来就是置灰的（这里再挡一次），也不清空用户已经写下的内容
        if (isDemoProvider()) {
            return;
        }
        chat(input.value.trim());
        input.value = '';
    });

    // 切换厂商：进入演示厂商立刻锁住输入，切回真实厂商立刻还原
    if (providerSelect) {
        providerSelect.addEventListener('change', applyDemoLock);
    }

    input.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
            event.preventDefault();
            chat(input.value.trim());
            input.value = '';
        }
    });

    /** 用当前（可能被用户改过的）Wikitext 重新渲染预览（api.php action=parse，与编辑页同一套渲染）。 */
    function refreshPreview() {
        if (!sourceInput || !currentProposal) {
            return;
        }
        var body = new URLSearchParams();
        body.append('action', 'parse');
        body.append('format', 'json');
        body.append('formatversion', '2');
        body.append('prop', 'text');
        body.append('title', currentProposal.title);
        body.append('text', sourceInput.value);
        refreshPreviewButton.disabled = true;
        fetch('/api.php', {
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
            refreshPreviewButton.disabled = false;
            var parsed = result && result.parse ? result.parse : null;
            var html = parsed ? parsed.text : '';
            if (html && typeof html === 'object') {
                html = html['*'] || '';
            }
            if (!html) {
                showSourceStatus(msg('preview-failed'), false);
                return;
            }
            panel.querySelector('[data-panel="preview"]').innerHTML = html;
            selectTab('preview');
            showSourceStatus(msg('preview-refreshed'), true);
        }).catch(function () {
            refreshPreviewButton.disabled = false;
            showSourceStatus(msg('preview-failed'), false);
        });
    }

    if (refreshPreviewButton) {
        refreshPreviewButton.addEventListener('click', refreshPreview);
    }

    /** 提交单个提案（选中的那个可带上用户改过的正文）。 */
    function applyProposal(proposal) {
        if (busy || !proposal) {
            return;
        }
        setBusy(true);
        var fields = { session: session, proposal: proposal.id, comment: commentInput.value };
        if (currentProposal && currentProposal.id === proposal.id && sourceInput) {
            fields.content = sourceInput.value;
        }
        post('/api/ai-agent/apply', fields).then(function (result) {
            setBusy(false);
            var data = (result && result.data) || {};
            if (result && result.code === 200 && data.ok) {
                showStatus((msg('applied')).replace('{0}', data.title), true);
                addMessage('system', data.message || '');
                renderProposals(data.remaining || proposals.filter(function (item) {
                    return item.id !== proposal.id;
                }), '');
                return;
            }
            if (data.code === 'conflict') {
                renderProposals(data.remaining || proposals, proposal.id);
                conflictBox.hidden = false;
                conflictBox.textContent = data.message || conflictBox.textContent;
                showStatus(data.message || '', false);
                return;
            }
            showStatus((result && result.message) || (msg('failed')), false);
        }).catch(function () {
            setBusy(false);
            showStatus(MESSAGES.networkError || '网络错误，请稍后重试', false);
        });
    }

    applyButton.addEventListener('click', function () {
        applyProposal(currentProposal);
    });

    /** 一键提交全部提案（多条目：正文 + 模板等）。 */
    function applyAll() {
        if (busy || !proposals.length) {
            return;
        }
        var confirmText = (msg('apply-all-confirm'))
            .replace('{0}', proposals.length);
        if (confirmText && !window.confirm(confirmText)) {
            return;
        }
        setBusy(true);
        post('/api/ai-agent/apply-all', { session: session, comment: commentInput.value }).then(function (result) {
            setBusy(false);
            var data = (result && result.data) || {};
            if (!result || result.code !== 200) {
                showStatus((result && result.message) || (msg('failed')), false);
                return;
            }
            showStatus(data.message || '', !data.failedCount);
            (data.applied || []).forEach(function (item) {
                addMessage('system', item.message || msg('applied').replace('{0}', item.title));
            });
            (data.failed || []).forEach(function (item) {
                addMessage('system', '× ' + (item.title || '') + msg('label-separator') + (item.message || item.code || ''));
            });
            renderProposals(data.remaining || [], '');
        }).catch(function () {
            setBusy(false);
            showStatus(MESSAGES.networkError || '网络错误，请稍后重试', false);
        });
    }

    if (applyAllButton) {
        applyAllButton.addEventListener('click', applyAll);
    }

    regenerateButton.addEventListener('click', function () {
        if (busy || !currentProposal) {
            return;
        }
        var text = (msg('regenerate'))
            .replace('{0}', currentProposal.title);
        renderProposal(null);
        chat(text);
    });

    discardButton.addEventListener('click', function () {
        if (busy || !currentProposal) {
            return;
        }
        var confirmText = msg('confirm-discard');
        if (confirmText && !window.confirm(confirmText)) {
            return;
        }
        setBusy(true);
        post('/api/ai-agent/discard', { session: session, proposal: currentProposal.id }).then(function (result) {
            setBusy(false);
            var data = (result && result.data) || {};
            showStatus(data.message || '', true);
            renderProposals(data.remaining || proposals.filter(function (item) {
                return item.id !== currentProposal.id;
            }), '');
        }).catch(function () {
            setBusy(false);
            showStatus(MESSAGES.networkError || '网络错误，请稍后重试', false);
        });
    });

    /**
     * 刷新还原：取回最近一次会话的对话记录（含 Markdown HTML）、步骤轨迹与**全部**待确认提案。
     * 会话在服务端（内存，TTL 见配置），所以刷新页面、或者关掉标签页再回来都不会丢。
     */
    function restore() {
        fetch('/api/ai-agent/session', { headers: { 'Accept': 'application/json' } })
            .then(function (response) {
                return response.json();
            })
            .then(function (result) {
                if (!result || result.code !== 200) {
                    return;
                }
                var data = result.data || {};
                if (!data.session) {
                    return;
                }
                session = data.session;
                if (data.provider && providerSelect) {
                    var known = false;
                    Array.prototype.forEach.call(providerSelect.options, function (option) {
                        if (option.value === data.provider) {
                            known = true;
                        }
                    });
                    if (known) {
                        providerSelect.value = data.provider;
                        // 恢复出来的厂商可能是演示厂商：锁状态要跟着变
                        applyDemoLock();
                    }
                }
                (data.messages || []).forEach(function (message) {
                    if (message.role === 'assistant') {
                        addMessage('assistant', message.text, message.html);
                    } else if (message.role === 'user') {
                        addMessage('user', message.text);
                    } else {
                        addMessage('system', message.text);
                    }
                });
                renderSteps(data.steps || []);
                renderProposals(data.proposals || (data.proposal ? [data.proposal] : []), '');
            })
            .catch(function () {
                // 还原失败不影响开始新对话
            });
    }

    // 页面加载后先按当前选中的厂商判定一次：目录里只有演示厂商（没配任何密钥的普通用户）时
    // 打开页面就是锁定态；有真实厂商且默认选中它时不会锁。
    applyDemoLock();
    restore();
})();
