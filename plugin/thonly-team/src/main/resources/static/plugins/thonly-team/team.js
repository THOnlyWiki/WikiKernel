/*
 * 组队跑展前端：
 *  - 页面里放 <team year="2026" event="199" /> 就渲染一块组队区
 *  - event 省略 = 整年总览，只能看和加入，不能发起
 *  - 联系方式由服务端控制：未加入时接口根本不返回，这里也不用藏
 */
(function () {
    'use strict';

    var TYPE_LABELS = { car: '拼车', room: '拼房', company: '同行', booth: '摊位', shoot: '拍摄' };
    var TYPE_OPTIONS = ['company', 'car', 'room', 'booth', 'shoot'];

    function esc(text) {
        return String(text == null ? '' : text)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function typeLabel(type) {
        return TYPE_LABELS[type] || '同行';
    }

    function get(url) {
        return fetch(url, {
            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
        }).then(function (response) {
            return response.json();
        });
    }

    function post(url, data) {
        var body = new URLSearchParams();
        Object.keys(data || {}).forEach(function (key) {
            body.append(key, data[key] == null ? '' : data[key]);
        });
        return fetch(url, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/x-www-form-urlencoded',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: body.toString()
        }).then(function (response) {
            return response.json();
        });
    }

    function close(mask) {
        if (mask && mask.parentNode) {
            mask.parentNode.removeChild(mask);
        }
    }

    /** 通用弹层：buttons = [{label, primary, danger, onClick}] */
    function dialog(title, sub, bodyHtml, buttons) {
        var mask = document.createElement('div');
        mask.className = 'team-mask';
        mask.innerHTML = '<div class="team-dialog">'
            + '<h3>' + esc(title) + '</h3>'
            + '<div class="team-dialog-sub">' + esc(sub || '') + '</div>'
            + '<div class="team-dialog-body">' + bodyHtml + '</div>'
            + '<div class="team-dialog-actions"></div>'
            + '</div>';
        var actions = mask.querySelector('.team-dialog-actions');
        (buttons || []).forEach(function (spec) {
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'team-btn'
                + (spec.primary ? ' team-btn-primary' : '')
                + (spec.danger ? ' team-btn-danger' : '');
            button.textContent = spec.label;
            button.addEventListener('click', function () {
                spec.onClick(mask);
            });
            actions.appendChild(button);
        });
        mask.addEventListener('click', function (event) {
            if (event.target === mask) {
                close(mask);
            }
        });
        document.body.appendChild(mask);
        return mask;
    }

    function message(mask, text, ok) {
        var box = mask.querySelector('.team-message');
        if (!box) {
            box = document.createElement('p');
            box.className = 'team-message';
            mask.querySelector('.team-dialog-body').appendChild(box);
        }
        box.textContent = text;
        box.className = 'team-message' + (ok ? ' ok' : '');
    }

    function setup(root) {
        var base = root.getAttribute('data-api') || '/api/thonly-team';
        var year = root.getAttribute('data-year') || '';
        var eventId = parseInt(root.getAttribute('data-event') || '0', 10) || 0;
        var eventName = root.getAttribute('data-event-name') || '';

        root.innerHTML = '<div class="team-toolbar">'
            + '<span class="team-count"></span>'
            + '<span class="team-actions"></span>'
            + '</div>'
            + '<div class="team-list"></div>'
            + '<p class="team-safety">仅限同好交流，线下见面请注意人身安全，尽量选人多的地方集合。'
            + '遇到骚扰或可疑内容，点开队伍后有举报入口。</p>';

        var countEl = root.querySelector('.team-count');
        var actionsEl = root.querySelector('.team-actions');
        var listEl = root.querySelector('.team-list');

        if (eventId > 0) {
            var createButton = document.createElement('button');
            createButton.type = 'button';
            createButton.className = 'team-btn team-btn-primary';
            createButton.textContent = '发起组队';
            createButton.addEventListener('click', function () {
                openCreate();
            });
            actionsEl.appendChild(createButton);
        }

        function load() {
            listEl.innerHTML = '<div class="team-empty">加载中…</div>';
            get(base + '/list?year=' + encodeURIComponent(year) + '&eventId=' + eventId)
                .then(function (result) {
                    if (result.code !== 200) {
                        listEl.innerHTML = '<div class="team-empty">'
                            + esc(result.message || '加载失败') + '</div>';
                        return;
                    }
                    render((result.data && result.data.teams) || []);
                })
                .catch(function () {
                    listEl.innerHTML = '<div class="team-empty">网络异常，稍后再试</div>';
                });
        }

        function render(teams) {
            countEl.textContent = teams.length ? '共 ' + teams.length + ' 支队伍' : '';
            if (!teams.length) {
                listEl.innerHTML = '<div class="team-empty">还没有人组队'
                    + (eventId > 0 ? '，来做第一个' : '') + '</div>';
                return;
            }
            listEl.innerHTML = '';
            teams.forEach(function (team) {
                var card = document.createElement('article');
                card.className = 'team-card';
                card.innerHTML = cardHtml(team);
                card.addEventListener('click', function () {
                    openDetail(team.id);
                });
                listEl.appendChild(card);
            });
        }

        function cardHtml(team) {
            var meta = '团长 ' + esc(team.ownerName || '—')
                + ' · ' + team.joined + '/' + team.capacity + ' 人';
            if (team.joinMode === 'approve') {
                meta += ' · 需要批准';
            }
            var flags = [];
            if (team.status === 'closed') {
                flags.push('已关闭');
            } else if (team.remaining === 0) {
                flags.push('已满员');
            }
            return '<div class="team-card-head">'
                + '<span class="team-type">' + esc(typeLabel(team.type)) + '</span>'
                + '<h3 class="team-title">' + esc(team.title) + '</h3>'
                + (flags.length ? '<span class="team-flag">' + flags.join(' · ') + '</span>' : '')
                + '</div>'
                + '<p class="team-meta">' + meta + '</p>'
                + (team.note ? '<p class="team-note">' + esc(team.note) + '</p>' : '');
        }

        function openDetail(id) {
            get(base + '/detail?id=' + encodeURIComponent(id))
                .then(function (result) {
                    if (result.code !== 200) {
                        window.alert(result.message || '打不开这支队伍');
                        return;
                    }
                    showDetail(result.data);
                })
                .catch(function () {
                    window.alert('网络异常，稍后再试');
                });
        }

        function showDetail(data) {
            var team = data.team;
            var body = '<p class="team-meta">' + esc(typeLabel(team.type))
                + ' · 团长 ' + esc(team.ownerName || '—')
                + ' · ' + team.joined + '/' + team.capacity + ' 人'
                + (team.joinMode === 'approve' ? ' · 需要批准' : ' · 立即进入')
                + '</p>';

            if (team.note) {
                body += '<p class="team-note">' + esc(team.note) + '</p>';
            }

            var members = data.members || [];
            if (members.length) {
                body += '<ul class="team-members">';
                members.forEach(function (member) {
                    var isOwner = member.role === 'owner';
                    body += '<li class="' + (isOwner ? 'owner' : '') + '">'
                        + esc(member.userName || '匿名')
                        + (isOwner ? '（团长）' : '')
                        + (member.status === 'pending' ? '（待批准）' : '')
                        + '</li>';
                });
                body += '</ul>';
            }

            if (data.contactVisible && data.contact) {
                body += '<div class="team-contact"><span class="team-contact-label">联系方式</span>'
                    + esc(data.contact) + '</div>';
            } else {
                body += '<p class="team-hint" style="font-size:11px;color:#aaa;margin-top:10px;">'
                    + '加入之后才能看到联系方式。</p>';
            }

            var buttons = [];
            if (!data.joined && !data.isOwner && team.status === 'open' && team.remaining > 0) {
                buttons.push({
                    label: team.joinMode === 'approve' ? '申请加入' : '加入',
                    primary: true,
                    onClick: function (mask) {
                        post(base + '/join', { id: team.id }).then(function (result) {
                            if (result.code !== 200) {
                                message(mask, result.message || '加入失败');
                                return;
                            }
                            close(mask);
                            load();
                        });
                    }
                });
            }
            if (data.joined && !data.isOwner) {
                buttons.push({
                    label: '退出',
                    onClick: function (mask) {
                        post(base + '/leave', { id: team.id }).then(function (result) {
                            if (result.code !== 200) {
                                message(mask, result.message || '退出失败');
                                return;
                            }
                            close(mask);
                            load();
                        });
                    }
                });
            }
            if (data.isOwner) {
                buttons.push({
                    label: '关闭队伍',
                    onClick: function (mask) {
                        post(base + '/close', { id: team.id }).then(function (result) {
                            if (result.code !== 200) {
                                message(mask, result.message || '操作失败');
                                return;
                            }
                            close(mask);
                            load();
                        });
                    }
                });
            }
            if (!data.isOwner) {
                buttons.push({
                    label: '举报',
                    danger: true,
                    onClick: function (mask) {
                        close(mask);
                        openReport(team);
                    }
                });
            }
            buttons.push({ label: '关闭', onClick: close });

            dialog(team.title, team.eventName || '', body, buttons);
        }

        function openReport(team) {
            var mask = dialog('举报这支队伍', team.title,
                '<div class="team-field"><label>理由</label>'
                + '<textarea class="team-reason" maxlength="200" '
                + 'placeholder="比如：广告、引流、内容不当、骚扰"></textarea></div>',
                [
                    {
                        label: '提交举报', primary: true, danger: true,
                        onClick: function (dialogMask) {
                            var reason = dialogMask.querySelector('.team-reason').value.trim();
                            if (!reason) {
                                message(dialogMask, '请填写理由');
                                return;
                            }
                            post(base + '/report', { id: team.id, reason: reason })
                                .then(function (result) {
                                    if (result.code !== 200) {
                                        message(dialogMask, result.message || '提交失败');
                                        return;
                                    }
                                    message(dialogMask, result.message || '已提交', true);
                                    window.setTimeout(function () {
                                        close(dialogMask);
                                    }, 900);
                                });
                        }
                    },
                    { label: '取消', onClick: close }
                ]);
            return mask;
        }

        function openCreate() {
            var typeOptions = TYPE_OPTIONS.map(function (value) {
                return '<option value="' + value + '">' + esc(typeLabel(value)) + '</option>';
            }).join('');

            var body = '<div class="team-field"><label>标题</label>'
                + '<input class="team-title-input" maxlength="60" '
                + 'placeholder="比如：10.2 潍坊 THP 拼车，济南出发"></div>'
                + '<div class="team-field"><label>类型</label>'
                + '<select class="team-type-input">' + typeOptions + '</select></div>'
                + '<div class="team-field"><label>计划人数（含自己）</label>'
                + '<input class="team-capacity-input" type="number" min="2" max="30" value="2"></div>'
                + '<div class="team-field"><label>加入方式</label>'
                + '<select class="team-joinmode-input">'
                + '<option value="approve">需要我批准</option>'
                + '<option value="instant">立即进入</option>'
                + '</select></div>'
                + '<div class="team-field"><label>联系方式</label>'
                + '<input class="team-contact-input" maxlength="120" placeholder="QQ 号或群号">'
                + '<div class="team-hint">只有加入成功的人能看到，公开列表里不会出现。</div></div>'
                + '<div class="team-field"><label>说明（可选）</label>'
                + '<textarea class="team-note-input" maxlength="500" '
                + 'placeholder="出发地、时间、预算、住宿要求…"></textarea></div>';

            dialog('发起组队', eventName, body, [
                {
                    label: '发布', primary: true,
                    onClick: function (mask) {
                        var title = mask.querySelector('.team-title-input').value.trim();
                        var contact = mask.querySelector('.team-contact-input').value.trim();
                        if (!title) {
                            message(mask, '请填写标题');
                            return;
                        }
                        if (!contact) {
                            message(mask, '请填写联系方式');
                            return;
                        }
                        post(base + '/create', {
                            eventYear: year,
                            eventId: eventId,
                            eventName: eventName,
                            type: mask.querySelector('.team-type-input').value,
                            title: title,
                            capacity: mask.querySelector('.team-capacity-input').value,
                            joinMode: mask.querySelector('.team-joinmode-input').value,
                            contact: contact,
                            note: mask.querySelector('.team-note-input').value.trim()
                        }).then(function (result) {
                            if (result.code !== 200) {
                                message(mask, result.message || '发布失败');
                                return;
                            }
                            close(mask);
                            load();
                        });
                    }
                },
                { label: '取消', onClick: close }
            ]);
        }

        load();
    }

    function init() {
        var roots = document.querySelectorAll('.wiki-team');
        for (var i = 0; i < roots.length; i++) {
            if (!roots[i].getAttribute('data-ready')) {
                roots[i].setAttribute('data-ready', '1');
                setup(roots[i]);
            }
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
