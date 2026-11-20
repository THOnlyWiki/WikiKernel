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
        activityOptions = await api('/activities?year=' + year);
        select.replaceChildren(); var none = el('option', '不关联活动'); none.value = '0'; select.appendChild(none);
        activityOptions.forEach(function (a) { var option = el('option', a.name + ' · ' + a.date); option.value = a.id; select.appendChild(option); });
    }
    async function show(row) {
        currentRow = row || null; editor.reset();
        editor.elements.id.value = row ? row.id : 0; editor.elements.version.value = row ? row.version : 0;
        await activities(row && row.content.activityYear || new Date().getFullYear());
        if (row) {
            Object.keys(row.content).forEach(function (key) {
                if (key === 'region' && row.content[key] && !Array.from(editor.elements.region.options).some(function (o) { return o.value === row.content[key]; })) {
                    var legacy = el('option', row.content[key]); legacy.value = row.content[key]; editor.elements.region.appendChild(legacy);
                }
                if (editor.elements[key]) editor.elements[key].value = timeFields.includes(key) ? localTime(row.content[key]) : row.content[key];
            });
            root.querySelector('[data-editor-title]').textContent = stateNames[row.status] + ' · ' + (row.content.aiAssisted ? 'AI 协助起草' : '人工投稿');
            var history = await api('/history/' + row.id), list = root.querySelector('[data-news-history]'); list.replaceChildren();
            history.forEach(function (revision) { list.appendChild(el('p', '版本 ' + revision.version + ' · ' + revision.action + ' · ' + date(revision.created_at))); });
        } else root.querySelector('[data-editor-title]').textContent = '新建投稿';
        ['publish','reject','withdraw'].forEach(function (action) {
            root.querySelector('[data-news-action="' + action + '"]').hidden = !row || !row.canReview ||
                (action === 'withdraw' ? !row.hasPublished : row.status !== 'PENDING');
        });
        root.querySelector('[data-news-preview-area]').hidden = true;
        root.querySelector('[data-save-hint]').textContent = row && row.hasPublished ? '当前公开版本会保留，修改需重新提交审核。' : '';
        changed = false;
    }
    async function drafts() {
        var rows = await api('/drafts'); var container = root.querySelector('[data-drafts]'); container.replaceChildren();
        rows.forEach(function (row) {
            var button = el('button', (stateNames[row.status] || row.status) + ' · ' + row.content.title);
            button.type = 'button'; button.addEventListener('click', function () {
                if (changed && !window.confirm('有尚未保存的修改，仍要切换稿件吗？')) return;
                show(row).catch(function (e) { status.textContent = e.message; });
            }); container.appendChild(button);
        });
    }
    async function save() {
        if (!editor.reportValidity()) throw Error('请补全标题和正文。');
        var row = await api('/save', {id:Number(editor.elements.id.value), version:Number(editor.elements.version.value), content:content()}, true);
        await show(row); await drafts(); return row;
    }
    editor.addEventListener('input', function () { changed = true; });
    editor.elements.activityYear.addEventListener('change', function () { activities(editor.elements.activityYear.value).catch(function (e) { status.textContent = e.message; }); });
    editor.elements.activityId.addEventListener('change', function () {
        var match = activityOptions.find(function (a) { return a.id === Number(editor.elements.activityId.value); });
        editor.elements.activityRevision.value = match ? match.revision : '';
        if (match) editor.elements.region.value = match.region;
    });
    editor.addEventListener('submit', async function (event) {
        event.preventDefault();
        try { await save(); status.textContent = '草稿已保存'; } catch (error) { status.textContent = error.message; }
    });
    root.querySelector('[data-news-new]').addEventListener('click', function () {
        if (!changed || window.confirm('放弃尚未保存的修改？')) show(null);
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
                var open = el('button', '查看待审核草稿'); open.type = 'button';
                open.addEventListener('click', async function () {
                    if (!changed || window.confirm('放弃未保存修改，查看新草稿？')) await show(await api('/draft/' + job.newsId));
                }); box.appendChild(open);
            } else if (job.state === 'FAILED') {
                var retry = el('button', '重试'); retry.type = 'button'; retry.addEventListener('click', async function () { await api('/retry', new URLSearchParams({id:id})); pollJob(id); }); box.appendChild(retry);
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
