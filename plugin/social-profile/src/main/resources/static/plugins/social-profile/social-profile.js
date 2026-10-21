/*
 * 插件 social-profile：SocialProfile 的前端逻辑（站点级注入，只在需要的页面生效）
 *  - 所有带 action="/api/social-profile/..." 的表单：AJAX 提交 + 结果提示（失败时显示服务端文案）
 *  - 留言板：回复按钮（把 parent 写进表单）/ 删除按钮
 *  - 好友 / 仇敌：按钮 data-friend-add / data-user / data-id
 *  - 礼物：删除按钮
 *  - 奖励：撤销按钮
 * 文案统一走 window.WIKI_MESSAGES（由插件的 HtmlHeadProvider 注入，绝不写死中文）
 *
 * 注意：**不再**把面板里的 .sp-card-actions 搬进资料卡（原 moveCardActions）——资料卡只放 core
 * 自己的东西（头像 / 用户名 / 用户组徽章 / 等级积分 / 贡献链接），社交入口（留言板 / 加好友 /
 * 送礼物 / 发私信 / 编辑资料 / 上传头像）只在资料卡下方的面板里出现一次，避免社交入口重复两处。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var API_PREFIX = '/api/social-profile/';

    /** 在表单 / 表格附近显示一条消息。 */
    function showMessage(form, text, success) {
        var box = form ? form.querySelector('.form-message') : null;
        if (!box) {
            if (!success) {
                window.alert(text);
            }
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    /** 异步表单提交：解析 JSON 信封 {success, message, data}。 */
    function submitForm(form, reloadOnSuccess) {
        return fetch(form.action, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: new FormData(form)
        }).then(function (response) {
            return response.json();
        }).then(function (result) {
            var success = !!(result && result.success);
            showMessage(form, (result && result.message) || (success
                ? MESSAGES.operationSuccess : MESSAGES.spOperationFailed), success);
            if (success) {
                if (reloadOnSuccess) {
                    window.location.reload();
                    return;
                }
                form.reset();
                var parent = form.querySelector('input[name="parent"]');
                if (parent) {
                    parent.value = '';
                }
                refreshBoard();
            }
        }).catch(function () {
            showMessage(form, MESSAGES.networkError || MESSAGES.spOperationFailed, false);
        });
    }

    /** 留言板提交成功后刷新页面上的留言列表（整页刷新最稳：wikitext 渲染在服务端）。 */
    function refreshBoard() {
        if (document.getElementById('sp-board') || document.querySelector('.sp-board-form')) {
            window.location.reload();
        }
    }

    /** 简单的 POST（按钮类操作）：可选确认文案。 */
    function post(url, body, confirmText) {
        if (confirmText && !window.confirm(confirmText)) {
            return Promise.resolve(false);
        }
        var data = new FormData();
        Object.keys(body || {}).forEach(function (key) {
            data.append(key, body[key]);
        });
        return fetch(url, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: data
        }).then(function (response) {
            return response.json();
        }).then(function (result) {
            var success = !!(result && result.success);
            if (!success) {
                window.alert((result && result.message) || MESSAGES.spOperationFailed);
                return false;
            }
            if (result.message) {
                showToast(result.message);
            }
            return true;
        }).catch(function () {
            window.alert(MESSAGES.networkError || MESSAGES.spOperationFailed);
            return false;
        });
    }

    /** 页面右上角的临时提示（成功操作使用；2.5 秒后自动消失）。 */
    function showToast(text) {
        var toast = document.getElementById('sp-toast');
        if (!toast) {
            toast = document.createElement('div');
            toast.id = 'sp-toast';
            toast.className = 'sp-toast';
            document.body.appendChild(toast);
        }
        toast.textContent = text;
        toast.classList.add('sp-toast-visible');
        window.setTimeout(function () {
            toast.classList.remove('sp-toast-visible');
        }, 2500);
    }

    // ==================== 用户页资料卡 ====================
    // 资料卡里没有社交入口：操作按钮行就在资料卡下方的 .wiki-profile-panel 里，脚本不需要搬家

    // ==================== 表单提交 ====================

    Array.prototype.forEach.call(document.querySelectorAll('form[action^="' + API_PREFIX + '"]'), function (form) {
        // 按钮类操作（好友 / 仇敌 / 删除等）走各自的按钮处理器，不受表单提交影响
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            submitForm(form, form.hasAttribute('data-reload'));
        });
    });

    // 社交偏好表单（参数设置分区里注入的，action 由 data-api 指定）
    Array.prototype.forEach.call(document.querySelectorAll('form[data-api^="' + API_PREFIX + '"]'), function (form) {
        form.action = form.getAttribute('data-api');
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            var data = new FormData();
            Array.prototype.forEach.call(form.querySelectorAll('input[type="checkbox"]'), function (box) {
                data.append(box.name, box.checked ? '1' : '0');
            });
            fetch(form.action, {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: data
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                showMessage(form, (result && result.message) || MESSAGES.spPreferencesSaved,
                    !!(result && result.success));
            }).catch(function () {
                showMessage(form, MESSAGES.networkError || MESSAGES.spOperationFailed, false);
            });
        });
    });

    // ==================== 留言板 ====================

    Array.prototype.forEach.call(document.querySelectorAll('.sp-reply-button'), function (button) {
        button.addEventListener('click', function () {
            var form = document.querySelector('.sp-board-form');
            if (!form) {
                return;
            }
            var parent = form.querySelector('input[name="parent"]');
            if (parent) {
                parent.value = button.getAttribute('data-parent') || '';
            }
            var textarea = form.querySelector('textarea');
            if (textarea) {
                textarea.focus();
                textarea.scrollIntoView({ block: 'center' });
            }
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.sp-delete-button'), function (button) {
        button.addEventListener('click', function () {
            post(API_PREFIX + 'board/delete', { id: button.getAttribute('data-id') },
                MESSAGES.spBoardDeleteConfirm).then(function (done) {
                if (done) {
                    window.location.reload();
                }
            });
        });
    });

    // ==================== 好友 / 仇敌 ====================

    // 用户页资料卡上的「加为好友」按钮（POST friend/request，成功后整页刷新以更新按钮与关系状态）
    Array.prototype.forEach.call(document.querySelectorAll('[data-friend-add]'), function (button) {
        button.addEventListener('click', function () {
            post(API_PREFIX + 'friend/request', { user: button.getAttribute('data-friend-add') })
                .then(function (done) {
                    if (done) {
                        window.location.reload();
                    }
                });
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.sp-friend-accept'), function (button) {
        button.addEventListener('click', function () {
            post(API_PREFIX + 'friend/accept', { id: button.getAttribute('data-id') }).then(function (done) {
                if (done) {
                    window.location.reload();
                }
            });
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.sp-friend-reject'), function (button) {
        button.addEventListener('click', function () {
            post(API_PREFIX + 'friend/reject', { id: button.getAttribute('data-id') }).then(function (done) {
                if (done) {
                    window.location.reload();
                }
            });
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.sp-friend-remove'), function (button) {
        button.addEventListener('click', function () {
            post(API_PREFIX + 'friend/remove', { user: button.getAttribute('data-user') },
                MESSAGES.spFriendRemoveConfirm).then(function (done) {
                if (done) {
                    window.location.reload();
                }
            });
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.sp-foe-add'), function (button) {
        button.addEventListener('click', function () {
            post(API_PREFIX + 'foe/add', { user: button.getAttribute('data-user') }).then(function (done) {
                if (done) {
                    window.location.reload();
                }
            });
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.sp-foe-remove'), function (button) {
        button.addEventListener('click', function () {
            post(API_PREFIX + 'foe/remove', { user: button.getAttribute('data-user') }).then(function (done) {
                if (done) {
                    window.location.reload();
                }
            });
        });
    });

    // ==================== 礼物 / 奖励 ====================

    Array.prototype.forEach.call(document.querySelectorAll('.sp-gift-delete'), function (button) {
        button.addEventListener('click', function () {
            post(API_PREFIX + 'gift/delete', { id: button.getAttribute('data-id') },
                MESSAGES.spGiftDeleteConfirm).then(function (done) {
                if (done) {
                    window.location.reload();
                }
            });
        });
    });

    Array.prototype.forEach.call(document.querySelectorAll('.sp-award-revoke'), function (button) {
        button.addEventListener('click', function () {
            post(API_PREFIX + 'award/revoke', { id: button.getAttribute('data-id') },
                MESSAGES.spAwardRevokeConfirm).then(function (done) {
                if (done) {
                    window.location.reload();
                }
            });
        });
    });
})();
