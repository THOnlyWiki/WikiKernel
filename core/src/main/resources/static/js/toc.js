/*
 * 目录折叠（仿 MediaWiki）：点击目录标题旁的 [隐藏] / [显示] 切换。
 */
(function () {
    'use strict';

    Array.prototype.forEach.call(document.querySelectorAll('.toc .toctoggle'), function (toggle) {
        toggle.addEventListener('click', function () {
            var toc = toggle.closest ? toggle.closest('.toc') : null;
            if (toc) {
                toc.classList.toggle('toc-hidden');
            }
        });
    });
})();
