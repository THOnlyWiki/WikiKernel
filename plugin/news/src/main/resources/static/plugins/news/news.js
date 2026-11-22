(function () {
    'use strict';
    var pattern = window.mw && mw.config.get('wgArticlePath') || '/wiki/$1';
    function page(title) { return pattern.replace('$1', title.split('/').map(encodeURIComponent).join('/')); }
    function el(tag, text, className) {
        var item = document.createElement(tag);
        if (text) item.textContent = text;
        if (className) item.className = className;
        return item;
    }
    function link(text, url) { var item = el('a', text); item.href = url; return item; }
    function message(container, text) { container.replaceChildren(el('p', text, 'news-meta')); }
    async function api(path, body, json) {
        var options = {cache:'no-store'};
        if (body) {
            options.method = 'POST'; options.headers = {'X-Requested-With':'XMLHttpRequest'};
            if (json) { options.headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(body); }
            else options.body = body;
        }
        var response = await fetch('/api/news' + path, options);
        var result = await response.json();
        if (!response.ok) throw Error(result.message || '操作失败，请检查权限后重试。');
        return result;
    }
    function date(value) { return value ? new Date(value).toLocaleString(undefined, {dateStyle:'medium', timeStyle:'short'}) : ''; }
    document.querySelectorAll('[data-news-time]').forEach(function (time) { time.textContent = date(time.dateTime); });
    function card(row, featured) {
        var c = row.content;
        var article = el('article', '', featured ? 'news-item news-featured' : 'news-item');
        if (featured && c.cover) {
            var image = el('img'); image.src = c.cover; image.alt = ''; image.loading = 'lazy';
            article.appendChild(image);
        }
        var meta = el('div', '', 'news-meta');
        meta.appendChild(el('span', c.category, 'news-kind' + (c.category === '变更' ? ' news-important' : '')));
        meta.appendChild(el('span', [c.region, date(c.publishAt)].filter(Boolean).join(' · ')));
        article.appendChild(meta);
        var heading = el(featured ? 'h3' : 'h4'); heading.appendChild(link(c.title, page('新闻/' + row.id)));
        article.appendChild(heading);
        if (c.summary) article.appendChild(el('p', c.summary, 'news-excerpt'));
        return article;
    }
    async function list(container, options, append) {
        var rows = await api('?' + new URLSearchParams(options));
        if (!append) container.replaceChildren();
        if (!rows.length && !append) message(container, '暂时没有已发布的资讯。');
        rows.forEach(function (row, i) { container.appendChild(card(row, !append && i === 0)); });
        return rows.length;
    }
    document.querySelectorAll('[data-news-list]').forEach(function (container) {
        var portal = container.closest('[data-news-page]');
        if (!portal) {
            var items = el('div', '', 'news-list-items');
            var links = el('nav', '', 'news-list-links'); links.setAttribute('aria-label', '新闻导航');
            links.appendChild(link('全部新闻', page('新闻')));
            links.appendChild(link('投稿与管理', page('特殊:新闻管理')));
            container.replaceChildren(items, links);
            var limit = Number(container.dataset.newsLimit) || 6;
            list(items, {homepage:true, limit:limit}).catch(function () { message(items, '资讯载入失败。'); });
            return;
        }
        var filter = portal.querySelector('form'); var more = portal.querySelector('[data-news-more]'); var offset = 0;
        api('/regions').then(function (regions) {
            regions.forEach(function (region) { var option = el('option', region); option.value = region; filter.elements.region.appendChild(option); });
        }).catch(function () {});
        async function refresh(append) {
            more.disabled = true;
            try {
                var query = Object.fromEntries(new FormData(filter)); query.offset = offset;
                var count = await list(container, query, append); offset += count; more.hidden = count < 20;
            } catch (error) { message(container, error.message); }
            finally { more.disabled = false; }
        }
        filter.addEventListener('submit', function (event) { event.preventDefault(); offset = 0; refresh(false); });
        more.addEventListener('click', function () { refresh(true); }); refresh(false);
    });

    var root = document.querySelector('[data-newsroom]');
    if (!root) return;
    root.querySelector('[data-news-url]').href = page('新闻');
    root.querySelector('[data-ai-url]').href = page('特殊:AI管理');
    var status = root.querySelector('[data-news-status]');
    var editor = root.querySelector('[data-news-editor]');
    var currentRow = null, changed = false, activityOptions = [];
    var activityRequest = 0, draftRequest = 0, loadedActivityYear;
    var narrow = window.matchMedia('(max-width: 700px)');
    if (narrow.matches) root.querySelector('[data-news-draft-list]').open = false;
    var syncSelects = typeof HTMLElement.prototype.showPopover === 'function'
        ? Array.from(editor.querySelectorAll('[data-news-select]'), enhanceSelect) : [];

    function enhanceSelect(select) {
        var id = 'news-select-' + select.name;
        var label = select.labels[0];
        var caption = label.firstChild.textContent.trim();
        var searchLabel = select.dataset.newsSelect;
        var trigger = el('button', '', 'wiki-button news-select-trigger');
        trigger.type = 'button';
        trigger.id = id;
        trigger.setAttribute('aria-haspopup', 'dialog');
        trigger.setAttribute('aria-controls', id + '-menu');
        trigger.setAttribute('aria-expanded', 'false');
        var panel = el('div', '', 'news-select-menu');
        panel.id = id + '-menu';
        panel.setAttribute('popover', 'auto');
        panel.setAttribute('role', 'dialog');
        panel.setAttribute('aria-label', caption);
        var list = el('div', '', 'news-select-options');
        var search;
        if (searchLabel) {
            search = document.createElement('input');
            search.type = 'search';
            search.placeholder = searchLabel;
            search.setAttribute('aria-label', searchLabel);
            search.addEventListener('input', renderOptions);
            panel.appendChild(search);
        }
        panel.appendChild(list);
        select.after(trigger);
        document.body.appendChild(panel);
        trigger.setAttribute('popovertarget', panel.id);

        function renderOptions() {
            var query = search ? search.value.trim().toLowerCase() : '';
            var options = Array.from(select.options).filter(function (option) {
                return !option.disabled && !option.hidden && option.textContent.toLowerCase().includes(query);
            });
            list.replaceChildren(...options.map(function (option) {
                var row = el('button', option.textContent, 'wiki-button news-select-option');
                row.type = 'button';
                row.setAttribute('aria-pressed', String(option.selected));
                row.addEventListener('click', function () {
                    panel.hidePopover();
                    trigger.focus({preventScroll:true});
                    if (!option.selected) {
                        select.value = option.value;
                        select.dispatchEvent(new Event('input', {bubbles:true}));
                        select.dispatchEvent(new Event('change', {bubbles:true}));
                    }
                });
                return row;
            }));
            if (!options.length) list.appendChild(el('p', '没有匹配的选项', 'news-select-empty'));
        }

        function position() {
            if (!panel.matches(':popover-open')) return;
            var anchor = trigger.getBoundingClientRect();
            var viewport = window.visualViewport;
            var top = viewport ? viewport.offsetTop : 0;
            var left = viewport ? viewport.offsetLeft : 0;
            var bottom = top + (viewport ? viewport.height : window.innerHeight);
            var width = viewport ? viewport.width : document.documentElement.clientWidth;
            if (!anchor.width || anchor.bottom <= top || anchor.top >= bottom) {
                panel.hidePopover();
                return;
            }
            var below = bottom - anchor.bottom - 12, above = anchor.top - top - 12;
            var opensUp = below < Math.min(panel.scrollHeight, 300) && above > below;
            var menuWidth = Math.min(Math.max(anchor.width, 180), width - 16);
            panel.style.width = menuWidth + 'px';
            panel.style.left = Math.max(left + 8, Math.min(anchor.left, left + width - menuWidth - 8)) + 'px';
            panel.style.top = opensUp ? 'auto' : anchor.bottom + 4 + 'px';
            panel.style.bottom = opensUp ? window.innerHeight - anchor.top + 4 + 'px' : 'auto';
            panel.style.maxHeight = Math.max(40, Math.min(300, opensUp ? above : below)) + 'px';
        }

        function sync() {
            var native = !searchLabel && narrow.matches;
            select.hidden = !native;
            trigger.hidden = native;
            label.htmlFor = native ? select.id : trigger.id;
            var selected = select.selectedOptions[0]?.textContent || '请选择';
            trigger.textContent = selected;
            trigger.setAttribute('aria-label', caption + '：' + selected);
            trigger.disabled = select.disabled;
            if (panel.matches(':popover-open')) {
                if (native || select.disabled) panel.hidePopover();
                else renderOptions();
            }
        }
        select.id = id + '-native';
        select.addEventListener('change', sync);
        narrow.addEventListener('change', sync);
        panel.addEventListener('toggle', function (event) {
            var open = event.newState === 'open';
            trigger.setAttribute('aria-expanded', String(open));
            if (!open) return;
            if (search) search.value = '';
            renderOptions();
            position();
            (search || list.querySelector('[aria-pressed="true"]') || list.firstElementChild)?.focus({preventScroll:true});
        });
        trigger.addEventListener('keydown', function (event) {
            if (event.key === 'ArrowDown' && !panel.matches(':popover-open')) {
                event.preventDefault();
                panel.showPopover();
            }
        });
        panel.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                event.preventDefault();
                panel.hidePopover();
                trigger.focus({preventScroll:true});
                return;
            }
            if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return;
            var rows = Array.from(list.querySelectorAll('button'));
            if (!rows.length) return;
            event.preventDefault();
            var index = rows.indexOf(document.activeElement);
            var next = event.key === 'ArrowDown' ? Math.min(index + 1, rows.length - 1)
                : index < 0 ? rows.length - 1 : index - 1;
            (next < 0 ? search || rows[rows.length - 1] : rows[next]).focus();
        });
        window.addEventListener('resize', position);
        window.addEventListener('scroll', position, true);
        window.visualViewport?.addEventListener('resize', position);
        window.visualViewport?.addEventListener('scroll', position);
        sync();
        return sync;
    }

    function syncFormSelects() { syncSelects.forEach(function (sync) { sync(); }); }
    var stateNames = {DRAFT:'草稿', PENDING:'待审核', PUBLISHED:'已发布', REJECTED:'已退回', WITHDRAWN:'已撤下'};
    var timeFields = ['publishAt','expiresAt','pinUntil'];
    function localTime(value) {
        if (!value) return '';
        var d = new Date(value); d.setMinutes(d.getMinutes() - d.getTimezoneOffset()); return d.toISOString().slice(0,16);
    }
    function content() {
        var data = Object.fromEntries(new FormData(editor));
        delete data.id; delete data.version;
        data.activityYear = Number(data.activityYear) || 0; data.activityId = Number(data.activityId) || 0;
        timeFields.forEach(function (key) { data[key] = data[key] ? new Date(data[key]).toISOString() : ''; });
        data.aiAssisted = !!(currentRow && currentRow.content.aiAssisted); data.aiModel = currentRow ? currentRow.content.aiModel : '';
        return data;
    }
    async function activities(year) {
        var select = editor.elements.activityId;
        select.replaceChildren(); var none = el('option', '不关联活动'); none.value = '0'; select.appendChild(none);
        var request = ++activityRequest;
        activityOptions = [];
        editor.elements.activityRevision.value = '';
        select.disabled = Boolean(year);
        syncFormSelects();
        try {
            var rows = year ? await api('/activities?year=' + encodeURIComponent(year)) : [];
            if (request !== activityRequest) return false;
            activityOptions = rows;
            loadedActivityYear = year;
            rows.forEach(function (a) { var option = el('option', a.name + ' · ' + a.date); option.value = a.id; select.appendChild(option); });
            return true;
        } catch (error) {
            if (request === activityRequest) status.textContent = error.message;
            return false;
        } finally {
            if (request === activityRequest) {
                select.disabled = false;
                syncFormSelects();
            }
        }
    }
    function updateActions() {
        var pending = currentRow && currentRow.status === 'PENDING' && !changed;
        root.querySelector('[data-news-action="submit"]').hidden = Boolean(pending);
        root.querySelector('[data-news-action="publish"]').hidden = !(pending && currentRow.canReview);
        root.querySelector('[data-news-action="reject"]').hidden = !(pending && currentRow.canReview);
        root.querySelector('[data-news-action="withdraw"]').hidden = !(currentRow && currentRow.canReview && currentRow.hasPublished);
    }
    async function show(row) {
        var request = ++draftRequest;
        currentRow = row || null; editor.reset();
        editor.elements.id.value = row ? row.id : 0; editor.elements.version.value = row ? row.version : 0;
        var historyList = root.querySelector('[data-news-history]');
        historyList.textContent = row ? '正在载入操作记录…' : '选择稿件后查看。';
        if (row) {
            Object.keys(row.content).forEach(function (key) {
                if (key === 'region' && row.content[key] && !Array.from(editor.elements.region.options).some(function (o) { return o.value === row.content[key]; })) {
                    var legacy = el('option', row.content[key]); legacy.value = row.content[key]; editor.elements.region.appendChild(legacy);
                }
                if (editor.elements[key] && !['activityId', 'activityRevision'].includes(key)) {
                    editor.elements[key].value = timeFields.includes(key) ? localTime(row.content[key]) : row.content[key];
                    if (key === 'activityYear' && !row.content[key]) editor.elements[key].value = '';
                }
            });
            root.querySelector('[data-editor-title]').textContent = stateNames[row.status] + ' · ' + (row.content.aiAssisted ? 'AI 协助起草' : '人工投稿');
        } else root.querySelector('[data-editor-title]').textContent = '新建投稿';
        root.querySelectorAll('[data-draft-id]').forEach(function (button) {
            if (row && Number(button.dataset.draftId) === row.id) button.setAttribute('aria-current', 'true');
            else button.removeAttribute('aria-current');
        });
        root.querySelector('[data-news-preview-area]').hidden = true;
        root.querySelector('[data-save-hint]').textContent = row && row.hasPublished ? '当前公开版本会保留，修改需重新提交审核。' : '';
        changed = false;
        updateActions();
        var year = Number(editor.elements.activityYear.value) || new Date().getFullYear();
        var loading = activities(year);
        var activityVersion = activityRequest;
        await loading;
        if (request !== draftRequest || !row) return;
        if (activityVersion === activityRequest && row.content.activityId) {
            var selected = editor.elements.activityId;
            if (!Array.from(selected.options).some(function (option) { return Number(option.value) === row.content.activityId; })) {
                var unavailable = el('option', '原关联活动 #' + row.content.activityId);
                unavailable.value = row.content.activityId;
                selected.appendChild(unavailable);
            }
            selected.value = row.content.activityId;
            editor.elements.activityRevision.value = row.content.activityRevision || '';
            syncFormSelects();
        }
        var history = await api('/history/' + row.id);
        if (request !== draftRequest) return;
        historyList.replaceChildren();
        history.forEach(function (revision) { historyList.appendChild(el('p', '版本 ' + revision.version + ' · ' + revision.action + ' · ' + date(revision.created_at))); });
    }
    async function drafts() {
        var rows = await api('/drafts'); var container = root.querySelector('[data-drafts]'); container.replaceChildren();
        if (!rows.length) container.appendChild(el('p', '暂无稿件', 'news-meta'));
        rows.forEach(function (row) {
            var button = el('button', (stateNames[row.status] || row.status) + ' · ' + row.content.title, 'wiki-button');
            button.dataset.draftId = row.id;
            if (currentRow && currentRow.id === row.id) button.setAttribute('aria-current', 'true');
            button.type = 'button'; button.addEventListener('click', function () {
                if (changed && !window.confirm('有尚未保存的修改，仍要切换稿件吗？')) return;
                if (narrow.matches) root.querySelector('[data-news-draft-list]').open = false;
                show(row).catch(function (e) { status.textContent = e.message; });
            }); container.appendChild(button);
        });
    }
    async function save() {
        if (editor.elements.activityId.disabled) throw Error('正在载入关联活动，请稍后再试。');
        if (!editor.reportValidity()) throw Error('请补全标题和正文。');
        var row = await api('/save', {id:Number(editor.elements.id.value), version:Number(editor.elements.version.value), content:content()}, true);
        await show(row); await drafts(); return row;
    }
    editor.addEventListener('input', function () { changed = true; updateActions(); });
    editor.elements.activityYear.addEventListener('change', function () { activities(editor.elements.activityYear.value).catch(function (e) { status.textContent = e.message; }); });
    editor.elements.activityId.addEventListener('change', function () {
        var match = activityOptions.find(function (a) { return a.id === Number(editor.elements.activityId.value); });
        editor.elements.activityRevision.value = match ? match.revision : '';
        if (match) editor.elements.activityYear.value = loadedActivityYear;
        if (match && Array.from(editor.elements.region.options).some(function (option) { return option.value === match.region; })) {
            editor.elements.region.value = match.region;
        }
        syncFormSelects();
    });
    editor.addEventListener('submit', async function (event) {
        event.preventDefault();
        try { await save(); status.textContent = '草稿已保存'; } catch (error) { status.textContent = error.message; }
    });
    root.querySelector('[data-news-new]').addEventListener('click', function () {
        if (!changed || window.confirm('放弃尚未保存的修改？')) {
            if (narrow.matches) root.querySelector('[data-news-draft-list]').open = false;
            show(null).catch(function (error) { status.textContent = error.message; });
        }
    });
    root.querySelector('[data-news-preview]').addEventListener('click', function () {
        var data = content(), preview = root.querySelector('[data-news-preview-area]'); preview.replaceChildren(); preview.hidden = false;
        preview.appendChild(el('h2', data.title)); preview.appendChild(el('p', data.summary)); preview.appendChild(el('div', data.body, 'news-body'));
        if (data.sourceUrl) preview.appendChild(el('p', '来源 ' + data.sourceName + ' ' + data.sourceUrl));
    });
    root.querySelectorAll('[data-news-action]').forEach(function (button) {
        button.addEventListener('click', async function () {
            button.disabled = true;
            try {
                var action = button.dataset.newsAction;
                if (action === 'submit' && (changed || !currentRow)) await save();
                else if (changed) throw Error('请先保存并重新提交审核，再处理此版本。');
                if (!currentRow) throw Error('请先保存稿件。');
                if (action === 'publish' && !window.confirm('已核对来源和正文，确认发布当前待审核版本？')) return;
                var row = await api('/action', new URLSearchParams({id:currentRow.id, version:currentRow.version, action:action}));
                await show(row); await drafts(); status.textContent = stateNames[row.status];
            } catch (error) { status.textContent = error.message; }
            finally { button.disabled = false; }
        });
    });
    var trackedJob = sessionStorage.getItem('news-job');
    async function pollJob(id) {
        var box = root.querySelector('[data-job-status]');
        try {
            var job = await api('/job/' + id); box.replaceChildren(el('p', {QUEUED:'正在排队…', RUNNING:'正在起草…', COMPLETED:'草稿已进入待审核列表。', FAILED:job.error}[job.state]));
            if (job.state === 'COMPLETED') {
                sessionStorage.removeItem('news-job'); await drafts();
                var open = el('button', '查看待审核草稿', 'wiki-button'); open.type = 'button';
                open.addEventListener('click', async function () {
                    if (!changed || window.confirm('放弃未保存修改，查看新草稿？')) await show(await api('/draft/' + job.newsId));
                }); box.appendChild(open);
            } else if (job.state === 'FAILED') {
                var retry = el('button', '重试', 'wiki-button'); retry.type = 'button'; retry.addEventListener('click', async function () { await api('/retry', new URLSearchParams({id:id})); pollJob(id); }); box.appendChild(retry);
            } else window.setTimeout(function () { pollJob(id); }, 2500);
        } catch (error) { box.textContent = error.message; }
    }
    root.querySelector('[data-news-generate]').addEventListener('submit', async function (event) {
        event.preventDefault(); var button = event.target.querySelector('button'); button.disabled = true;
        try {
            var result = await api('/generate', new URLSearchParams(new FormData(event.target)));
            sessionStorage.setItem('news-job', result.jobId); pollJob(result.jobId);
        } catch (error) { status.textContent = error.message; }
        finally { button.disabled = false; }
    });
    api('/state').then(async function (state) {
        if (!state.loggedIn) {
            root.querySelector('.newsroom-layout').hidden = true;
            status.replaceChildren(link('请先登录后投稿或审核新闻', page('特殊:登录') + '?returnto=' + encodeURIComponent(location.pathname)));
            return;
        }
        root.querySelector('.news-ai').hidden = !state.canGenerate;
        var regions = await api('/regions');
        regions.forEach(function (region) { var option = el('option', region); option.value = region; editor.elements.region.appendChild(option); });
        await Promise.all([drafts(), show(null)]);
        if (trackedJob) pollJob(trackedJob);
    }).catch(function (error) { status.textContent = error.message; });
})();
