/*
 * 最近更改页交互（仿 MediaWiki）：
 *  - 过滤更改输入框：按文本过滤条目
 *  - 动态更新：每 15 秒用 Ajax 拉取当前页面，把新条目插到最上面（淡黄高亮渐隐），
 *    不再整页刷新；标签页隐藏时暂停轮询，重新可见时立刻补一次
 *  - 过滤器 / 命名空间 / 标签 / 隐藏 / 范围 / 已激活过滤器：全部用 Ajax 替换页面内容，
 *    只更新最近更改区域并 pushState，不整体刷新（浏览器后退也走 Ajax）
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var LIVE_INTERVAL = 15000;
    var MAX_INSERT = 20;

    var liveTimer = null;
    var liveBusy = false;

    function liveButton() {
        return document.getElementById('rc-live-toggle');
    }

    function setLiveIcon(text) {
        var button = liveButton();
        var icon = button ? button.querySelector('.rc-live-icon') : null;
        if (icon) {
            icon.textContent = text;
        }
    }

    function syncLiveState() {
        var button = liveButton();
        if (!button) {
            return;
        }
        if (liveTimer) {
            button.classList.add('active');
            button.setAttribute('title', MESSAGES.liveOnTitle || 'recentChanges.liveOnTitle');
            setLiveIcon('❚❚');
        } else {
            button.classList.remove('active');
            button.setAttribute('title', MESSAGES.liveOffTitle || 'recentChanges.liveOffTitle');
            setLiveIcon('▶');
        }
    }

    function stopLive() {
        if (liveTimer) {
            window.clearInterval(liveTimer);
            liveTimer = null;
        }
        syncLiveState();
    }

    function startLive() {
        if (liveTimer) {
            return;
        }
        liveTimer = window.setInterval(poll, LIVE_INTERVAL);
        syncLiveState();
        poll();
    }

    function poll() {
        if (document.hidden || liveBusy) {
            return;
        }
        liveBusy = true;
        fetch(window.location.href, {
            headers: { 'Accept': 'text/html', 'X-Requested-With': 'XMLHttpRequest' }
        }).then(function (response) {
            return response.text();
        }).then(applyLive).catch(function () {
            // 离线 / 网络错误：静默跳过，下轮再试
        }).then(function () {
            liveBusy = false;
        });
    }

    /** 替换页面的最近更改区域，并保持当前滚动位置。 */
    function replacePageSection(doc, finalUrl, push) {
        var current = document.querySelector('.mw-rc-page');
        var next = doc ? doc.querySelector('.mw-rc-page') : null;
        if (!current || !next) {
            return false;
        }
        var scrollY = window.scrollY;
        current.innerHTML = next.innerHTML;
        if (doc.title) {
            document.title = doc.title;
        }
        if (push && finalUrl && finalUrl !== window.location.href) {
            window.history.pushState({ recentChanges: true }, '', finalUrl);
        }
        window.scrollTo(0, scrollY);
        syncLiveState();
        return true;
    }

    /** 过滤器交互：AJAX 拉取同一页面，只换内容，不整体刷新。 */
    function loadFilter(rawUrl, push) {
        var url = new URL(rawUrl, window.location.href).href;
        fetch(url, {
            headers: { 'Accept': 'text/html', 'X-Requested-With': 'XMLHttpRequest' }
        }).then(function (response) {
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            var finalUrl = response.url || url;
            return response.text().then(function (html) {
                return { html: html, url: finalUrl };
            });
        }).then(function (result) {
            var doc = new DOMParser().parseFromString(result.html, 'text/html');
            if (!replacePageSection(doc, result.url, push)) {
                window.location.href = url;
            }
        }).catch(function () {
            // 极少数情况下服务器返回了非页面内容：退回普通导航，避免停在半更新状态。
            window.location.href = url;
        });
    }

    /** 把服务器最新列表合并进 #rc-days。 */
    function applyLive(html) {
        var container = document.getElementById('rc-days');
        if (!container) {
            return;
        }
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var existingByKey = {};
        Array.prototype.forEach.call(document.querySelectorAll('.rc-entry[data-key]'), function (entry) {
            existingByKey[entry.getAttribute('data-key')] = entry;
        });

        var plans = [];
        var total = 0;
        Array.prototype.forEach.call(doc.querySelectorAll('.rc-day'), function (day) {
            var titleEl = day.querySelector('.rc-day-title');
            var plan = {
                title: titleEl ? titleEl.textContent : '',
                inserts: [],
                updates: []
            };
            Array.prototype.forEach.call(day.querySelectorAll('.rc-entry'), function (entry) {
                var key = entry.getAttribute('data-key');
                if (!key) {
                    return;
                }
                var current = existingByKey[key];
                if (!current) {
                    plan.inserts.push(entry);
                    total += 1;
                } else if (current.textContent !== entry.textContent) {
                    plan.updates.push({ current: current, incoming: entry });
                    total += 1;
                }
            });
            if (plan.inserts.length > 0 || plan.updates.length > 0) {
                plans.push(plan);
            }
        });
        if (total === 0) {
            return;
        }
        // 变化太多时不整页刷新：直接替换列表区域即可。
        if (total > MAX_INSERT) {
            var nextDays = doc.getElementById('rc-days');
            if (nextDays) {
                container.innerHTML = nextDays.innerHTML;
            }
            return;
        }

        plans.forEach(function (plan) {
            plan.updates.forEach(function (pair) {
                replaceEntry(pair.current, pair.incoming);
            });
        });

        for (var i = plans.length - 1; i >= 0; i--) {
            var plan = plans[i];
            if (plan.inserts.length === 0) {
                continue;
            }
            var targetDay = null;
            Array.prototype.forEach.call(container.querySelectorAll('.rc-day'), function (candidate) {
                var heading = candidate.querySelector('.rc-day-title');
                if (heading && heading.textContent === plan.title) {
                    targetDay = candidate;
                }
            });
            if (!targetDay) {
                targetDay = document.createElement('div');
                targetDay.className = 'rc-day';
                var heading = document.createElement('h2');
                heading.className = 'rc-day-title';
                heading.textContent = plan.title;
                targetDay.appendChild(heading);
                var emptyList = document.createElement('ul');
                emptyList.className = 'rc-list';
                targetDay.appendChild(emptyList);
                container.insertBefore(targetDay, container.firstChild);
            }
            var list = targetDay.querySelector('.rc-list');
            var fragment = document.createDocumentFragment();
            plan.inserts.forEach(function (entry) {
                var clone = entry.cloneNode(true);
                clone.classList.add('rc-live-new');
                fragment.appendChild(clone);
            });
            list.insertBefore(fragment, list.firstChild);
        }

        var button = liveButton();
        if (button) {
            button.classList.add('rc-live-flash');
            window.setTimeout(function () {
                button.classList.remove('rc-live-flash');
            }, 1200);
        }
    }

    /** 用服务器版本替换旧条目，保留 <details> 展开状态并高亮。 */
    function replaceEntry(current, incoming) {
        var openDetails = current.querySelector('details[open]');
        var clone = incoming.cloneNode(true);
        clone.classList.add('rc-live-new');
        current.replaceWith(clone);
        if (openDetails) {
            var details = clone.querySelector('details');
            if (details) {
                details.open = true;
            }
        }
    }

    function applyTextFilter() {
        var input = document.getElementById('rc-filter-input');
        var query = input ? input.value.trim().toLowerCase() : '';
        document.querySelectorAll('.rc-entry').forEach(function (entry) {
            var text = entry.textContent.toLowerCase();
            entry.style.display = (!query || text.indexOf(query) >= 0) ? '' : 'none';
        });
    }

    function toggleFilters() {
        var activeFilters = document.getElementById('rc-active-filters');
        var button = document.getElementById('rc-filters-toggle');
        if (!activeFilters || !button) {
            return;
        }
        activeFilters.hidden = !activeFilters.hidden;
        button.textContent = activeFilters.hidden
                ? button.getAttribute('data-show')
                : button.getAttribute('data-hide');
    }

    // 事件委托：替换页面内容后不需要重新绑定每个新元素。
    document.addEventListener('input', function (event) {
        if (event.target && event.target.id === 'rc-filter-input') {
            applyTextFilter();
        }
    });

    document.addEventListener('click', function (event) {
        var target = event.target;
        if (!target || !target.closest) {
            return;
        }
        if (target.closest('#rc-live-toggle')) {
            event.preventDefault();
            if (liveTimer) {
                stopLive();
            } else {
                startLive();
            }
            return;
        }
        if (target.closest('#rc-filters-toggle')) {
            event.preventDefault();
            toggleFilters();
            return;
        }
        var filterLink = target.closest('.rc-filters a, .rc-toolbar .rc-menu a');
        if (filterLink && filterLink.getAttribute('href')) {
            event.preventDefault();
            loadFilter(filterLink.href, true);
        }
    });

    document.addEventListener('visibilitychange', function () {
        if (!document.hidden && liveTimer) {
            poll();
        }
    });

    window.addEventListener('popstate', function () {
        loadFilter(window.location.href, false);
    });

    // 初次加载保持按钮状态（默认停止），并确保服务端渲染的按钮样式正确。
    syncLiveState();
})();
