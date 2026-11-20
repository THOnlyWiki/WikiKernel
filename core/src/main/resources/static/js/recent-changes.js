/*
 * 最近更改页交互（仿 MediaWiki）：
 *  - Timeless 搜索筛选选项，其他皮肤可按列表文本过滤
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
    var filterRequest = null;
    var filterVersion = 0;
    var highlightMode = false;
    var filterFailure;
    var filterSection = document.querySelector('.mw-rc-page');
    var filterStatus = filterSection && window.WikiQueryStatus
        ? window.WikiQueryStatus(filterSection, function () {
            if (filterFailure) loadFilter(filterFailure.url, filterFailure.push, filterFailure.menu);
        }) : null;

    function liveButton() {
        return document.getElementById('rc-live-toggle');
    }

    function setLiveIcon(text) {
        var button = liveButton();
        var icon = button ? button.querySelector('.rc-live-icon') : null;
        if (icon && !button.closest('.rc-timeless')) {
            icon.textContent = text;
        }
    }

    function syncLiveState() {
        var button = liveButton();
        if (!button) {
            return;
        }
        button.setAttribute('aria-pressed', String(Boolean(liveTimer)));
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
        if (document.hidden || liveBusy || filterRequest) {
            return;
        }
        liveBusy = true;
        var version = filterVersion;
        fetch(window.location.href, {
            headers: { 'Accept': 'text/html', 'X-Requested-With': 'XMLHttpRequest' }
        }).then(function (response) {
            return response.text();
        }).then(function (html) {
            if (liveTimer && version === filterVersion) {
                applyLive(html);
                applyTextFilter();
            }
        }).catch(function () {
            // 离线 / 网络错误：静默跳过，下轮再试
        }).then(function () {
            liveBusy = false;
        });
    }

    /** 替换页面的最近更改区域，并保持当前滚动位置。 */
    function replacePageSection(doc, finalUrl, push, openMenu) {
        var current = document.querySelector('.mw-rc-page');
        var next = doc ? doc.querySelector('.mw-rc-page') : null;
        if (!current || !next) {
            return false;
        }
        var scrollY = window.scrollY;
        var input = current.querySelector('#rc-filter-input');
        var query = input ? input.value : '';
        var collapsed = current.querySelector('#rc-filters-toggle[aria-expanded="false"]');
        var beforeChips = captureChips(current);
        var priorMenu = openMenu && current.querySelector(openMenu);
        var options = priorMenu ? Array.from(priorMenu.querySelectorAll('a')) : [];
        var focusedIndex = options.indexOf(document.activeElement);
        var pickerScroll = current.querySelector('.rc-picker-options');
        var pickerOffset = pickerScroll ? pickerScroll.scrollTop : 0;
        var restored = openMenu && next.querySelector('.rc-timeless');
        if (restored) restored.classList.add('rc-restoring');
        current.replaceChildren(...next.childNodes);
        if (doc.title) {
            document.title = doc.title;
        }
        if (push && finalUrl && finalUrl !== window.location.href) {
            window.history.pushState({ recentChanges: true }, '', finalUrl);
        }
        window.scrollTo(0, scrollY);
        syncLiveState();
        renderSavedFilters();
        input = current.querySelector('#rc-filter-input');
        if (input) input.value = query;
        if (collapsed) setFiltersVisible(false, false);
        syncHighlightMode();
        var menu = openMenu && current.querySelector(openMenu);
        if (menu) {
            menu.open = true;
            if (openMenu === '#rc-filter-picker') {
                filterPicker(query);
                menu.querySelector('.rc-picker-options').scrollTop = pickerOffset;
            }
            var nextLink = menu.querySelectorAll('a')[focusedIndex];
            if (nextLink) nextLink.focus({ preventScroll: true });
        }
        if (restored) {
            requestAnimationFrame(function () {
                requestAnimationFrame(function () { restored.classList.remove('rc-restoring'); });
            });
        }
        applyTextFilter();
        animateFilterResult(current, beforeChips);
        return true;
    }

    /** 过滤器交互：AJAX 拉取同一页面，只换内容，不整体刷新。 */
    function loadFilter(rawUrl, push, openMenu) {
        var url = new URL(rawUrl, window.location.href).href;
        if (filterRequest) filterRequest.abort();
        var request = new AbortController();
        filterRequest = request;
        filterVersion++;
        filterFailure = null;
        if (filterStatus) filterStatus('loading');
        var timeout = setTimeout(function () { request.abort(); }, 15000);
        fetch(url, {
            headers: { 'Accept': 'text/html', 'X-Requested-With': 'XMLHttpRequest' },
            signal: request.signal
        }).then(function (response) {
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            var finalUrl = response.url || url;
            return response.text().then(function (html) {
                return { html: html, url: finalUrl };
            });
        }).then(function (result) {
            if (filterRequest !== request) return;
            if (request.signal.aborted) throw new Error('Query timed out');
            var doc = new DOMParser().parseFromString(result.html, 'text/html');
            if (!replacePageSection(doc, result.url, push, openMenu)) {
                throw new Error('Unexpected query response');
            }
            if (filterStatus) filterStatus('ready');
        }).catch(function () {
            if (filterRequest !== request) return;
            filterFailure = {url: url, push: push, menu: openMenu};
            if (filterStatus) filterStatus('failed');
        }).finally(function () {
            clearTimeout(timeout);
            if (filterRequest === request) filterRequest = null;
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
        if (document.querySelector('.rc-timeless')) return;
        var input = document.getElementById('rc-filter-input');
        var query = input ? input.value.trim().toLowerCase() : '';
        document.querySelectorAll('.rc-entry').forEach(function (entry) {
            var text = entry.textContent.toLowerCase();
            entry.style.display = (!query || text.indexOf(query) >= 0) ? '' : 'none';
        });
    }

    function allowsMotion() {
        return typeof Element.prototype.animate === 'function' &&
                !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    }

    function chipElements(section) {
        return Array.from(section.querySelectorAll('.rc-timeless .rc-chips > [data-filter-key]'));
    }

    function captureChips(section) {
        var chips = new Map();
        if (!allowsMotion()) return chips;
        chipElements(section).forEach(function (chip) {
            var bounds = chip.getBoundingClientRect();
            if (bounds.width) chips.set(chip.dataset.filterKey, { node: chip, bounds: bounds });
        });
        return chips;
    }

    function animateFilterResult(section, before) {
        var controls = section.querySelector('.rc-timeless');
        if (!controls || !allowsMotion()) return;
        var timing = { duration: 170, easing: 'cubic-bezier(0.22, 0.58, 0.12, 0.98)' };
        var positions = chipElements(section).map(function (chip) {
            return { chip: chip, bounds: chip.getBoundingClientRect() };
        });
        var root = controls.getBoundingClientRect();
        positions.forEach(function (item) {
            var chip = item.chip;
            var previous = before.get(chip.dataset.filterKey);
            before.delete(chip.dataset.filterKey);
            if (!item.bounds.width) return;
            if (!previous) {
                chip.animate([{ opacity: 0, transform: 'scale(.84)' }, { opacity: 1, transform: 'none' }], timing);
                return;
            }
            var x = previous.bounds.left - item.bounds.left;
            var y = previous.bounds.top - item.bounds.top;
            if (Math.abs(x) + Math.abs(y) > 1) {
                chip.animate([{ transform: 'translate(' + x + 'px, ' + y + 'px)' }, { transform: 'none' }], timing);
            }
        });
        var chips = controls.querySelector('.rc-chips');
        before.forEach(function (previous) {
            var ghost = previous.node.cloneNode(true);
            ghost.inert = true;
            ghost.setAttribute('aria-hidden', 'true');
            ghost.removeAttribute('data-filter-key');
            ghost.classList.add('rc-chip-leaving');
            ghost.style.left = (previous.bounds.left - root.left) + 'px';
            ghost.style.top = (previous.bounds.top - root.top) + 'px';
            ghost.style.width = previous.bounds.width + 'px';
            chips.appendChild(ghost);
            ghost.animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'scale(.84)' }], timing)
                .finished.then(function () { ghost.remove(); }, function () { ghost.remove(); });
        });
        var results = section.querySelector('#rc-days');
        if (results) results.animate([{ opacity: .76 }, { opacity: 1 }], timing);
    }

    function setFiltersVisible(visible, animate) {
        var activeFilters = document.getElementById('rc-active-filters');
        var button = document.getElementById('rc-filters-toggle');
        if (!activeFilters || !button) {
            return;
        }
        if (visible == null) visible = button.getAttribute('aria-expanded') === 'false' || activeFilters.hidden;
        button.setAttribute('aria-expanded', String(visible));
        button.textContent = !visible
                ? button.getAttribute('data-show')
                : button.getAttribute('data-hide');
        var start = activeFilters.getBoundingClientRect().height;
        var opacity = start ? getComputedStyle(activeFilters).opacity : 0;
        if (activeFilters.trayAnimation) activeFilters.trayAnimation.cancel();
        activeFilters.inert = !visible;
        if (!animate || !activeFilters.closest('.rc-timeless') || !allowsMotion()) {
            activeFilters.hidden = !visible;
            return;
        }
        activeFilters.hidden = false;
        var end = visible ? activeFilters.getBoundingClientRect().height : 0;
        var animation = activeFilters.animate([
            { height: start + 'px', opacity: opacity, minHeight: 0, overflow: 'hidden' },
            { height: end + 'px', opacity: visible ? 1 : 0, minHeight: 0, overflow: 'hidden' }
        ], { duration: 200, easing: 'cubic-bezier(0.22, 0.58, 0.12, 0.98)' });
        activeFilters.trayAnimation = animation;
        animation.finished.then(function () {
            if (activeFilters.trayAnimation !== animation) return;
            activeFilters.hidden = !visible;
            activeFilters.trayAnimation = null;
        }, function () {});
    }

    function savedContext() {
        var controls = document.querySelector('.rc-timeless');
        if (!controls) return null;
        var url = new URL(controls.dataset.filterUrl, location.href);
        return { url: url, key: 'wikikernel.rc.saved:' + url.pathname };
    }

    function readSavedFilters(context) {
        try {
            var saved = JSON.parse(localStorage.getItem(context.key) || '[]');
            if (Array.isArray(saved)) {
                return saved.filter(function (entry) {
                    return entry && typeof entry.name === 'string' && typeof entry.query === 'string';
                });
            }
        } catch (error) {
            // Disabled or malformed storage leaves the regular filters available.
        }
        return [];
    }

    function writeSavedFilters(context, saved) {
        var status = document.getElementById('rc-storage-status');
        try {
            localStorage.setItem(context.key, JSON.stringify(saved));
            status.hidden = true;
            renderSavedFilters();
            return true;
        } catch (error) {
            status.textContent = document.querySelector('.rc-timeless').dataset.storageError;
            status.hidden = false;
            return false;
        }
    }

    function renderSavedFilters() {
        var context = savedContext();
        if (!context) return;
        var list = document.getElementById('rc-saved-list');
        var template = document.getElementById('rc-saved-item');
        var saved = readSavedFilters(context);
        list.replaceChildren();
        list.hidden = !saved.length;
        document.getElementById('rc-saved-empty').hidden = Boolean(saved.length);
        saved.forEach(function (entry, index) {
            var row = template.content.cloneNode(true);
            var link = row.querySelector('a');
            var url = new URL(context.url);
            url.search = entry.query;
            link.href = url.href;
            link.textContent = entry.name;
            row.querySelector('button').addEventListener('click', function (event) {
                event.stopPropagation();
                var next = saved.slice();
                next.splice(index, 1);
                if (writeSavedFilters(context, next)) {
                    document.querySelector('#rc-saved-list button, #rc-saved > summary').focus();
                }
            });
            list.appendChild(row);
        });
    }

    function closeMenus(except) {
        document.querySelectorAll('.rc-timeless .rc-menu[open]').forEach(function (menu) {
            if (menu !== except) menu.open = false;
        });
    }

    function filterPicker(query) {
        var picker = document.getElementById('rc-filter-picker');
        if (!picker) return;
        query = query.trim().toLowerCase();
        var matches = 0;
        picker.querySelectorAll('li').forEach(function (item) {
            var match = item.textContent.toLowerCase().includes(query);
            item.hidden = !match;
            if (match) matches++;
        });
        picker.querySelectorAll('.rc-filter-category').forEach(function (category) {
            category.hidden = !category.querySelector('li:not([hidden])');
        });
        picker.querySelector('#rc-picker-empty').hidden = Boolean(matches);
        picker.open = true;
        closeMenus(picker);
    }

    function syncHighlightMode() {
        var toggle = document.getElementById('rc-highlight-toggle');
        if (!toggle) return;
        toggle.setAttribute('aria-pressed', String(highlightMode));
        document.querySelectorAll('.rc-filter-choice:not([aria-disabled="true"])').forEach(function (choice) {
            choice.href = highlightMode ? choice.dataset.highlightUrl : choice.dataset.normalUrl;
            choice.setAttribute('aria-checked', highlightMode ? choice.dataset.highlighted : choice.dataset.selected);
        });
    }

    // 事件委托：替换页面内容后不需要重新绑定每个新元素。
    document.addEventListener('input', function (event) {
        if (event.target && event.target.id === 'rc-filter-input') {
            if (document.getElementById('rc-filter-picker')) filterPicker(event.target.value);
            else applyTextFilter();
        }
    });

    document.addEventListener('focusin', function (event) {
        if (event.target.id === 'rc-filter-input') filterPicker(event.target.value);
    });

    document.addEventListener('submit', function (event) {
        if (event.target.id !== 'rc-save-form') return;
        event.preventDefault();
        var name = document.getElementById('rc-save-name').value.trim();
        if (!name) return;
        var context = savedContext();
        var saved = readSavedFilters(context).filter(function (entry) { return entry.name !== name; });
        saved.unshift({ name: name, query: context.url.search });
        if (writeSavedFilters(context, saved)) {
            event.target.reset();
            event.target.hidden = true;
            document.querySelector('#rc-saved-list a').focus();
        }
    });

    document.addEventListener('keydown', function (event) {
        if (event.key === 'ArrowDown' && event.target.id === 'rc-filter-input' &&
                document.getElementById('rc-filter-picker')) {
            event.preventDefault();
            filterPicker(event.target.value);
            var first = document.querySelector('#rc-filter-picker .rc-filter-category:not([hidden]) li:not([hidden]) a[href]');
            if (first) first.focus();
        }
        if (event.key === ' ' && event.target.matches('.rc-group-toggle, .rc-filter-choice[href]')) {
            event.preventDefault();
            event.target.click();
        }
        if (event.key !== 'Escape') return;
        var menu = document.querySelector('.rc-timeless .rc-menu[open]');
        if (menu) {
            event.preventDefault();
            menu.open = false;
            if (menu.contains(document.activeElement)) menu.querySelector('summary').focus();
        }
    });

    document.addEventListener('click', function (event) {
        var target = event.target;
        if (!target || !target.closest) {
            return;
        }
        var menu = target.closest('.rc-timeless .rc-menu');
        if (menu && target.closest('summary') === menu.querySelector('summary')) closeMenus(menu);
        if (!menu && !target.closest('#rc-filter-input')) closeMenus();
        if (target.closest('#rc-filter-input') && document.getElementById('rc-filter-picker')) {
            filterPicker(target.value);
            return;
        }
        if (target.closest('#rc-highlight-toggle')) {
            highlightMode = !highlightMode;
            syncHighlightMode();
            return;
        }
        if (target.closest('#rc-save-filter')) {
            var savedMenu = document.getElementById('rc-saved');
            closeMenus(savedMenu);
            savedMenu.open = true;
            document.getElementById('rc-save-form').hidden = false;
            document.getElementById('rc-save-name').focus();
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
            setFiltersVisible(null, true);
            return;
        }
        var filterLink = target.closest('.rc-filters a, .rc-toolbar .rc-menu a, .rc-saved-link');
        if (filterLink && filterLink.getAttribute('href') && !event.ctrlKey && !event.metaKey &&
                !event.shiftKey && !event.altKey) {
            event.preventDefault();
            var openMenu = filterLink.closest('.rc-range-panel') ? '#rc-range' : null;
            if (filterLink.closest('.rc-picker')) openMenu = '#rc-filter-picker';
            if (menu && !openMenu) menu.open = false;
            loadFilter(filterLink.href, true, openMenu);
        }
    });

    document.addEventListener('visibilitychange', function () {
        if (!document.hidden && liveTimer) {
            poll();
        }
    });

    window.addEventListener('popstate', function () {
        if (document.querySelector('.mw-rc-page')) {
            loadFilter(window.location.href, false);
        }
    });

    // 初次加载保持按钮状态（默认停止），并确保服务端渲染的按钮样式正确。
    syncLiveState();
    renderSavedFilters();
})();
