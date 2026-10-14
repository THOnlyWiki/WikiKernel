/**
 * 页面横幅轮播（插件 page-banner）。
 *
 * 由 PageBannerHook 注入：本页**可能**显示横幅时注入 page-banner.css，本页确有**多张**图片时再注入本脚本
 * （<script defer src="/plugins/page-banner/page-banner.js">）。
 *
 * 结构（由 PageBannerRenderer 生成）：
 *   .page-banner[data-page-banner-carousel]
 *     .page-banner-viewport > .page-banner-track > .page-banner-slide[data-slide-index] > img
 *     .page-banner-progress > .page-banner-progress-bar
 *     .page-banner-controls > .page-banner-prev / .page-banner-dots > .page-banner-dot / .page-banner-next
 *
 * 约定：
 * - 文案**不写死**在脚本里：优先读容器上的 data-* （服务端按当前语言注入，见 PageBannerHook），
 *   其次读 window.WIKI_MESSAGES（core 会合并插件 MessageBundle 的键），最后原样显示翻译键名；
 * - 没有 JS 时所有图片都是可见的（每张都是普通文档流元素），浏览器显示第一张；只有本脚本启动后
 *   才给容器加 .page-banner-carousel--ready，CSS 随之只显示当前张；
 * - 只有一张图片：不显示控件、不自动翻页（服务端已经退化成静态横幅，这里再兜一次底）；
 * - prefers-reduced-motion: reduce：不加过渡动画，也不再自动翻页。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var DEFAULT_INTERVAL = 5;
    var MAX_INTERVAL = 3600;

    /** 取一条界面文案：没有本地化时显示翻译键本身（与 core 的「漏翻显示键名」策略一致）。 */
    function text(key) {
        if (key == null) {
            return '';
        }
        var value = MESSAGES[key];
        return value == null ? key : value;
    }

    /** 容器上的 data-* 文案；缺失时回落到 window.WIKI_MESSAGES[messageKey]。 */
    function label(root, attribute, messageKey) {
        var value = root.getAttribute(attribute);
        return value == null || value === '' ? text(messageKey) : value;
    }

    /** {0} → 序号（与 core 的 MessageFormat 占位写法一致）。 */
    function format(template, value) {
        return String(template).replace('{0}', value);
    }

    /** 整数解析：非法 / 空 → fallback。 */
    function toInt(value, fallback) {
        var parsed = parseInt(value, 10);
        return isNaN(parsed) ? fallback : parsed;
    }

    function prefersReducedMotion() {
        return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    }

    function init(root) {
        var slides = root.querySelectorAll('.page-banner-slide');
        if (slides.length < 2) {
            return;
        }
        var dots = root.querySelectorAll('.page-banner-dot');
        var controls = root.querySelector('.page-banner-controls');
        var progress = root.querySelector('.page-banner-progress-bar');
        var declared = toInt(root.getAttribute('data-slide-count'), slides.length);
        var total = Math.min(slides.length, declared > 0 ? declared : slides.length);
        var transition = (root.getAttribute('data-transition') || 'fade').toLowerCase();
        var interval = toInt(root.getAttribute('data-interval'), DEFAULT_INTERVAL);
        var showControls = root.getAttribute('data-controls') !== 'false';
        var pauseOnHover = root.getAttribute('data-pause-on-hover') !== 'false';
        var statusTemplate = label(root, 'data-status-label', 'pageBannerStatus');
        var reduced = prefersReducedMotion();
        var current = 0;
        var timer = null;
        var paused = false;

        if (interval < 0) {
            interval = 0;
        }
        if (interval > MAX_INTERVAL) {
            interval = MAX_INTERVAL;
        }

        root.classList.add('page-banner-carousel--ready');
        root.setAttribute('data-transition', transition === 'slide' ? 'slide' : 'fade');
        root.setAttribute('data-slide-active', '0');
        if (!showControls && controls) {
            controls.hidden = true;
        }

        // 无障碍：控件按钮的 aria-label / title（文案来自服务端注入的 data-*）
        var prevButton = root.querySelector('.page-banner-prev');
        var nextButton = root.querySelector('.page-banner-next');
        if (prevButton) {
            prevButton.setAttribute('aria-label', label(root, 'data-prev-label', 'pageBannerPrev'));
            prevButton.setAttribute('title', prevButton.getAttribute('aria-label'));
        }
        if (nextButton) {
            nextButton.setAttribute('aria-label', label(root, 'data-next-label', 'pageBannerNext'));
            nextButton.setAttribute('title', nextButton.getAttribute('aria-label'));
        }
        for (var i = 0; i < dots.length; i++) {
            var index = toInt(dots[i].getAttribute('data-slide-to'), i);
            dots[i].setAttribute('aria-label',
                    format(label(root, 'data-goto-label', 'pageBannerGoto'), index + 1));
        }
        if (!root.hasAttribute('tabindex')) {
            root.setAttribute('tabindex', '0');
        }

        /** 当前是第几张（容器的 aria-label 里同步播报「第 N / 共 M 张」）。 */
        function announce() {
            root.setAttribute('aria-label', format(statusTemplate, current + 1) + ' / ' + total);
        }

        /** 自动翻页进度条：从左到右走一遍（与下一张的切换同步；暂停时停在中间）。 */
        function restartProgress() {
            if (!progress) {
                return;
            }
            if (interval <= 0 || paused || reduced) {
                progress.style.transition = 'none';
                progress.style.width = '0%';
                return;
            }
            progress.style.transition = 'none';
            progress.style.width = '0%';
            void progress.offsetWidth;
            progress.style.transition = 'width ' + interval + 's linear';
            progress.style.width = '100%';
        }

        function clearTimer() {
            if (timer !== null) {
                clearInterval(timer);
                timer = null;
            }
        }

        /** 重新开始计时（手动 / 自动翻页后都从头计时，工具条也跟着重走）。 */
        function restartTimer() {
            clearTimer();
            if (interval <= 0 || paused || reduced) {
                restartProgress();
                return;
            }
            timer = setInterval(next, interval * 1000);
            restartProgress();
        }

        function show(index, focusDot) {
            current = ((index % total) + total) % total;
            for (var i = 0; i < slides.length; i++) {
                var active = i === current;
                slides[i].classList.toggle('page-banner-slide--active', active);
                slides[i].setAttribute('aria-hidden', active ? 'false' : 'true');
            }
            for (var d = 0; d < dots.length; d++) {
                var dotActive = toInt(dots[d].getAttribute('data-slide-to'), d) === current;
                dots[d].classList.toggle('page-banner-dot--active', dotActive);
                dots[d].setAttribute('aria-current', dotActive ? 'true' : 'false');
            }
            root.setAttribute('data-slide-active', String(current));
            announce();
            if (focusDot && dots[current]) {
                dots[current].focus();
            }
            preload(current + 1);
            restartTimer();
        }

        /** 预加载下一张（当前张已经在 DOM 里，浏览器按 lazy 策略自行决定时机）。 */
        function preload(index) {
            var slide = slides[((index % total) + total) % total];
            var image = slide ? slide.querySelector('img') : null;
            var source = image ? (image.currentSrc || image.getAttribute('src')) : null;
            if (!source || image.complete) {
                return;
            }
            var preloader = new Image();
            preloader.src = source;
        }

        function next() {
            show(current + 1, false);
        }

        function stop() {
            clearTimer();
            if (progress) {
                progress.style.transition = 'none';
                progress.style.width = '0%';
            }
        }

        function start() {
            restartTimer();
        }

        function pause() {
            if (paused) {
                return;
            }
            paused = true;
            stop();
        }

        function resume() {
            if (!paused) {
                return;
            }
            paused = false;
            start();
        }

        if (prevButton) {
            prevButton.addEventListener('click', function () {
                show(current - 1, false);
            });
        }
        if (nextButton) {
            nextButton.addEventListener('click', function () {
                next();
            });
        }
        for (var j = 0; j < dots.length; j++) {
            dots[j].addEventListener('click', function (event) {
                show(toInt(event.currentTarget.getAttribute('data-slide-to'), current), false);
            });
        }

        // 鼠标悬停暂停（可选）；获得焦点时也暂停（键盘用户不会被自动翻页打断）
        if (pauseOnHover) {
            root.addEventListener('mouseenter', pause);
            root.addEventListener('mouseleave', function () {
                if (!root.contains(document.activeElement)) {
                    resume();
                }
            });
        }
        root.addEventListener('focusin', pause);
        root.addEventListener('focusout', function () {
            if (!root.contains(document.activeElement)) {
                resume();
            }
        });

        // 键盘左右方向键（容器或其中的控件获得焦点时）
        root.addEventListener('keydown', function (event) {
            if (event.key === 'ArrowLeft') {
                show(current - 1, false);
            } else if (event.key === 'ArrowRight') {
                next();
            } else if (event.key === 'Home') {
                show(0, false);
            } else if (event.key === 'End') {
                show(total - 1, false);
            } else {
                return;
            }
            event.preventDefault();
        });

        // 标签页在后台时不白跑定时器
        document.addEventListener('visibilitychange', function () {
            if (document.hidden) {
                pause();
            } else {
                resume();
            }
        });

        show(0, false);
        start();
    }

    function boot() {
        var roots = document.querySelectorAll('[data-page-banner-carousel]');
        for (var i = 0; i < roots.length; i++) {
            try {
                init(roots[i]);
            } catch (exception) {
                // 单条横幅出错不影响页面其它部分（此时 CSS 尚未隐藏非当前张，图片仍可见）
                if (window.console && window.console.warn) {
                    window.console.warn('page-banner 轮播初始化失败', exception);
                }
            }
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }
})();
