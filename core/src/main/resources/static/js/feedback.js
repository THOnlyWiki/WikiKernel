/*
 * 全局表单提示自动淡出：
 *  - 所有 .form-message 提示在显示后自动淡出：成功（.success）3 秒、其它（错误等）8 秒
 *  - 淡出过渡 0.65s，结束后置 hidden；重新显示（改文字 / 去掉 hidden）会重新计时
 *  - 用 MutationObserver 监听整个文档，所有脚本 / 服务端渲染的提示统一生效，无需逐个改脚本
 */
(function () {
    'use strict';
    if (!window.MutationObserver || !document.querySelectorAll) {
        return;
    }

    var SUCCESS_DELAY = 3000;
    var DEFAULT_DELAY = 8000;
    var FADE_MS = 650;

    function watch(box) {
        if (box.__wikiFeedback) {
            return;
        }
        box.__wikiFeedback = true;
        var visibleTimer = null;
        var fadeTimer = null;

        function cancel() {
            window.clearTimeout(visibleTimer);
            window.clearTimeout(fadeTimer);
        }

        function schedule() {
            cancel();
            if (box.hidden || !box.textContent || !box.textContent.trim()) {
                return;
            }
            box.classList.remove('wiki-fading');
            var delay = box.classList.contains('success') ? SUCCESS_DELAY : DEFAULT_DELAY;
            visibleTimer = window.setTimeout(function () {
                box.classList.add('wiki-fading');
                fadeTimer = window.setTimeout(function () {
                    box.hidden = true;
                    box.classList.remove('wiki-fading');
                }, FADE_MS);
            }, delay);
        }

        // 只观察 hidden 与内容：class 变化（添加淡出类）不能触发重启计时
        new MutationObserver(schedule).observe(box, {
            attributes: true,
            attributeFilter: ['hidden'],
            childList: true,
            characterData: true,
            subtree: true
        });
        schedule();
    }

    function scan(root) {
        if (!root) {
            return;
        }
        if (root.nodeType === 1 && root.classList && root.classList.contains('form-message')) {
            watch(root);
        }
        if (root.querySelectorAll) {
            Array.prototype.forEach.call(root.querySelectorAll('.form-message'), watch);
        }
    }

    new MutationObserver(function (mutations) {
        mutations.forEach(function (mutation) {
            Array.prototype.forEach.call(mutation.addedNodes, function (node) {
                if (node.nodeType === 1) {
                    scan(node);
                }
            });
        });
    }).observe(document.documentElement, { childList: true, subtree: true });

    scan(document);
})();
