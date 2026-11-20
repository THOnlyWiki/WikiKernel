(function () {
    'use strict';
    var root = document.querySelector('[data-ai-management]');
    if (!root) return;
    var status = root.querySelector('[data-status]');
    function node(tag, text) {
        var el = document.createElement(tag);
        if (text) el.textContent = text;
        return el;
    }
    async function request(path, body) {
        var response = await fetch('/api/ai-management' + path, body ? {
            method: 'POST', body: body, headers: {'X-Requested-With': 'XMLHttpRequest'}
        } : {cache: 'no-store'});
        if (!response.ok) throw new Error('操作失败，请检查配置、权限和接口连接。');
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
            details.appendChild(node('summary', row.label + ' · ' + (row.siteMask || (row.yamlConfigured ? '配置文件' : '未配置'))));
            var form = node('form'); form.dataset.providerForm = ''; form.className = 'wiki-form';
            field(form, '', 'id', row.id, 'hidden'); field(form, '', 'type', row.type, 'hidden');
            field(form, '名称', 'label', row.label);
            field(form, '接口地址', 'baseUrl', row.siteBaseUrl || row.defaultBaseUrl);
            field(form, '模型', 'model', row.siteModel || row.defaultModel);
            field(form, '新密钥（留空保留原值）', 'apiKey', '', 'password');
            field(form, '代理', 'proxy', row.siteProxy);
            field(form, '允许本机或内网服务', 'allowPrivate', row.allowPrivate, 'checkbox');
            field(form, '清除该接口的站点凭据', 'clear', false, 'checkbox');
            var button = node('button', '保存'); button.type = 'submit'; form.appendChild(button);
            details.appendChild(form); providers.appendChild(details);
        });
        var scenes = root.querySelector('[data-scenes]'); scenes.replaceChildren();
        data.scenes.forEach(function (row) {
            var form = node('form'); form.dataset.sceneForm = ''; form.className = 'wiki-form';
            form.appendChild(node('h3', row.id === 'activity' ? '活动文案整理' : '新闻起草'));
            field(form, '', 'id', row.id, 'hidden');
            var label = node('label', '站点接口'); var select = node('select'); select.name = 'provider';
            select.appendChild(node('option', '请选择'));
            data.providers.forEach(function (provider) {
                var option = node('option', provider.label); option.value = provider.id; select.appendChild(option);
            }); select.value = row.provider; label.appendChild(select); form.appendChild(label);
            field(form, '允许使用的用户组（用 | 分隔）', 'groups', row.groups);
            field(form, '启用', 'enabled', row.enabled, 'checkbox');
            var save = node('button', '保存场景'); save.type = 'submit'; form.appendChild(save);
            var test = node('button', '测试连接'); test.type = 'button';
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
                + call.duration_ms + 'ms · ' + new Date(call.created_at).toLocaleString()));
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
            status.textContent = '已保存'; await load();
        } catch (error) { status.textContent = error.message; }
        finally { button.disabled = false; }
    });
    load().catch(function (error) { status.textContent = error.message; });
})();
