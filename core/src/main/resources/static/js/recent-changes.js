/*
 * 最近更改页交互（仿 MediaWiki）：
 *  - 过滤更改输入框：按文本过滤条目
 *  - 动态更新：每 15 秒用 Ajax 拉取当前页面，把新条目插到最上面（淡黄高亮渐隐），
 *    不再整页刷新；标签页隐藏时暂停轮询，重新可见时立刻补一次
 *  - 激活的过滤器：隐藏 / 显示过滤区（过滤器的移除全部走服务端链接，不用前端假删除）
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var LIVE_INTERVAL = 15000;
    var MAX_INSERT = 20;

    var filterInput = document.getElementById('rc-filter-input');
    if (filterInput) {
        filterInput.addEventListener('input', function () {
            var query = filterInput.value.trim().toLowerCase();
            document.querySelectorAll('.rc-entry').forEach(function (entry) {
                var text = entry.textContent.toLowerCase();
                entry.style.display = (!query || text.indexOf(query) >= 0) ? '' : 'none';
            });
        });
    }

    var liveToggle = document.getElementById('rc-live-toggle');
    var liveTimer = null;

    function setLiveIcon(text) {
        var icon = liveToggle ? liveToggle.querySelector('.rc-live-icon') : null;
        if (icon) {
            icon.textContent = text;
        }
    }

    function stopLive() {
        if (liveTimer) {
            window.clearInterval(liveTimer);
            liveTimer = null;
        }
        if (liveToggle) {
            liveToggle.classList.remove('active');
            liveToggle.setAttribute('title', MESSAGES.liveOffTitle || 'recentChanges.liveOffTitle');
        }
        setLiveIcon('▶');
    }

    function startLive() {
        if (liveTimer) {
            return;
        }
        if (liveToggle) {
            liveToggle.classList.add('active');
            liveToggle.setAttribute('title', MESSAGES.liveOnTitle || 'recentChanges.liveOnTitle');
        }
        setLiveIcon('❚❚');
        liveTimer = window.setInterval(poll, LIVE_INTERVAL);
        poll();
    }

    function poll() {
        if (document.hidden) {
            return;
        }
        fetch(window.location.href, {
            headers: { 'Accept': 'text/html', 'X-Requested-With': 'XMLHttpRequest' }
        }).then(function (response) {
            return response.text();
        }).then(applyLive).catch(function () {
            // 离线 / 网络错误：静默跳过，下轮再试
        });
    }

    /**
     * 把服务器最新列表合并进 #rc-days：
     *  - 已有条目（同 key）内容变了 → 原地替换（组内新增编辑、N次更改 计数更新）
     *  - 新条目 → 插到对应日期块最上面
     * 变化太多（> MAX_INSERT）时退回整页刷新。
     */
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

        // 收集：更新 + 新增（按日期块）
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
        if (total > MAX_INSERT) {
            window.location.reload();
            return;
        }

        // 原地替换有变化的条目（保留展开状态）
        plans.forEach(function (plan) {
            plan.updates.forEach(function (pair) {
                replaceEntry(pair.current, pair.incoming);
            });
        });

        // 插入新条目：日期块从旧到新处理，保证新日期块叠在最上面时顺序正确
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

        if (liveToggle) {
            liveToggle.classList.add('rc-live-flash');
            window.setTimeout(function () {
                liveToggle.classList.remove('rc-live-flash');
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

    if (liveToggle) {
        stopLive();
        liveToggle.addEventListener('click', function () {
            if (liveTimer) {
                stopLive();
            } else {
                startLive();
            }
        });
        // 回到前台立刻补一次
        document.addEventListener('visibilitychange', function () {
            if (!document.hidden && liveTimer) {
                poll();
            }
        });
    }

    var filtersToggle = document.getElementById('rc-filters-toggle');
    var activeFilters = document.getElementById('rc-active-filters');
    if (filtersToggle && activeFilters) {
        filtersToggle.addEventListener('click', function () {
            activeFilters.hidden = !activeFilters.hidden;
            filtersToggle.textContent = activeFilters.hidden
                ? filtersToggle.getAttribute('data-show')
                : filtersToggle.getAttribute('data-hide');
        });
    }
})();
