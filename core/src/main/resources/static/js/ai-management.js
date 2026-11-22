(function () {
    'use strict';
    var root = document.querySelector('[data-ai-management]');
    if (!root) return;
    var status = root.querySelector('[data-status]');
    var labels = window.wikiAiManagementLabels;
    function node(tag, text) {
        var el = document.createElement(tag);
        if (text) el.textContent = text;
        return el;
    }
    async function request(path, body) {
        var response = await fetch('/api/ai-management' + path, body ? {
            method: 'POST', body: body, headers: {'X-Requested-With': 'XMLHttpRequest'}
        } : {cache: 'no-store'});
        if (!response.ok) throw new Error(labels.failed);
        return response.json();
    }
    function field(form, label, name, value, type) {
        var wrap = node('label', label);
        var input = node('input');
        input.name = name;
        input.type = type || 'text';
        if (input.type === 'checkbox') { input.value = 'true'; input.checked = !!value; }
        else input.value = value || '';
        if (input.type === 'password') input.autocomplete = 'new-password';
        wrap.appendChild(input); form.appendChild(wrap);
        return input;
    }
    async function load() {
        var data = await request('');
        var providers = root.querySelector('[data-providers]');
        providers.replaceChildren();
        data.providers.forEach(function (row) {
            var details = node('details');
            details.appendChild(node('summary', row.label + ' · ' + (row.siteMask || (row.yamlConfigured ? labels.configFile : labels.unconfigured))));
            var form = node('form'); form.dataset.providerForm = ''; form.className = 'wiki-form';
            field(form, '', 'id', row.id, 'hidden'); field(form, '', 'type', row.type, 'hidden');
            field(form, labels.label, 'label', row.label);
            field(form, labels.baseUrl, 'baseUrl', row.siteBaseUrl || row.defaultBaseUrl);
            field(form, labels.model, 'model', row.siteModel || row.defaultModel);
            field(form, labels.replaceKey, 'apiKey', '', 'password');
            field(form, labels.proxy, 'proxy', row.siteProxy);
            field(form, labels.allowPrivate, 'allowPrivate', row.allowPrivate, 'checkbox');
            field(form, labels.clearCredentials, 'clear', false, 'checkbox');
            var button = node('button', labels.save); button.type = 'submit'; form.appendChild(button);
            details.appendChild(form); providers.appendChild(details);
        });
        var scenes = root.querySelector('[data-scenes]'); scenes.replaceChildren();
        data.scenes.forEach(function (row) {
            var form = node('form'); form.dataset.sceneForm = ''; form.className = 'wiki-form';
            form.appendChild(node('h3', row.id === 'activity' ? labels.activity : labels.news));
            field(form, '', 'id', row.id, 'hidden');
            var label = node('label', labels.providers); var select = node('select'); select.name = 'provider';
            var placeholder = node('option', labels.choose);
            placeholder.value = '';
            select.appendChild(placeholder);
            data.providers.forEach(function (provider) {
                var option = node('option', provider.label); option.value = provider.id; select.appendChild(option);
            });
            select.value = row.provider || '';
            if (select.selectedIndex < 0) select.value = '';
            label.appendChild(select); form.appendChild(label);
            field(form, labels.groups, 'groups', row.groups);
            field(form, labels.enabled, 'enabled', row.enabled, 'checkbox');
            var save = node('button', labels.saveScene); save.type = 'submit'; form.appendChild(save);
            var test = node('button', labels.test); test.type = 'button';
            test.addEventListener('click', async function () {
                test.disabled = true;
                try { var result = await request('/test', new URLSearchParams({scene:row.id})); status.textContent = result.result; }
                catch (error) { status.textContent = error.message; }
                finally { test.disabled = false; }
            }); form.appendChild(test); scenes.appendChild(form);
        });
        var limits = root.querySelector('[data-limits-form]');
        var agent = root.querySelector('[data-agent-policy-form]');
        Object.keys(data.agent).forEach(function (key) { agent.elements[key].checked = data.agent[key]; });
        Object.keys(data.limits).forEach(function (key) { limits.elements[key].value = data.limits[key]; });
        var calls = root.querySelector('[data-calls]'); calls.replaceChildren();
        data.calls.forEach(function (call) {
            calls.appendChild(node('p', call.provider + ' · ' + call.model + ' · ' + call.outcome + ' · '
                + call.duration_ms + 'ms · ' + new Date(call.created_at).toLocaleString(document.documentElement.lang || undefined)));
        });
    }
    root.addEventListener('submit', async function (event) {
        var form = event.target; event.preventDefault();
        var button = form.querySelector('button[type="submit"]'); button.disabled = true;
        try {
            var path = '/scene';
            if (form.hasAttribute('data-provider-form')) path = '/provider';
            if (form.hasAttribute('data-limits-form')) path = '/limits';
            if (form.hasAttribute('data-agent-policy-form')) path = '/agent';
            await request(path, new URLSearchParams(new FormData(form)));
            status.textContent = labels.saved; await load();
        } catch (error) { status.textContent = error.message; }
        finally { button.disabled = false; }
    });
    load().catch(function (error) { status.textContent = error.message; });
})();
