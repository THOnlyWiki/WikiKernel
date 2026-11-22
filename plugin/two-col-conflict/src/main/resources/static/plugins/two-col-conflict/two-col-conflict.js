(function () {
    'use strict';
    const root = document.querySelector('[data-conflict-resolution]');
    const form = root && root.closest('form');
    const output = form && form.querySelector('textarea[name="content"]');
    if (!output) return;

    const blocks = [...root.querySelectorAll('[data-conflict-block]')].map(block =>
        [...block.querySelectorAll('[data-conflict-option]')].map(option => ({
            choice: option.querySelector('input[type="radio"]'),
            preview: option.querySelector('[data-conflict-preview]'),
            source: option.querySelector('[data-conflict-source]').value.slice(1),
            text: option.querySelector('[data-conflict-text]'),
            edit: option.querySelector('[data-conflict-edit]'),
            apply: option.querySelector('[data-conflict-apply]'),
            reset: option.querySelector('[data-conflict-reset]')
        })));
    const draft = root.querySelector('[data-conflict-draft]');
    draft.value = draft.value.slice(1);

    function sync() {
        output.value = blocks.map(options =>
            options.find(option => !option.choice || option.choice.checked).text.value).join('');
    }

    function toggle(option, editing) {
        option.preview.hidden = editing;
        option.edit.hidden = editing;
        option.text.hidden = !editing;
        option.apply.hidden = !editing;
        option.reset.hidden = !editing;
    }

    for (const options of blocks) {
        for (const option of options) {
            option.applied = option.source;
            option.text.value = option.applied;
            option.choice?.addEventListener('change', function () {
                for (const sibling of options) {
                    sibling.edit.disabled = !sibling.choice.checked;
                    if (sibling.edit.disabled) {
                        sibling.text.value = sibling.applied;
                        toggle(sibling, false);
                    }
                }
                sync();
            });
            option.edit.addEventListener('click', function () {
                toggle(option, true);
                option.text.focus();
            });
            option.text.addEventListener('input', sync);
            option.apply.addEventListener('click', function () {
                option.applied = option.text.value;
                option.preview.textContent = option.applied;
                toggle(option, false);
                option.edit.focus();
                sync();
            });
            option.reset.addEventListener('click', function () {
                option.text.value = option.applied;
                toggle(option, false);
                option.edit.focus();
                sync();
            });
        }
    }
    form.addEventListener('submit', sync, true);
    form.querySelector('#wiki-preview-button')?.addEventListener('click', sync, true);
    output.hidden = true;
    output.removeAttribute('autofocus');
    document.querySelectorAll('.edit-conflict-diff, .edit-conflict-hint').forEach(e => e.hidden = true);
    root.hidden = false;
    sync();
})();
