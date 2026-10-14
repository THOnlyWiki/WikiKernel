/* 监视 / 取消监视（仿 MediaWiki 的监视星标）：点页签里的「监视」切换。 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var toggle = document.getElementById('watch-toggle');
    if (toggle) {
        toggle.addEventListener('click', function (event) {
            event.preventDefault();
            var title = toggle.getAttribute('data-title');
            var watched = toggle.getAttribute('data-watched') === 'true';
            if (!title) {
                return;
            }
            toggle.style.pointerEvents = 'none';
            fetch('/api/watch', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest',
                    'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
                },
                body: new URLSearchParams({
                    title: title,
                    action: watched ? 'unwatch' : 'watch'
                })
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                if (result && result.code === 200) {
                    var now = result.data && result.data.watched === true;
                    toggle.setAttribute('data-watched', now ? 'true' : 'false');
                    toggle.textContent = now
                            ? (MESSAGES.watchUnwatch || 'watch.unwatch')
                            : (MESSAGES.watchWatch || 'watch.watch');
                }
                toggle.style.pointerEvents = '';
            }).catch(function () {
                toggle.style.pointerEvents = '';
            });
        });
    }

    // 监视列表页：每行「移除」
    Array.prototype.forEach.call(document.querySelectorAll('.watch-remove'), function (button) {
        button.addEventListener('click', function (event) {
            event.preventDefault();
            var title = button.getAttribute('data-title');
            if (!title) {
                return;
            }
            button.style.pointerEvents = 'none';
            fetch('/api/watch', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest',
                    'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
                },
                body: new URLSearchParams({title: title, action: 'unwatch'})
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                if (result && result.code === 200) {
                    window.location.reload();
                    return;
                }
                button.style.pointerEvents = '';
            }).catch(function () {
                button.style.pointerEvents = '';
            });
        });
    });

    // 「编辑监视列表」：整体保存
    var rawForm = document.getElementById('watchlist-raw-form');
    if (rawForm) {
        rawForm.addEventListener('submit', function (event) {
            event.preventDefault();
            var message = document.getElementById('watchlist-message');
            var button = rawForm.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
            }
            fetch('/api/watch/raw', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: new URLSearchParams(new FormData(rawForm))
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                if (message) {
                    message.textContent = (result && result.message) || '';
                    message.className = 'form-message' + (result && result.code === 200 ? ' success' : '');
                    message.hidden = false;
                }
                if (button) {
                    button.disabled = false;
                }
            }).catch(function () {
                if (message) {
                    message.textContent = MESSAGES.networkError || 'auth.networkError';
                    message.className = 'form-message';
                    message.hidden = false;
                }
                if (button) {
                    button.disabled = false;
                }
            });
        });
    }
})();
