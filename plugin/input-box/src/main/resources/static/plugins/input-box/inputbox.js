/*
 * <inputbox>（InputBox 扩展）前端：带 prefix / preload / editintro 的创建表单组装目标页面。
 * 无脚本时表单退化为「特殊:创建页面?pagename=输入值」（不含前缀）。
 * 目标标题按 MediaWiki 规则拼接，斜杠分段编码（%2F 会被 Tomcat 拒绝，必须保留 /）。
 */
(function () {
    'use strict';

    document.addEventListener('submit', function (event) {
        var form = event.target && event.target.closest ? event.target.closest('form.inputbox') : null;
        if (!form) {
            return;
        }
        var hasOptions = form.dataset.prefix || form.dataset.preload || form.dataset.editintro;
        if (!hasOptions) {
            return; // 普通创建 / 搜索表单：让浏览器直接提交
        }
        var input = form.querySelector('input[name="pagename"]');
        if (!input) {
            return;
        }
        var title = (form.dataset.prefix || '') + input.value.trim();
        if (!title) {
            input.focus();
            event.preventDefault();
            return;
        }
        event.preventDefault();
        // 每一段单独编码，保留路径分隔符 /（编码成 %2F 会被服务端 400 拒绝）
        var path = title.split('/').map(encodeURIComponent).join('/');
        var url = new URL('/wiki/' + path, window.location.origin);
        url.searchParams.set('action', 'edit');
        if (form.dataset.preload) {
            url.searchParams.set('preload', form.dataset.preload);
        }
        if (form.dataset.editintro) {
            url.searchParams.set('editintro', form.dataset.editintro);
        }
        window.location.href = url.toString();
    });
})();
