/*
 * 多媒体预览（仿 MediaWiki TimedMediaHandler）：
 *  - .wiki-media-player 里的原生 <video> / <audio> 增强为 MediaWiki 风格播放器：
 *    居中大播放按钮、播放 / 暂停、当前时间 / 总时长、红色进度条（可拖动）、音量、倍速、全屏、原始文件下载
 *  - 无 JS 时回退为浏览器原生 controls；格式不支持时显示提示 + 下载链接
 *  - 键盘：空格 / K 播放暂停、← → 快退快进 5 秒、↑ ↓ 音量、M 静音、F 全屏（视频）
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var RATES = [1, 1.25, 1.5, 2, 0.5];

    /** 取一条界面文案（服务端经 window.WIKI_MESSAGES 注入）；缺失时显示键名本身。 */
    function msg(key) {
        return MESSAGES[key] || key;
    }

    var ICONS = {
        play: '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M8 5v14l11-7z"/></svg>',
        pause: '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M7 5h4v14H7zM13 5h4v14h-4z"/></svg>',
        volume: '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M4 9v6h4l5 4V5L8 9H4zm12.5 3a4.5 4.5 0 0 0-2.5-4v8a4.5 4.5 0 0 0 2.5-4z"/></svg>',
        muted: '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M4 9v6h4l5 4V5L8 9H4zm13.3 1.1-1.4-1.4 2.1 2.1 2.1-2.1 1.4 1.4-2.1 2.1 2.1 2.1-1.4 1.4-2.1-2.1-2.1 2.1-1.4-1.4 2.1-2.1z"/></svg>',
        fullscreen: '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M4 4h6v2H6v4H4zm10 0h6v6h-2V6h-4zm6 10v6h-6v-2h4v-4zM4 14h2v4h4v2H4z"/></svg>',
        exitFullscreen: '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M10 4v6H4V8h4V4zm10 0v4h-4v2h6V4zM4 16v-4h2v4h4v2H4zm12 0h4v-2h-4v-2h-2v6h6v-2z"/></svg>',
        download: '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M12 3v9l3.5-3.5 1.4 1.4L12 15.8 7.1 9.9l1.4-1.4L12 12V3zM5 18h14v2H5z"/></svg>'
    };

    function formatTime(seconds) {
        if (!isFinite(seconds) || seconds < 0) {
            return '0:00';
        }
        var total = Math.floor(seconds);
        var hours = Math.floor(total / 3600);
        var minutes = Math.floor((total % 3600) / 60);
        var secs = total % 60;
        function pad(value) {
            return value < 10 ? '0' + value : String(value);
        }
        if (hours > 0) {
            return hours + ':' + pad(minutes) + ':' + pad(secs);
        }
        return minutes + ':' + pad(secs);
    }

    function enhance(container) {
        if (container.__wikiMediaPlayer) {
            return;
        }
        var media = container.querySelector('video, audio');
        if (!media) {
            return;
        }
        container.__wikiMediaPlayer = true;

        var isVideo = media.tagName.toLowerCase() === 'video';
        var src = container.getAttribute('data-src') || media.getAttribute('src') || '';
        var name = container.getAttribute('data-name') || '';
        var rateIndex = 0;
        var seeking = false;

        media.removeAttribute('controls');
        container.classList.add('mw-player-enhanced');

        var bigPlay = document.createElement('button');
        bigPlay.type = 'button';
        bigPlay.className = 'mw-player-bigplay';
        bigPlay.innerHTML = ICONS.play;
        bigPlay.setAttribute('aria-label', msg('mediaPlay'));

        var controls = document.createElement('div');
        controls.className = 'mw-player-controls';
        controls.innerHTML =
            '<button type="button" class="mw-player-button mw-player-play" aria-label="play">' + ICONS.play + '</button>'
            + '<span class="mw-player-time"><span class="mw-player-current">0:00</span>'
            + ' / <span class="mw-player-duration">0:00</span></span>'
            + '<input type="range" class="mw-player-seek" min="0" max="1000" step="1" value="0" aria-label="seek"/>'
            + '<button type="button" class="mw-player-button mw-player-mute" aria-label="mute">' + ICONS.volume + '</button>'
            + '<input type="range" class="mw-player-volume" min="0" max="1" step="0.05" value="1" aria-label="volume"/>'
            + '<button type="button" class="mw-player-button mw-player-rate" title="'
            + msg('mediaRate') + '">1×</button>'
            + (isVideo ? '<button type="button" class="mw-player-button mw-player-fullscreen" aria-label="fullscreen">'
                + ICONS.fullscreen + '</button>' : '')
            + '<a class="mw-player-button mw-player-download" href="' + src.replace(/"/g, '&quot;')
            + '" download="' + name.replace(/"/g, '&quot;') + '" title="'
            + msg('mediaDownload') + '">' + ICONS.download + '</a>';

        container.appendChild(bigPlay);
        container.appendChild(controls);

        var playButton = controls.querySelector('.mw-player-play');
        var muteButton = controls.querySelector('.mw-player-mute');
        var seek = controls.querySelector('.mw-player-seek');
        var currentText = controls.querySelector('.mw-player-current');
        var durationText = controls.querySelector('.mw-player-duration');
        var volume = controls.querySelector('.mw-player-volume');
        var rateButton = controls.querySelector('.mw-player-rate');
        var fullscreenButton = controls.querySelector('.mw-player-fullscreen');

        function toggle() {
            if (media.paused || media.ended) {
                var promise = media.play();
                if (promise && promise.catch) {
                    promise.catch(function () { /* 用户手势限制 / 解码错误由 error 事件提示 */ });
                }
            } else {
                media.pause();
            }
        }

        function toggleFullscreen() {
            if (document.fullscreenElement) {
                document.exitFullscreen();
                return;
            }
            if (container.requestFullscreen) {
                container.requestFullscreen();
            } else if (media.webkitEnterFullscreen) {
                media.webkitEnterFullscreen();
            }
        }

        function updatePlayState() {
            var playing = !media.paused && !media.ended;
            playButton.innerHTML = playing ? ICONS.pause : ICONS.play;
            playButton.setAttribute('aria-label', playing ? msg('mediaPause') : msg('mediaPlay'));
            container.classList.toggle('mw-player-playing', playing);
            bigPlay.hidden = playing;
        }

        function updateTime() {
            if (isFinite(media.duration)) {
                durationText.textContent = formatTime(media.duration);
            }
            if (seeking) {
                return;
            }
            currentText.textContent = formatTime(media.currentTime);
            if (isFinite(media.duration) && media.duration > 0) {
                var value = Math.round((media.currentTime / media.duration) * 1000);
                seek.value = String(value);
                seek.style.setProperty('--mw-progress', (value / 10) + '%');
            }
        }

        function updateVolume() {
            volume.value = String(media.muted ? 0 : media.volume);
            muteButton.innerHTML = (media.muted || media.volume === 0) ? ICONS.muted : ICONS.volume;
            muteButton.setAttribute('aria-label',
                (media.muted || media.volume === 0) ? msg('mediaUnmute') : msg('mediaMute'));
        }

        function showFallback() {
            container.classList.add('mw-player-error');
            var fallback = container.querySelector('.wiki-media-fallback');
            if (fallback) {
                fallback.textContent = msg('mediaUnsupported');
                fallback.hidden = false;
            }
        }

        media.addEventListener('play', updatePlayState);
        media.addEventListener('pause', updatePlayState);
        media.addEventListener('ended', updatePlayState);
        media.addEventListener('timeupdate', updateTime);
        media.addEventListener('durationchange', updateTime);
        media.addEventListener('loadedmetadata', updateTime);
        media.addEventListener('volumechange', updateVolume);
        media.addEventListener('waiting', function () {
            container.classList.add('mw-player-loading');
        });
        media.addEventListener('playing', function () {
            container.classList.remove('mw-player-loading');
        });
        media.addEventListener('canplay', function () {
            container.classList.remove('mw-player-loading');
        });
        media.addEventListener('error', showFallback);
        media.addEventListener('click', function (event) {
            event.preventDefault();
            toggle();
        });
        if (isVideo) {
            media.addEventListener('dblclick', function (event) {
                event.preventDefault();
                toggleFullscreen();
            });
        }

        bigPlay.addEventListener('click', toggle);
        playButton.addEventListener('click', toggle);
        seek.addEventListener('input', function () {
            seeking = true;
            if (isFinite(media.duration) && media.duration > 0) {
                media.currentTime = (Number(seek.value) / 1000) * media.duration;
                currentText.textContent = formatTime(media.currentTime);
                seek.style.setProperty('--mw-progress', (Number(seek.value) / 10) + '%');
            }
        });
        ['change', 'pointerup', 'mouseup', 'touchend'].forEach(function (type) {
            seek.addEventListener(type, function () {
                seeking = false;
            });
        });
        volume.addEventListener('input', function () {
            media.volume = Number(volume.value);
            media.muted = media.volume === 0;
        });
        muteButton.addEventListener('click', function () {
            media.muted = !media.muted;
        });
        rateButton.addEventListener('click', function () {
            rateIndex = (rateIndex + 1) % RATES.length;
            media.playbackRate = RATES[rateIndex];
            rateButton.textContent = RATES[rateIndex] + '×';
        });
        if (fullscreenButton) {
            fullscreenButton.addEventListener('click', toggleFullscreen);
        }
        document.addEventListener('fullscreenchange', function () {
            if (fullscreenButton) {
                fullscreenButton.innerHTML = document.fullscreenElement === container
                    ? ICONS.exitFullscreen : ICONS.fullscreen;
            }
        });

        container.addEventListener('keydown', function (event) {
            switch (event.key) {
                case ' ':
                case 'k':
                case 'K':
                    event.preventDefault();
                    toggle();
                    break;
                case 'ArrowLeft':
                    media.currentTime = Math.max(0, media.currentTime - 5);
                    break;
                case 'ArrowRight':
                    media.currentTime = Math.min(media.duration || 0, media.currentTime + 5);
                    break;
                case 'ArrowUp':
                    event.preventDefault();
                    media.muted = false;
                    media.volume = Math.min(1, media.volume + 0.05);
                    break;
                case 'ArrowDown':
                    event.preventDefault();
                    media.muted = false;
                    media.volume = Math.max(0, media.volume - 0.05);
                    break;
                case 'm':
                case 'M':
                    media.muted = !media.muted;
                    break;
                case 'f':
                case 'F':
                    if (isVideo) {
                        toggleFullscreen();
                    }
                    break;
            }
        });

        updatePlayState();
        updateTime();
        updateVolume();
    }

    function scan(root) {
        if (!root || !root.querySelectorAll) {
            return;
        }
        if (root.nodeType === 1 && root.classList && root.classList.contains('wiki-media-player')) {
            enhance(root);
        }
        Array.prototype.forEach.call(root.querySelectorAll('.wiki-media-player'), enhance);
    }

    scan(document);
    new MutationObserver(function (mutations) {
        mutations.forEach(function (mutation) {
            Array.prototype.forEach.call(mutation.addedNodes, function (node) {
                if (node.nodeType === 1) {
                    scan(node);
                }
            });
        });
    }).observe(document.documentElement, { childList: true, subtree: true });
})();
