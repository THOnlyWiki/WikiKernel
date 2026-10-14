/* 可打印版（?printable=yes，仿 MediaWiki）：自动打开浏览器打印窗口（可直接另存为 PDF），
   并在页面顶部提供「打印 / 另存为 PDF」与「返回阅读页」按钮（打印时隐藏）。 */
(function () {
    'use strict';

    function labels() {
        var messages = window.WIKI_MESSAGES || {};
        return {
            print: messages.printablePrint || 'printable.print',
            back: messages.printableBack || 'printable.back'
        };
    }

    function backUrl() {
        try {
            var url = new URL(window.location.href);
            url.searchParams.delete('printable');
            return url.toString();
        } catch (error) {
            return window.location.pathname;
        }
    }

    function buildBar() {
        var text = labels();
        var bar = document.createElement('div');
        bar.className = 'wk-printable-bar';

        var printButton = document.createElement('button');
        printButton.type = 'button';
        printButton.className = 'wiki-button';
        printButton.textContent = text.print;
        printButton.addEventListener('click', function () {
            window.print();
        });

        var backLink = document.createElement('a');
        backLink.className = 'wk-printable-back';
        backLink.href = backUrl();
        backLink.textContent = text.back;

        bar.appendChild(printButton);
        bar.appendChild(backLink);
        return bar;
    }

    var printQueued = false;

    function requestAutoPrint() {
        if (printQueued) {
            return;
        }
        printQueued = true;
        // 页面已加载完成，再留一点时间让布局稳定，然后打开打印窗口（可另存为 PDF）
        window.setTimeout(function () {
            document.body.setAttribute('data-print-triggered', '1');
            window.print();
        }, 250);
    }

    /**
     * 自动打印：Chrome 对隐藏页面（后台标签页 / 窗口被遮挡）触发打印时会产生
     * 「没有可用的预览」，所以页面不可见时先不打印，等切到可见后再自动弹出；
     * 顶部的「打印 / 另存为 PDF」按钮任何时候都能手动打印。
     */
    function scheduleAutoPrint() {
        if (document.visibilityState === 'visible') {
            requestAutoPrint();
            return;
        }
        document.body.setAttribute('data-print-deferred', '1');
        document.addEventListener('visibilitychange', function onVisible() {
            if (document.visibilityState !== 'visible') {
                return;
            }
            document.removeEventListener('visibilitychange', onVisible);
            requestAutoPrint();
        });
    }

    function start() {
        var body = document.body;
        if (!body || !body.classList.contains('wiki-printable')) {
            return;
        }
        if (!document.querySelector('.wk-printable-bar')) {
            body.insertBefore(buildBar(), body.firstChild);
        }
        // 等图片 / 字体等资源就绪后再安排自动打印
        window.setTimeout(scheduleAutoPrint, 350);
    }

    if (document.readyState === 'complete') {
        start();
    } else {
        window.addEventListener('load', start);
    }
})();
