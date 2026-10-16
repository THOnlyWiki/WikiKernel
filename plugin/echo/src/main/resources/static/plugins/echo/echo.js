/*
 * Echo 插件前端（站点级注入，皮肤无关）：
 *  - 顶栏两个条目（提醒 .echo-alerts / 一般通知 .echo-notices）：各自开合下拉面板；
 *  - 面板：拉取 GET /api/echo/list?group=… 填充最近通知；「全部标为已读」调 POST /api/echo/mark-all-read?group=…
 *    （只清本组）；点条目 → 标记该条已读并跳转；
 *  - 特殊:通知 页：.echo-mark-read（单条已读）、.echo-mark-all-read（全部已读）；
 *  - 未读轮询：页面可见时每 60 秒拉一次 GET /api/echo/unread-count，更新两个条目的红点。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    function api(path, options) {
        var opts = options || {};
        opts.headers = Object.assign({
            'Accept': 'application/json',
            'X-Requested-With': 'XMLHttpRequest'
        }, opts.headers || {});
        return fetch(path, opts).then(function (response) {
            return response.json();
        });
    }

    function post(path, body) {
        return api(path, { method: 'POST', body: body || new URLSearchParams() });
    }

    /* ==================== 铃铛 + 下拉面板 ==================== */

    var ROOT = 'https://x'; // 只用来解析 href，不会真的请求

    function absolute(href) {
        try {
            return new URL(href, window.location.origin).href;
        } catch (e) {
            return href;
        }
    }

    function setBadge(entry, count, text) {
        var badge = entry.querySelector('.echo-badge');
        if (count > 0) {
            if (!badge) {
                badge = document.createElement('span');
                badge.className = 'echo-badge';
                var link = entry.querySelector('.echo-bell');
                (link || entry).appendChild(badge);
            }
            badge.textContent = String(count);
        } else if (badge) {
            badge.remove();
        }
    }

    function positionFlyout(flyout, bell) {
        if (!flyout || !bell) {
            return;
        }
        var rect = bell.getBoundingClientRect();
        var width = flyout.offsetWidth || 360;
        var left = Math.max(8, Math.min(rect.left + rect.width - width, window.innerWidth - width - 8));
        flyout.style.top = Math.round(rect.bottom + 8) + 'px';
        flyout.style.left = Math.round(left) + 'px';
        flyout.style.right = 'auto';
    }

    function renderFlyout(flyout, items) {
        var list = flyout.querySelector('.echo-flyout-list');
        if (!list) {
            return;
        }
        list.innerHTML = '';
        if (!items || !items.length) {
            var empty = document.createElement('div');
            empty.className = 'echo-flyout-empty';
            empty.textContent = MESSAGES.echoEmpty || '暂无通知。';
            list.appendChild(empty);
            return;
        }
        items.forEach(function (item) {
            var row = document.createElement('a');
            row.className = 'echo-flyout-item' + (item.read ? '' : ' echo-unread');
            row.href = item.pageUrl || '/wiki/特殊:通知';
            row.setAttribute('data-id', String(item.id));

            var icon = document.createElement('span');
            icon.className = 'echo-icon';
            icon.textContent = item.icon || '🔔';

            var body = document.createElement('span');
            body.className = 'echo-item-body';

            var desc = document.createElement('span');
            desc.className = 'echo-item-desc';
            desc.innerHTML = item.html || '';

            var time = document.createElement('span');
            time.className = 'echo-item-time';
            time.textContent = item.timeText || '';

            body.appendChild(desc);
            body.appendChild(document.createElement('br'));
            body.appendChild(time);
            row.appendChild(icon);
            row.appendChild(body);

            row.addEventListener('click', function () {
                if (!item.read) {
                    post('/api/echo/mark-read', new URLSearchParams({ id: String(item.id) }))
                        .then(function (result) {
                            if (result && result.code === 200) {
                                refreshCounts();
                            }
                        }).catch(function () { });
                }
            });
            list.appendChild(row);
        });
    }

    function setupEntry(entry) {
        var bell = entry.querySelector('.echo-bell');
        var flyout = entry.querySelector('.echo-flyout');
        if (!bell || !flyout) {
            return null;
        }
        var group = flyout.getAttribute('data-group') || entry.getAttribute('data-group') || '';

        // 面板必须挂在 <body> 下：kawaii-timeless 等皮肤对 #personal-extra 内的 span / a 有隐藏与装饰规则
        // （例如「未读数 span → 红点」），面板留在 li 里会被清掉内容 / 被层叠上下文裁剪。
        if (flyout.parentElement !== document.body) {
            document.body.appendChild(flyout);
        }

        function load() {
            api('/api/echo/list?limit=15' + (group ? '&group=' + encodeURIComponent(group) : ''))
                .then(function (result) {
                    if (result && result.code === 200 && result.data) {
                        renderFlyout(flyout, result.data.items || []);
                    } else {
                        renderFlyout(flyout, []);
                    }
                }).catch(function () {
                    renderFlyout(flyout, []);
                });
        }

        bell.addEventListener('click', function (event) {
            event.preventDefault();
            if (flyout.hidden) {
                document.querySelectorAll('.echo-flyout').forEach(function (other) {
                    other.hidden = true;
                });
                flyout.hidden = false;
                positionFlyout(flyout, bell);
                load();
            } else {
                flyout.hidden = true;
            }
        });

        var markAll = flyout.querySelector('.echo-mark-all');
        if (markAll) {
            markAll.addEventListener('click', function (event) {
                event.preventDefault();
                post('/api/echo/mark-all-read' + (group ? '?group=' + encodeURIComponent(group) : ''))
                    .then(function () {
                        refreshCounts();
                        load();
                    }).catch(function () { });
            });
        }
        return { entry: entry, flyout: flyout, bell: bell };
    }

    var entries = Array.prototype.slice.call(document.querySelectorAll('.echo-entry')).map(setupEntry)
        .filter(function (value) { return value !== null; });

    if (entries.length) {
        document.addEventListener('click', function (event) {
            entries.forEach(function (item) {
                if (item.flyout.hidden) {
                    return;
                }
                if (item.entry.contains(event.target) || item.flyout.contains(event.target)) {
                    return;
                }
                item.flyout.hidden = true;
            });
        });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                entries.forEach(function (item) { item.flyout.hidden = true; });
            }
        });
        window.addEventListener('resize', function () {
            entries.forEach(function (item) {
                if (!item.flyout.hidden) {
                    positionFlyout(item.flyout, item.bell);
                }
            });
        });
    }

    /* ==================== 未读轮询 / 徽标刷新 ==================== */

    function applyCounts(data) {
        entries.forEach(function (item) {
            var group = item.flyout.getAttribute('data-group') || '';
            var count = group === 'notice' ? data.notices
                : group === 'alert' ? data.alerts : data.count;
            setBadge(item.entry, count || 0);
        });
    }

    function refreshCounts() {
        return api('/api/echo/unread-count').then(function (result) {
            if (result && result.code === 200 && result.data) {
                applyCounts(result.data);
            }
        }).catch(function () { });
    }

    var POLL_MS = 60000;
    if (entries.length) {
        window.setInterval(function () {
            if (!document.hidden) {
                refreshCounts();
            }
        }, POLL_MS);
    }

    /* ==================== 特殊:通知 页 ==================== */

    var pageMessage = document.getElementById('echo-page-message');
    function showPageMessage(text) {
        if (!pageMessage) {
            return;
        }
        pageMessage.textContent = text;
        pageMessage.hidden = false;
    }

    Array.prototype.forEach.call(document.querySelectorAll('.echo-mark-read'), function (button) {
        button.addEventListener('click', function () {
            var id = button.getAttribute('data-id');
            button.disabled = true;
            post('/api/echo/mark-read', new URLSearchParams({ id: String(id) })).then(function (result) {
                if (result && result.code === 200) {
                    var item = button.closest('.echo-item');
                    if (item) {
                        item.classList.remove('echo-unread');
                    }
                    button.remove();
                } else {
                    button.disabled = false;
                    showPageMessage((result && result.message) || MESSAGES.echoOperationFailed || '操作失败');
                }
            }).catch(function () {
                button.disabled = false;
                showPageMessage(MESSAGES.echoNetworkError || '网络错误');
            });
        });
    });

    var pageMarkAll = document.querySelector('.echo-mark-all-read');
    if (pageMarkAll) {
        pageMarkAll.addEventListener('click', function () {
            pageMarkAll.disabled = true;
            post('/api/echo/mark-all-read').then(function () {
                window.location.reload();
            }).catch(function () {
                pageMarkAll.disabled = false;
                showPageMessage(MESSAGES.echoNetworkError || '网络错误');
            });
        });
    }

    /* ==================== 参数设置 → 通知 ==================== */

    var prefForm = document.getElementById('echo-preferences-form');
    if (prefForm) {
        prefForm.addEventListener('submit', function (event) {
            event.preventDefault();
            var button = prefForm.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
            }
            var body = new URLSearchParams();
            new FormData(prefForm).forEach(function (value, key) {
                body.append(key, value);
            });
            post(prefForm.getAttribute('data-api') || '/api/echo/preferences', body)
                .then(function (result) {
                    var box = prefForm.querySelector('.form-message');
                    if (box) {
                        box.textContent = (result && result.message)
                            || MESSAGES.echoPreferencesSaved || '已保存';
                        box.hidden = false;
                    }
                    if (button) {
                        button.disabled = false;
                    }
                }).catch(function () {
                    var box = prefForm.querySelector('.form-message');
                    if (box) {
                        box.textContent = MESSAGES.echoNetworkError || '网络错误';
                        box.hidden = false;
                    }
                    if (button) {
                        button.disabled = false;
                    }
                });
        });
    }
})();
