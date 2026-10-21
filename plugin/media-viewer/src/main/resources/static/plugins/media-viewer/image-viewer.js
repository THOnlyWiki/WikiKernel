/*
 * 图片放大查看（仿 MediaWiki MediaViewer）：
 *  - 点击词条嵌入图片（[[文件:...]]，默认链到文件页时）/ 文件页大图 → 全屏深色查看器
 *  - 缩放（按钮 / 双击 / 滚轮）、适应窗口、1:1、同一页面内上一张 / 下一张
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

    var viewer = null;
    var elements = {};
    var items = [];
    var state = { index: 0, zoom: 1, fitFactor: 1, naturalWidth: 0, naturalHeight: 0 };

    /** 取一条界面文案（服务端经 window.WIKI_MESSAGES 注入）；缺失时显示键名本身。 */
    function msg(key) {
        return MESSAGES[key] || key;
    }

    /**
     * 是否「链到文件页 / 原文件」的链接（这些才拦截给预览器）。
     * 注意：裸根部署下文件页链接是 {@code /文件:X.png}（浏览器还会把冒号编码成 %3A），
     * 只有 URL 前缀 {@code /wiki/} 时也要能认出来。
     */
    function isFileLink(href) {
        if (!href) {
            return false;
        }
        if (href.indexOf('/files/') === 0) {
            return true;
        }
        var decoded = href;
        try {
            decoded = decodeURIComponent(href);
        } catch (error) {
            decoded = href;
        }
        return /(^|\/)(?:wiki\/)?(?:文件|File|Image|图像|图片):/i.test(decoded);
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
            if (img.closest && (img.closest('#mw-header-container') || img.closest('#mw-footer-container')
                    || img.closest('#mw-site-navigation') || img.closest('#mw-related-navigation')
                    || img.closest('#wiki-head') || img.closest('.wiki-image-viewer')
                    || img.closest('.mw-editsection') || img.closest('a[data-no-viewer]'))) {
                return false;
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
            return !img.closest('.mw-editsection');
        });
    }

    function buildItems() {
        var raw = viewableImages();
        return raw.map(function (img) {
            var link = img.closest ? img.closest('a') : null;
            var href = link ? link.getAttribute('href') || '' : '';
            var title = img.getAttribute('data-file') || '';
            if (!title && link) {
                // 从「文件:X」链接 / 链接 title 推断文件名
                var decoded = '';
                try {
                    decoded = decodeURIComponent(href);
                } catch (error) {
                    decoded = href;
                }
                var match = decoded.match(/\/(?:wiki\/)?(?:文件|File):([^?#]+)$/i);
                if (match) {
                    title = '文件:' + match[1];
                } else if (link.getAttribute('title')) {
                    title = link.getAttribute('title');
                }
            }
            var caption = img.getAttribute('data-caption') || '';
            if (!caption) {
                var figure = img.closest ? img.closest('figure, .thumb, .wiki-media, .mw-halign-none, .center') : null;
                if (figure) {
                    var captionEl = figure.querySelector('figcaption, .thumbcaption, .wiki-media-caption');
                    if (captionEl) {
                        caption = captionEl.textContent.trim();
                    }
                }
            }
            if (!caption) {
                caption = img.getAttribute('alt') || '';
            }
            var page = null;
            if (isFileLink(href)) {
                page = href;
            } else if (title) {
                page = '/wiki/' + encodeURIComponent(title);
            }
            var src = img.getAttribute('src') || img.src;
            var original = img.getAttribute('data-original-src') || '';
            // 旧缓存 HTML 没有 data-original-src：如果图片直接链到 /files/，它就是原图
            if (!original && href.indexOf('/files/') === 0) {
                original = href;
            }
            return {
                src: src,
                original: original || src,
                title: title,
                caption: caption,
                page: page
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
        viewer.innerHTML =
            '<div class="wiki-image-viewer-top">'
            + '<a class="wiki-image-viewer-title" href="#"></a>'
            + '<button type="button" class="wiki-image-viewer-button wiki-image-viewer-close" aria-label="'
            + msg('imageViewerClose') + '">×</button>'
            + '</div>'
            + '<button type="button" class="wiki-image-viewer-nav wiki-image-viewer-prev" aria-label="'
            + msg('imageViewerPrev') + '">‹</button>'
            + '<div class="wiki-image-viewer-stage">'
            + '<img alt=""/>'
            + '<div class="wiki-image-viewer-status" hidden></div>'
            + '</div>'
            + '<button type="button" class="wiki-image-viewer-nav wiki-image-viewer-next" aria-label="'
            + msg('imageViewerNext') + '">›</button>'
            + '<div class="wiki-image-viewer-bottom">'
            + '<div class="wiki-image-viewer-caption"></div>'
            + '<div class="wiki-image-viewer-controls">'
            + '<button type="button" class="wiki-image-viewer-button" data-act="zoom-out" aria-label="'
            + msg('imageViewerZoomOut') + '">−</button>'
            + '<button type="button" class="wiki-image-viewer-button wiki-image-viewer-zoom" data-act="fit" title="'
            + msg('imageViewerFit') + '">100%</button>'
            + '<button type="button" class="wiki-image-viewer-button" data-act="zoom-in" aria-label="'
            + msg('imageViewerZoomIn') + '">+</button>'
            + '<div class="wiki-image-viewer-download">'
            + '<button type="button" class="wiki-image-viewer-button" data-act="download">'
            + msg('imageViewerDownload') + ' ▾</button>'
            + '<div class="wiki-image-viewer-menu" hidden></div>'
            + '</div>'
            + '</div>'
            + '</div>';
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
            menu: viewer.querySelector('.wiki-image-viewer-menu')
        };
        bindViewer();
        return viewer;
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
        viewer.querySelector('[data-act="download"]').addEventListener('click', function (event) {
            event.stopPropagation();
            toggleMenu();
        });
        // 双击图片：适应窗口 / 1:1 切换（仿 MediaWiki）
        elements.image.addEventListener('dblclick', function (event) {
            event.preventDefault();
            if (Math.abs(state.zoom - 1) < 0.01 && state.fitFactor < 1) {
                zoomToActual();
            } else {
                zoomToFit();
            }
        });
        // 点击背景关闭
        elements.stage.addEventListener('click', function (event) {
            if (event.target === elements.stage) {
                close();
            }
        });
        viewer.addEventListener('wheel', function (event) {
            event.preventDefault();
            zoomBy(event.deltaY < 0 ? 1.15 : 1 / 1.15);
        }, { passive: false });
        document.addEventListener('keydown', function (event) {
            if (viewer.hidden) {
                return;
            }
            if (event.key === 'Escape') {
                event.preventDefault();
                if (!elements.menu.hidden) {
                    hideMenu();
                } else {
                    close();
                }
            } else if (event.key === 'ArrowLeft') {
                navigate(-1);
            } else if (event.key === 'ArrowRight') {
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
            if (elements.menu && !elements.menu.hidden
                && !viewer.querySelector('.wiki-image-viewer-download').contains(event.target)) {
                hideMenu();
            }
        });
        window.addEventListener('resize', function () {
            if (!viewer.hidden) {
                applyZoom();
            }
        });
    }

    // ==================== 打开 / 关闭 / 展示 ====================

    function open(index) {
        ensureViewer();
        items = buildItems();
        if (!items.length) {
            return;
        }
        state.index = Math.max(0, Math.min(index, items.length - 1));
        viewer.hidden = false;
        document.documentElement.classList.add('wiki-image-viewer-open');
        elements.close.focus();
        showItem();
    }

    function close() {
        if (!viewer) {
            return;
        }
        viewer.hidden = true;
        document.documentElement.classList.remove('wiki-image-viewer-open');
        elements.image.removeAttribute('src');
        hideMenu();
    }

    function navigate(step) {
        if (items.length < 2) {
            return;
        }
        state.index = (state.index + step + items.length) % items.length;
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

        elements.title.textContent = item.title;
        elements.title.hidden = !item.title;
        if (item.page) {
            elements.title.href = item.page;
        } else {
            elements.title.removeAttribute('href');
        }
        elements.caption.textContent = item.caption;
        elements.prev.hidden = items.length < 2;
        elements.next.hidden = items.length < 2;
        elements.zoom.textContent = '100%';
        hideMenu();
        showStatus(msg('imageViewerLoading'));

        elements.image.onload = function () {
            hideStatus();
            state.naturalWidth = elements.image.naturalWidth || 1;
            state.naturalHeight = elements.image.naturalHeight || 1;
            applyZoom();
        };
        elements.image.onerror = function () {
            showStatus(msg('imageViewerFailed'));
        };
        elements.image.src = item.original || item.src;
    }

    // ==================== 缩放 ====================

    function computeFitFactor() {
        var rect = elements.stage.getBoundingClientRect();
        var maxWidth = Math.max(80, rect.width - 32);
        var maxHeight = Math.max(80, rect.height - 32);
        if (!state.naturalWidth || !state.naturalHeight) {
            return 1;
        }
        return Math.min(1, maxWidth / state.naturalWidth, maxHeight / state.naturalHeight);
    }

    function applyZoom() {
        if (!state.naturalWidth) {
            return;
        }
        state.fitFactor = computeFitFactor();
        var displayWidth = Math.max(40, state.naturalWidth * state.fitFactor * state.zoom);
        elements.image.style.width = displayWidth + 'px';
        elements.image.style.height = 'auto';
        elements.zoom.textContent = Math.round(state.fitFactor * state.zoom * 100) + '%';
    }

    function zoomBy(factor) {
        state.zoom = Math.min(16, Math.max(0.25, state.zoom * factor));
        applyZoom();
    }

    function zoomToFit() {
        state.zoom = 1;
        applyZoom();
    }

    function zoomToActual() {
        state.zoom = state.fitFactor > 0 ? 1 / state.fitFactor : 1;
        applyZoom();
    }

    function showStatus(text) {
        elements.status.textContent = text;
        elements.status.hidden = false;
    }

    function hideStatus() {
        elements.status.hidden = true;
    }

    // ==================== 下载（原图 / 浏览器端缩放） ====================

    function toggleMenu() {
        if (elements.menu.hidden) {
            buildMenu(items[state.index]);
            elements.menu.hidden = false;
        } else {
            hideMenu();
        }
    }

    function hideMenu() {
        if (elements.menu) {
            elements.menu.hidden = true;
        }
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
        elements.menu.innerHTML = '';
        var original = document.createElement('a');
        original.className = 'wiki-image-viewer-menu-item';
        original.href = item.original || item.src;
        original.setAttribute('download', fileNameOf(item));
        original.textContent = msg('imageViewerOriginal')
            + (state.naturalWidth ? '（' + state.naturalWidth + '×' + state.naturalHeight + '）' : '');
        elements.menu.appendChild(original);

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

    function menuStatus(button, text) {
        if (button) {
            button.disabled = false;
            button.textContent = formatScaled(Number(button.getAttribute('data-width')));
        }
        var status = elements.menu.querySelector('.wiki-image-viewer-menu-status');
        if (status) {
            status.textContent = text;
            status.hidden = false;
        }
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
                return createImageBitmap(blob).then(function (bitmap) {
                    return { blob: blob, bitmap: bitmap };
                });
            })
            .then(function (result) {
                var targetWidth = Math.max(1, Math.round(width));
                var targetHeight = Math.max(1, Math.round(result.bitmap.height * (width / result.bitmap.width)));
                var canvas = document.createElement('canvas');
                canvas.width = targetWidth;
                canvas.height = targetHeight;
                canvas.getContext('2d').drawImage(result.bitmap, 0, 0, targetWidth, targetHeight);
                var type = /jpe?g/i.test(result.blob.type || '') ? 'image/jpeg' : 'image/png';
                return new Promise(function (resolve, reject) {
                    canvas.toBlob(function (scaled) {
                        if (scaled) {
                            resolve({ blob: scaled, type: type });
                        } else {
                            reject(new Error('encode failed'));
                        }
                    }, type, type === 'image/jpeg' ? 0.92 : undefined);
                });
            })
            .then(function (result) {
                saveBlob(result.blob, scaledName(item, width, result.type));
                hideMenu();
            })
            .catch(function () {
                menuStatus(button, width, msg('imageViewerFailed'));
            });
    }

    function menuStatus(button, width, text) {
        if (button) {
            button.disabled = false;
            button.textContent = formatScaled(width);
        }
        var status = elements.menu.querySelector('.wiki-image-viewer-menu-status');
        if (status) {
            status.textContent = text;
            status.hidden = false;
        }
    }

    function nameOf(item) {
        var title = (item.title || '').replace(/^(文件|File):/i, '').trim();
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
        if (viewer && !viewer.hidden) {
            return;
        }
        var target = event.target;
        var img = target && target.closest ? target.closest('img') : null;
        if (!img) {
            return;
        }
        var all = viewableImages();
        var index = all.indexOf(img);
        if (index < 0) {
            return;
        }
        var link = img.closest ? img.closest('a') : null;
        if (link) {
            var href = link.getAttribute('href') || '';
            // 只拦截默认的「链到文件页 / 原文件」；自定义 link= 保持原行为
            if (!isFileLink(href)) {
                return;
            }
        }
        event.preventDefault();
        event.stopPropagation();
        open(index);
    }, true);
})();

