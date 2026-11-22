/* Same-page queries use the original GET route and replace only the marked section. */
(function () {
    'use strict';

    const messages = window.WIKI_MESSAGES || {};
    function queryStatus(section, onRetry) {
        const status = document.createElement('div');
        status.className = 'wiki-query-status';
        status.hidden = true;
        const progress = document.createElement('span');
        progress.className = 'wiki-query-progress';
        const announcement = document.createElement('span');
        announcement.setAttribute('role', 'status');
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'wiki-button';
        retry.textContent = messages.queryRetry;
        retry.addEventListener('click', () => onRetry(retry));
        status.append(progress, announcement, retry);
        section.before(status);

        return function show(state) {
            section.setAttribute('aria-busy', String(state === 'loading'));
            progress.hidden = state !== 'loading';
            retry.hidden = state !== 'failed';
            status.hidden = !state;
            status.classList.toggle('wiki-query-status-silent', state === 'ready');
            announcement.textContent = state === 'loading' ? messages.queryLoading
                : state === 'failed' ? messages.queryFailed : messages.queryUpdated;
        };
    }

    window.WikiQueryStatus = queryStatus;
    const section = document.querySelector('[data-wiki-query]');
    if (!section || !window.fetch || !window.AbortController) return;
    section.tabIndex = -1;

    let pending;
    let failed;
    let displayed = new URL(location.href);

    function requestUrl(url) {
        for (const option of ['useskin', 'uselang']) {
            if (!url.searchParams.has(option) && displayed.searchParams.has(option)) {
                url.searchParams.set(option, displayed.searchParams.get(option));
            }
        }
        return url;
    }

    const showStatus = queryStatus(section, function (button) {
        if (failed) load(failed.url, failed.push, button);
    });

    async function load(url, push, trigger) {
        if (pending) pending.abort();
        const request = new AbortController();
        pending = request;
        failed = null;
        showStatus('loading');
        const timeout = setTimeout(function () { request.abort(); }, 15000);
        try {
            const endpoint = new URL(url);
            endpoint.hash = '';
            endpoint.searchParams.set('_wkfragment', 'query');
            const response = await fetch(endpoint, {signal: request.signal, redirect: 'error', cache: 'no-store'});
            if (!response.ok) throw new Error('HTTP ' + response.status);
            const html = await response.text();
            if (pending !== request) return;
            if (request.signal.aborted) throw new Error('Query timed out');
            const incoming = new DOMParser().parseFromString(html, 'text/html').body.firstElementChild;
            if (!incoming || incoming.dataset.wikiQuery !== section.dataset.wikiQuery) {
                throw new Error('Unexpected query response');
            }
            if (push && location.href !== url.href) history.pushState(history.state, '', url);
            displayed = url;
            const active = document.activeElement;
            const moveFocus = section.contains(active) || active === trigger;
            const focusId = section.contains(active) ? active.id : '';
            incoming.querySelectorAll('[autofocus]').forEach(el => el.removeAttribute('autofocus'));
            section.replaceChildren(...incoming.childNodes);
            section.dispatchEvent(new Event('wiki:query-updated', {bubbles: true}));
            if (moveFocus) {
                const sameControl = focusId && document.getElementById(focusId);
                (sameControl && section.contains(sameControl) ? sameControl : section).focus({preventScroll: true});
            }
            showStatus('ready');
        } catch (error) {
            if (pending !== request) return;
            failed = {url, push};
            showStatus('failed');
        } finally {
            clearTimeout(timeout);
            if (pending === request) pending = null;
        }
    }

    section.addEventListener('submit', function (event) {
        const form = event.target;
        const button = event.submitter;
        if (event.defaultPrevented || !(form instanceof HTMLFormElement)
                || (button?.getAttribute('formmethod') || form.getAttribute('method') || 'get').toLowerCase() !== 'get'
                || (button?.getAttribute('formtarget') || form.getAttribute('target') || '_self') !== '_self') return;
        // Named inputs such as LinkSearch's "target" can shadow the form's DOM properties.
        const action = button?.getAttribute('formaction') || form.getAttribute('action') || location.href;
        const url = new URL(action, location.href);
        if (url.origin !== location.origin) return;
        const fields = new FormData(form);
        if (button?.name) fields.append(button.name, button.value);
        url.search = new URLSearchParams(fields).toString();
        event.preventDefault();
        load(requestUrl(url), true, button);
    });

    section.addEventListener('click', function (event) {
        const link = event.target.closest('a[href]');
        if (event.defaultPrevented || !link || !link.closest('[data-wiki-query-links], [data-wiki-query-link]')
                || (link.target && link.target !== '_self') || link.hasAttribute('download')
                || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        const url = new URL(link.href);
        if (url.origin !== location.origin) return;
        event.preventDefault();
        load(requestUrl(url), true, link);
    });

    window.addEventListener('popstate', function () {
        const url = new URL(location.href);
        if (url.pathname !== displayed.pathname || url.search !== displayed.search) {
            load(url, false);
        } else if (pending || failed) {
            if (pending) pending.abort();
            pending = failed = null;
            showStatus(null);
        }
    });
})();
