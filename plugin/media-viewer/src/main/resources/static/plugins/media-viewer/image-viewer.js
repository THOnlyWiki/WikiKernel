/*
 * 图片放大查看（仿 MediaWiki MediaViewer）：
 *  - 点击词条嵌入图片（[[文件:...]]，默认链到文件页时）/ 文件页大图 → 全屏深色查看器
 *  - 双指、按钮、双击和滚轮缩放，放大后可拖动，同页图片可连续浏览
 *  - 键盘：Esc 关闭、← → 翻页、+ - 缩放、0 适应窗口、1 原始尺寸
 *  - 下载菜单：原图，以及用 <canvas> 在浏览器端缩放到指定宽度的版本（服务器不做任何缩放）
 *  - 仅当启用「多媒体预览」（window.WIKI_CONFIG.mediaViewer）时生效；
 *    自定义 link= 的图片保持原链接行为（与 MediaWiki 一致）
 */
(function () {
    'use strict';

    var CONFIG = window.WIKI_CONFIG || {};
    if (!CONFIG.mediaViewer) {
        return;
    }
    var MESSAGES = window.WIKI_MESSAGES || {};

    /** 下载菜单里的预设宽度（只显示比原图小的；过小的图会自动加 50% 选项）。 */
    var SCALED_WIDTHS = [1920, 1280, 800, 480];
    var HASH_PREFIX = '#mediaviewer/';

    var viewer = null;
    var elements = {};
    var items = [];
    var state = { index: 0, zoom: 1, fitFactor: 1, naturalWidth: 0, naturalHeight: 0,
        stageWidth: 0, stageHeight: 0, panX: 0, panY: 0 };
    var pointers = new Map();
    var gestureMoved = false;
    var opener = null;
    var menuTrigger = null;

    /** 取一条界面文案（服务端经 window.WIKI_MESSAGES 注入）；缺失时显示键名本身。 */
    function msg(key) {
        return MESSAGES[key] || key;
    }

    /**
     * 是否「链到文件页 / 原文件」的链接（这些才拦截给预览器）。
     * 注意：裸根部署下文件页链接是 {@code /文件:X.png}（浏览器还会把冒号编码成 %3A），
     * 只有 URL 前缀 {@code /wiki/} 时也要能认出来。
     */
    function fileTitle(path) {
        return decode(path).match(/(?:^|\/)(?:文件|File|Image|图像|图片):(.+)$/i);
    }

    function decode(value) {
        try {
            return decodeURIComponent(value);
        } catch (error) {
            return value;
        }
    }

    function localPath(href) {
        try {
            var url = new URL(href, document.baseURI);
            return url.origin === location.origin ? url.pathname : '';
        } catch (error) {
            return '';
        }
    }

    function filePage(title) {
        var pattern = window.mw && mw.config.get('wgArticlePath') || '/wiki/$1';
        return pattern.replace('$1', title.split('/').map(encodeURIComponent).join('/'));
    }

    /**
     * 正文里可以放大查看的图片：
     *  - 服务端渲染的 [[文件:X|thumb]] 会带 .wiki-media-image；但缓存里的旧 HTML、
     *    插件/模板生成的 <img>、直接写进 wikitext 的 <img> 都没有这个类 ——
     *    所以这里以「正文容器里的图片」为准（排除界面图标：头部/侧栏/页脚/小于 48px 的图）。
     */
    function viewableImages() {
        var selector = '#mw-parser-output img, .wiki-content img, #mw-content-text img, #mw-content img';
        return Array.prototype.slice.call(document.querySelectorAll(selector)).filter(function (img) {
            if (!img.getAttribute('src')) {
                return false;
            }
            if (img.closest('#mw-header-container, #mw-footer-container, #mw-site-navigation, '
                    + '#mw-related-navigation, #wiki-head, .wiki-image-viewer, .mw-editsection, '
                    + '.metadata, .noviewer, [data-no-viewer]')) {
                return false;
            }
            var link = img.closest('a[href]');
            if (link) {
                var path = localPath(link.getAttribute('href'));
                if (!path.startsWith('/files/') && !fileTitle(path)) {
                    return false;
                }
            }
            if (img.classList.contains('wiki-media-image')) {
                return true;
            }
            // 界面小图标（脚注箭头、缩略图角标、语言图标等）不参与查看
            var rect = img.getBoundingClientRect();
            var width = rect.width || img.naturalWidth || 0;
            var height = rect.height || img.naturalHeight || 0;
            if (width > 0 && width < 48 && height > 0 && height < 48) {
                return false;
            }
            return true;
        });
    }

    function buildItems() {
        return viewableImages().map(function (img) {
            var link = img.closest('a[href]');
            var href = link ? link.getAttribute('href') : '';
            var path = href && localPath(href);
            var match = fileTitle(path);
            var title = img.getAttribute('data-file') || (match ? '文件:' + match[1] : '');
            var caption = img.getAttribute('data-caption') || '';
            if (!caption) {
                var figure = img.closest('figure, .thumb, .wiki-media, .mw-halign-none, .center, .gallerybox');
                if (figure) {
                    var captionEl = figure.querySelector('figcaption, .thumbcaption, .wiki-media-caption, .gallerytext');
                    if (captionEl) {
                        caption = captionEl.textContent.trim();
                    }
                }
            }
            if (!caption) {
                caption = img.getAttribute('alt') || '';
            }
            var src = img.getAttribute('src') || img.src;
            var original = img.getAttribute('data-original-src') || '';
            // 旧缓存 HTML 没有 data-original-src：如果图片直接链到 /files/，它就是原图
            if (!original && path.startsWith('/files/')) {
                original = href;
            }
            return {
                element: img,
                src: src,
                original: original || src,
                title: title,
                caption: caption,
                page: match ? href : (title ? filePage(title) : null)
            };
        });
    }

    function ensureViewer() {
        if (viewer) {
            return viewer;
        }
        viewer = document.createElement('div');
        viewer.className = 'wiki-image-viewer';
        viewer.hidden = true;
        viewer.setAttribute('role', 'dialog');
        viewer.setAttribute('aria-modal', 'true');
        viewer.setAttribute('aria-labelledby', 'wiki-image-viewer-title');
        viewer.innerHTML =
            '<div class="wiki-image-viewer-main"><div class="wiki-image-viewer-stage">'
            + '<img alt="" draggable="false" hidden>'
            + '<div class="wiki-image-viewer-status" role="status" hidden></div></div>'
            + '<div class="wiki-image-viewer-top">'
            + '<button type="button" class="wiki-image-viewer-button" data-act="fullscreen"'
            + ' data-label="mediaFullscreen">'
            + icon('M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5') + '</button>'
            + '<button type="button" class="wiki-image-viewer-button wiki-image-viewer-close"'
            + ' data-label="imageViewerClose">' + icon('m5 5 14 14M19 5 5 19') + '</button></div>'
            + '<button type="button" class="wiki-image-viewer-nav wiki-image-viewer-prev"'
            + ' data-label="imageViewerPrev">' + icon('m15 4-8 8 8 8') + '</button>'
            + '<button type="button" class="wiki-image-viewer-nav wiki-image-viewer-next"'
            + ' data-label="imageViewerNext">' + icon('m9 4 8 8-8 8') + '</button></div>'
            + '<div class="wiki-image-viewer-bottom">'
            + '<div class="wiki-image-viewer-info">'
            + '<div id="wiki-image-viewer-title" class="wiki-image-viewer-title"></div>'
            + '<div class="wiki-image-viewer-caption"></div></div>'
            + '<div class="wiki-image-viewer-controls">'
            + '<button type="button" class="wiki-image-viewer-button" data-act="zoom-out"'
            + ' data-label="imageViewerZoomOut">−</button>'
            + '<button type="button" class="wiki-image-viewer-button wiki-image-viewer-zoom" data-act="fit"'
            + ' data-label="imageViewerFit">100%</button>'
            + '<button type="button" class="wiki-image-viewer-button" data-act="zoom-in"'
            + ' data-label="imageViewerZoomIn">+</button>'
            + '<button type="button" class="wiki-image-viewer-button" data-act="share"'
            + ' data-label="imageViewerShare" aria-expanded="false" aria-controls="wiki-image-viewer-menu">'
            + icon('M14 3v5c-6 0-9 4-11 10 4-4 7-5 11-5v5l7-7-7-8z') + '</button>'
            + '<button type="button" class="wiki-image-viewer-button" data-act="download"'
            + ' data-label="imageViewerDownload" aria-expanded="false" aria-controls="wiki-image-viewer-menu">'
            + icon('M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4') + '</button>'
            + '<a class="wiki-image-viewer-details"></a></div>'
            + '<div id="wiki-image-viewer-menu" class="wiki-image-viewer-menu" hidden></div></div>';
        document.body.appendChild(viewer);

        elements = {
            title: viewer.querySelector('.wiki-image-viewer-title'),
            close: viewer.querySelector('.wiki-image-viewer-close'),
            prev: viewer.querySelector('.wiki-image-viewer-prev'),
            next: viewer.querySelector('.wiki-image-viewer-next'),
            stage: viewer.querySelector('.wiki-image-viewer-stage'),
            image: viewer.querySelector('.wiki-image-viewer-stage img'),
            status: viewer.querySelector('.wiki-image-viewer-status'),
            caption: viewer.querySelector('.wiki-image-viewer-caption'),
            zoom: viewer.querySelector('.wiki-image-viewer-zoom'),
            details: viewer.querySelector('.wiki-image-viewer-details'),
            fullscreen: viewer.querySelector('[data-act="fullscreen"]'),
            share: viewer.querySelector('[data-act="share"]'),
            menu: viewer.querySelector('.wiki-image-viewer-menu')
        };
        viewer.querySelectorAll('[data-label]').forEach(function (button) {
            button.setAttribute('aria-label', msg(button.dataset.label));
            button.title = msg(button.dataset.label);
        });
        elements.details.textContent = msg('imageViewerDetails');
        elements.fullscreen.hidden = !document.fullscreenEnabled || !viewer.requestFullscreen;
        bindViewer();
        return viewer;
    }

    function icon(path) {
        return '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor"'
            + ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="' + path + '"/></svg>';
    }

    function bindViewer() {
        elements.close.addEventListener('click', close);
        elements.prev.addEventListener('click', function () {
            navigate(-1);
        });
        elements.next.addEventListener('click', function () {
            navigate(1);
        });
        elements.zoom.addEventListener('click', zoomToFit);
        viewer.querySelector('[data-act="zoom-out"]').addEventListener('click', function () {
            zoomBy(0.8);
        });
        viewer.querySelector('[data-act="zoom-in"]').addEventListener('click', function () {
            zoomBy(1.25);
        });
        viewer.querySelector('[data-act="download"]').addEventListener('click', toggleMenu);
        elements.share.addEventListener('click', toggleMenu);
        elements.fullscreen.addEventListener('click', toggleFullscreen);
        document.addEventListener('fullscreenchange', function () {
            var active = document.fullscreenElement === viewer;
            var label = msg(active ? 'imageViewerExitFullscreen' : 'mediaFullscreen');
            elements.fullscreen.title = label;
            elements.fullscreen.setAttribute('aria-label', label);
            elements.fullscreen.setAttribute('aria-pressed', String(active));
            if (!viewer.hidden) {
                resizeImage();
            }
        });
        elements.image.addEventListener('dblclick', function (event) {
            event.preventDefault();
            if (gestureMoved) {
                return;
            }
            if (Math.abs(state.zoom - 1) < 0.01 && state.fitFactor < 1) {
                zoomToActual();
            } else {
                zoomToFit();
            }
        });
        bindGestures();
        elements.stage.addEventListener('click', function (event) {
            if (event.target === elements.stage && !gestureMoved && elements.menu.hidden) {
                close();
            }
        });
        elements.stage.addEventListener('wheel', function (event) {
            event.preventDefault();
            var point = stagePoint(event.clientX, event.clientY);
            zoomBy(event.deltaY < 0 ? 1.15 : 1 / 1.15, point);
        }, { passive: false });
        document.addEventListener('keydown', function (event) {
            if (viewer.hidden) {
                return;
            }
            if (event.key === 'Escape') {
                event.preventDefault();
                if (!elements.menu.hidden) {
                    var trigger = menuTrigger;
                    hideMenu();
                    trigger.focus();
                } else {
                    close();
                }
                return;
            }
            if (event.target instanceof Element && event.target.closest('input, textarea')) {
                return;
            }
            if (event.key === 'ArrowLeft') {
                event.preventDefault();
                navigate(-1);
            } else if (event.key === 'ArrowRight') {
                event.preventDefault();
                navigate(1);
            } else if (event.key === '+' || event.key === '=') {
                zoomBy(1.25);
            } else if (event.key === '-' || event.key === '_') {
                zoomBy(0.8);
            } else if (event.key === '0') {
                zoomToFit();
            } else if (event.key === '1') {
                zoomToActual();
            }
        });
        document.addEventListener('click', function (event) {
            if (menuTrigger && !elements.menu.contains(event.target) && !menuTrigger.contains(event.target)) {
                hideMenu();
            }
        });
        window.addEventListener('resize', function () {
            if (!viewer.hidden) {
                resizeImage();
            }
        });
    }

    function toggleFullscreen() {
        if (document.fullscreenElement === viewer) {
            document.exitFullscreen().catch(function () {});
        } else {
            viewer.requestFullscreen().catch(function () {});
        }
    }

    function bindGestures() {
        var stage = elements.stage;
        stage.addEventListener('pointerdown', function (event) {
            if (event.button !== 0 || pointers.size >= 2) {
                return;
            }
            gestureMoved = pointers.size > 0;
            pointers.set(event.pointerId, { x: event.clientX, y: event.clientY,
                startX: event.clientX, startY: event.clientY });
            event.target.setPointerCapture(event.pointerId);
        });
        stage.addEventListener('pointermove', function (event) {
            var point = pointers.get(event.pointerId);
            if (!point || !state.naturalWidth) {
                return;
            }
            var x = event.clientX;
            var y = event.clientY;
            if (!gestureMoved && Math.hypot(x - point.startX, y - point.startY) < 4) {
                return;
            }
            gestureMoved = true;
            stage.classList.add('dragging');
            if (pointers.size === 2) {
                var other = Array.from(pointers.values()).find(function (candidate) { return candidate !== point; });
                var before = Math.max(8, Math.hypot(point.x - other.x, point.y - other.y));
                var after = Math.max(8, Math.hypot(x - other.x, y - other.y));
                var from = stagePoint((point.x + other.x) / 2, (point.y + other.y) / 2);
                var to = stagePoint((x + other.x) / 2, (y + other.y) / 2);
                zoomBy(after / before, from, to);
            } else {
                state.panX += x - point.x;
                state.panY += y - point.y;
                renderImage();
            }
            point.x = x;
            point.y = y;
        });
        function endPointer(event) {
            if (pointers.delete(event.pointerId) && !pointers.size) {
                stage.classList.remove('dragging');
            }
        }
        stage.addEventListener('pointerup', endPointer);
        stage.addEventListener('pointercancel', endPointer);
        stage.addEventListener('lostpointercapture', endPointer);
    }

    // ==================== 打开 / 关闭 / 展示 ====================

    function open(collection, index, fromHash) {
        ensureViewer();
        items = collection;
        state.index = index;
        if (viewer.hidden) {
            opener = fromHash ? null : items[index].element.closest('a[href]');
        }
        viewer.hidden = false;
        document.documentElement.classList.add('wiki-image-viewer-open');
        elements.close.focus();
        if (!fromHash && items[index].title) {
            var entry = Object.assign({}, history.state, { wikiImageViewer: true });
            history.pushState(entry, '', itemHash(items[index]));
        }
        showItem();
    }

    function close() {
        dismiss();
        if (history.state && history.state.wikiImageViewer) {
            history.back();
        } else if (location.hash.startsWith(HASH_PREFIX)) {
            history.replaceState(history.state, '', location.pathname + location.search);
        }
    }

    function dismiss() {
        viewer.hidden = true;
        if (document.fullscreenElement === viewer) {
            document.exitFullscreen().catch(function () {});
        }
        document.documentElement.classList.remove('wiki-image-viewer-open');
        elements.image.onload = null;
        elements.image.onerror = null;
        elements.image.removeAttribute('src');
        pointers.clear();
        elements.stage.classList.remove('dragging');
        hideMenu();
        if (opener && opener.isConnected) {
            opener.focus({ preventScroll: true });
        }
        opener = null;
    }

    function navigate(step) {
        if (items.length < 2) {
            return;
        }
        state.index = (state.index + step + items.length) % items.length;
        if (location.hash.startsWith(HASH_PREFIX) || (history.state && history.state.wikiImageViewer)) {
            var item = items[state.index];
            history.replaceState(history.state, '', item.title ? itemHash(item) : location.pathname + location.search);
        }
        showItem();
    }

    function showItem() {
        var item = items[state.index];
        if (!item) {
            return;
        }
        state.zoom = 1;
        state.fitFactor = 1;
        state.naturalWidth = 0;
        state.naturalHeight = 0;
        state.panX = 0;
        state.panY = 0;
        pointers.clear();
        gestureMoved = false;
        elements.stage.classList.remove('dragging');

        elements.title.textContent = nameOf(item);
        elements.details.hidden = !item.page;
        if (item.page) {
            elements.details.href = item.page;
        } else {
            elements.details.removeAttribute('href');
        }
        elements.share.hidden = !item.title;
        elements.caption.textContent = item.caption;
        elements.caption.hidden = !item.caption;
        elements.prev.hidden = items.length < 2;
        elements.next.hidden = items.length < 2;
        elements.zoom.textContent = '100%';
        elements.image.hidden = true;
        elements.image.alt = item.caption || nameOf(item);
        hideMenu();
        showStatus(msg('imageViewerLoading'));

        elements.image.onload = function () {
            hideStatus();
            state.naturalWidth = elements.image.naturalWidth;
            state.naturalHeight = elements.image.naturalHeight;
            resizeImage();
            elements.image.hidden = false;
        };
        elements.image.onerror = function () {
            showStatus(msg('imageViewerFailed'));
        };
        elements.image.src = item.original || item.src;
    }

    // ==================== 缩放 ====================

    function resizeImage() {
        if (!state.naturalWidth) {
            return;
        }
        var style = window.getComputedStyle(elements.stage);
        state.stageWidth = elements.stage.clientWidth;
        state.stageHeight = elements.stage.clientHeight;
        var maxWidth = Math.max(1, state.stageWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight));
        var maxHeight = Math.max(1, state.stageHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom));
        state.fitFactor = Math.min(1, maxWidth / state.naturalWidth, maxHeight / state.naturalHeight);
        elements.image.style.width = state.naturalWidth * state.fitFactor + 'px';
        renderImage();
    }

    function renderImage() {
        if (!state.naturalWidth) {
            return;
        }
        var scale = state.fitFactor * state.zoom;
        var maxX = Math.max(0, (state.naturalWidth * scale - state.stageWidth) / 2);
        var maxY = Math.max(0, (state.naturalHeight * scale - state.stageHeight) / 2);
        state.panX = Math.min(maxX, Math.max(-maxX, state.panX));
        state.panY = Math.min(maxY, Math.max(-maxY, state.panY));
        elements.image.style.transform = 'translate(-50%, -50%) translate(' + state.panX + 'px, '
            + state.panY + 'px) scale(' + state.zoom + ')';
        elements.zoom.textContent = Math.round(scale * 100) + '%';
    }

    function stagePoint(x, y) {
        var rect = elements.stage.getBoundingClientRect();
        return { x: x - rect.left - rect.width / 2, y: y - rect.top - rect.height / 2 };
    }

    function zoomBy(factor, from, to) {
        if (!state.naturalWidth) {
            return;
        }
        from = from || { x: 0, y: 0 };
        to = to || from;
        var next = Math.min(Math.max(16, 1 / state.fitFactor), Math.max(0.25, state.zoom * factor));
        var ratio = next / state.zoom;
        // 手指中心或鼠标指向的图像位置在缩放时仍留在原处，双指一起移动则跟随手指。
        state.panX = to.x - (from.x - state.panX) * ratio;
        state.panY = to.y - (from.y - state.panY) * ratio;
        state.zoom = next;
        renderImage();
    }

    function zoomToFit() {
        state.zoom = 1;
        state.panX = 0;
        state.panY = 0;
        renderImage();
    }

    function zoomToActual() {
        zoomBy(1 / (state.fitFactor * state.zoom));
    }

    function showStatus(text) {
        elements.status.textContent = text;
        elements.status.hidden = false;
    }

    function hideStatus() {
        elements.status.hidden = true;
    }

    // ==================== 下载（原图 / 浏览器端缩放） ====================

    function toggleMenu(event) {
        var trigger = event.currentTarget;
        var alreadyOpen = menuTrigger === trigger;
        hideMenu();
        if (alreadyOpen) {
            return;
        }
        menuTrigger = trigger;
        trigger.setAttribute('aria-expanded', 'true');
        elements.menu.hidden = false;
        if (trigger === elements.share) {
            buildShareMenu(items[state.index]);
        } else {
            buildMenu(items[state.index]);
        }
    }

    function hideMenu() {
        elements.menu.hidden = true;
        elements.menu.replaceChildren();
        if (menuTrigger) {
            menuTrigger.setAttribute('aria-expanded', 'false');
            menuTrigger = null;
        }
    }

    function itemHash(item) {
        return HASH_PREFIX + 'File:' + encodeURIComponent(nameOf(item).replace(/ /g, '_'));
    }

    function buildShareMenu(item) {
        var label = document.createElement('label');
        label.textContent = msg('imageViewerShare');
        var input = document.createElement('input');
        input.type = 'url';
        input.readOnly = true;
        input.value = new URL(itemHash(item), location.href).href;
        input.addEventListener('focus', function () { input.select(); });
        label.appendChild(input);
        elements.menu.appendChild(label);
        if (navigator.clipboard && navigator.clipboard.writeText) {
            var copy = document.createElement('button');
            copy.type = 'button';
            copy.className = 'wiki-image-viewer-menu-item';
            copy.textContent = msg('imageViewerCopy');
            copy.addEventListener('click', function () {
                navigator.clipboard.writeText(input.value).then(function () {
                    copy.textContent = msg('imageViewerCopied');
                }).catch(function () { input.focus(); });
            });
            elements.menu.appendChild(copy);
        }
        input.focus({ preventScroll: true });
    }

    function scaledWidths() {
        var widths = [];
        var natural = state.naturalWidth || 0;
        SCALED_WIDTHS.forEach(function (width) {
            if (natural && width < natural && widths.indexOf(width) < 0) {
                widths.push(width);
            }
        });
        // 小图也提供「50%」：否则只能下原图，没有"缩放尺寸"可选
        var half = natural ? Math.round(natural / 2) : 0;
        if (half >= 100 && widths.indexOf(half) < 0 && half < natural) {
            widths.push(half);
        }
        return widths.sort(function (a, b) {
            return b - a;
        });
    }

    function buildMenu(item) {
        var original = document.createElement('a');
        original.className = 'wiki-image-viewer-menu-item';
        original.href = item.original || item.src;
        original.setAttribute('download', fileNameOf(item));
        original.textContent = msg('imageViewerOriginal')
            + (state.naturalWidth ? '（' + state.naturalWidth + '×' + state.naturalHeight + '）' : '');
        elements.menu.appendChild(original);

        var viewOriginal = document.createElement('a');
        viewOriginal.className = original.className;
        viewOriginal.href = original.href;
        viewOriginal.target = '_blank';
        viewOriginal.rel = 'noopener';
        viewOriginal.textContent = msg('imageViewerViewOriginal');
        elements.menu.appendChild(viewOriginal);

        scaledWidths().forEach(function (width) {
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'wiki-image-viewer-menu-item';
            button.textContent = formatScaled(width);
            button.addEventListener('click', function () {
                downloadScaled(item, width, button);
            });
            elements.menu.appendChild(button);
        });

        var status = document.createElement('div');
        status.className = 'wiki-image-viewer-menu-status';
        status.hidden = true;
        elements.menu.appendChild(status);
    }

    function formatScaled(width) {
        return msg('imageViewerScaled').replace('{0}', width);
    }

    function downloadScaled(item, width, button) {
        button.disabled = true;
        button.textContent = msg('imageViewerWorking');
        fetch(item.original || item.src, { credentials: 'same-origin' })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('http ' + response.status);
                }
                return response.blob();
            })
            .then(function (blob) {
                return scaleImage(blob, width);
            })
            .then(function (result) {
                saveBlob(result.blob, scaledName(item, width, result.type));
                if (elements.menu.contains(button)) {
                    var trigger = menuTrigger;
                    hideMenu();
                    trigger.focus();
                }
            })
            .catch(function () {
                menuStatus(button, width, msg('imageViewerFailed'));
            });
    }

    async function scaleImage(blob, width) {
        var url = URL.createObjectURL(blob);
        var image = new Image();
        var canvas = document.createElement('canvas');
        try {
            image.src = url;
            await image.decode();
            canvas.width = width;
            canvas.height = Math.max(1, Math.round(image.naturalHeight * width / image.naturalWidth));
            canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
        } finally {
            URL.revokeObjectURL(url);
        }
        var type = /jpe?g/i.test(blob.type) ? 'image/jpeg' : 'image/png';
        var scaled = await new Promise(function (resolve) {
            canvas.toBlob(resolve, type, type === 'image/jpeg' ? 0.92 : undefined);
        });
        if (!scaled) {
            throw new Error('encode failed');
        }
        return { blob: scaled, type: type };
    }

    function menuStatus(button, width, text) {
        if (!elements.menu.contains(button)) {
            return;
        }
        button.disabled = false;
        button.textContent = formatScaled(width);
        var status = elements.menu.querySelector('.wiki-image-viewer-menu-status');
        if (status) {
            status.textContent = text;
            status.hidden = false;
        }
    }

    function nameOf(item) {
        var title = (item.title || '').replace(/^(文件|File|Image|图像|图片):/i, '').trim();
        if (title) {
            return title;
        }
        var name = (item.src || '').split('?')[0].split('/').pop() || 'image';
        try {
            return decodeURIComponent(name);
        } catch (error) {
            return name;
        }
    }

    function fileNameOf(item) {
        return nameOf(item);
    }

    function baseNameOf(item) {
        return nameOf(item).replace(/\.[^.]+$/, '');
    }

    function scaledName(item, width, type) {
        return baseNameOf(item) + '-' + width + 'px.' + (type === 'image/jpeg' ? 'jpg' : 'png');
    }

    function saveBlob(blob, name) {
        var url = URL.createObjectURL(blob);
        var link = document.createElement('a');
        link.href = url;
        link.download = name;
        document.body.appendChild(link);
        link.click();
        link.remove();
        window.setTimeout(function () {
            URL.revokeObjectURL(url);
        }, 10000);
    }

    // ==================== 点击图片打开（事件委托） ====================

    document.addEventListener('click', function (event) {
        if ((viewer && !viewer.hidden) || event.defaultPrevented || event.button !== 0
                || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
            return;
        }
        var target = event.target;
        var img = target.closest('img');
        // 键盘激活缩略图链接时，click 的目标是 <a>。
        if (!img && event.detail === 0 && target.matches('a[href]')) {
            img = target.querySelector('img');
        }
        if (!img) {
            return;
        }
        var collection = buildItems();
        var index = collection.findIndex(function (item) { return item.element === img; });
        if (index < 0) {
            return;
        }
        event.preventDefault();
        event.stopPropagation();
        open(collection, index, false);
    }, true);

    function syncHash() {
        if (location.hash.startsWith(HASH_PREFIX)) {
            var match = fileTitle(location.hash.slice(HASH_PREFIX.length));
            if (match) {
                var name = match[1].replace(/_/g, ' ');
                if (viewer && !viewer.hidden && nameOf(items[state.index]).replace(/_/g, ' ') === name) {
                    return;
                }
                var collection = buildItems();
                var index = collection.findIndex(function (item) {
                    return item.title && nameOf(item).replace(/_/g, ' ') === name;
                });
                if (index >= 0) {
                    open(collection, index, true);
                    return;
                }
            }
        }
        if (viewer && !viewer.hidden) {
            dismiss();
        }
    }

    window.addEventListener('hashchange', syncHash);
    window.addEventListener('popstate', syncHash);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', syncHash);
    } else {
        syncHash();
    }
})();

