/*
 * 评论区前端（页面含 comment 标签时由 view.html 渲染）：
 *  - 发表：拦截 .comment-form 提交到 POST /api/comments；成功后立即清空输入框，
 *    用 Ajax 拉取当前页面并只替换评论区（不整页刷新，浏览器也就不会恢复输入内容），新评论高亮定位
 *  - 回复：.comment-reply-link 设置表单 parentId 并显示「正在回复」
 *  - 删除：.comment-delete-link 调 POST /api/comments/delete，同样只刷新评论区（保留已输入的草稿）
 * 事件都委托在 document 上，替换评论区后无需重新绑定。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    function root() {
        return document.querySelector('.wiki-comments');
    }

    function form() {
        var section = root();
        return section ? section.querySelector('.comment-form') : null;
    }

    function apiBase() {
        var section = root();
        return (section && section.getAttribute('data-api')) || '/api/comments';
    }

    function pageTitle() {
        var section = root();
        return (section && section.getAttribute('data-page')) || '';
    }

    function showMessage(text, success) {
        var current = form();
        var box = current ? current.querySelector('.form-message') : null;
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    function resetReply() {
        var current = form();
        if (!current) {
            return;
        }
        var parentInput = current.querySelector('input[name="parentId"]');
        if (parentInput) {
            parentInput.value = '';
        }
        var note = current.querySelector('.comment-reply-note');
        if (note) {
            note.hidden = true;
        }
    }

    function post(url, data) {
        var body = new URLSearchParams();
        Object.keys(data).forEach(function (key) {
            body.append(key, data[key]);
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

    /**
     * 只替换评论区（拉当前页面 HTML），成功后显示提示并高亮新评论。
     * {@code clearDraft=true}（发表成功）时不恢复输入框；否则（删除评论）恢复草稿与回复状态。
     */
    function refreshComments(newId, successText, clearDraft) {
        var current = root();
        if (!current) {
            return;
        }
        // 替换前保存当前草稿与回复状态
        var draft = '';
        var parentId = '';
        var replyNameText = '';
        var replyVisible = false;
        var textarea = current.querySelector('.comment-form textarea');
        if (textarea) {
            draft = textarea.value;
        }
        var parentInput = current.querySelector('.comment-form input[name="parentId"]');
        if (parentInput) {
            parentId = parentInput.value;
        }
        var nameEl = current.querySelector('.comment-reply-name');
        if (nameEl) {
            replyNameText = nameEl.textContent;
        }
        var noteEl = current.querySelector('.comment-reply-note');
        if (noteEl) {
            replyVisible = !noteEl.hidden;
        }

        fetch(window.location.href, {
            headers: { 'Accept': 'text/html', 'X-Requested-With': 'XMLHttpRequest' }
        }).then(function (response) {
            return response.text();
        }).then(function (html) {
            var doc = new DOMParser().parseFromString(html, 'text/html');
            var fresh = doc.querySelector('.wiki-comments');
            if (!fresh) {
                return;
            }
            // 先淡出旧评论区，替换后再淡入，避免整块生硬闪替
            current.classList.add('wiki-comments-fading');
            window.setTimeout(function () {
                current.replaceWith(fresh);
                fresh.classList.add('wiki-comments-fading');
                // 下一拍去掉 class → CSS 过渡淡入（用 setTimeout 而不是 rAF：后台标签页 rAF 会被节流）
                window.setTimeout(function () {
                    fresh.classList.remove('wiki-comments-fading');
                }, 30);
                if (!clearDraft) {
                    // 恢复草稿与回复状态（删除评论时用户可能已经写了一半）
                    var freshTextarea = fresh.querySelector('.comment-form textarea');
                    if (freshTextarea) {
                        freshTextarea.value = draft;
                    }
                    var freshParent = fresh.querySelector('.comment-form input[name="parentId"]');
                    if (freshParent) {
                        freshParent.value = parentId;
                    }
                    var freshName = fresh.querySelector('.comment-reply-name');
                    if (freshName) {
                        freshName.textContent = replyNameText;
                    }
                    var freshNote = fresh.querySelector('.comment-reply-note');
                    if (freshNote) {
                        freshNote.hidden = !replyVisible;
                    }
                }
                if (successText) {
                    showMessage(successText, true);
                }
                if (newId) {
                    var item = document.querySelector('.comment-item[data-id="' + newId + '"]');
                    if (item) {
                        item.classList.add('comment-new');
                        if (item.scrollIntoView) {
                            item.scrollIntoView({ block: 'center', behavior: scrollBehavior() });
                        }
                    }
                }
            }, 150);
        }).catch(function () {
            // 拉取失败就退回整页刷新
            window.location.reload();
        });
    }

    /** 平滑滚动（尊重 prefers-reduced-motion）。 */
    function scrollBehavior() {
        if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            return 'auto';
        }
        return 'smooth';
    }

    document.addEventListener('click', function (event) {
        var target = event.target;
        if (!target || !target.closest) {
            return;
        }
        var reply = target.closest('.comment-reply-link');
        if (reply) {
            var current = form();
            if (!current) {
                return;
            }
            var parentInput = current.querySelector('input[name="parentId"]');
            if (parentInput) {
                parentInput.value = reply.getAttribute('data-id');
            }
            var name = current.querySelector('.comment-reply-name');
            if (name) {
                name.textContent = reply.getAttribute('data-author') || '';
            }
            var note = current.querySelector('.comment-reply-note');
            if (note) {
                note.hidden = false;
            }
            var textarea = current.querySelector('textarea');
            if (textarea) {
                textarea.focus();
            }
            return;
        }
        if (target.closest('.comment-reply-cancel')) {
            resetReply();
            return;
        }
        var del = target.closest('.comment-delete-link');
        if (del) {
            if (!window.confirm(MESSAGES.commentDeleteConfirm)) {
                return;
            }
            del.disabled = true;
            post(apiBase() + '/delete', { id: del.getAttribute('data-id') }).then(function (result) {
                if (result && result.code === 200) {
                    // 删除也只刷新评论区：输入框里未发表的草稿会保留
                    refreshComments(null, result.message || MESSAGES.commentDeleted, false);
                    return;
                }
                window.alert((result && result.message) || MESSAGES.operationFailed || '操作失败，请稍后重试');
                del.disabled = false;
            }).catch(function () {
                window.alert(MESSAGES.networkError || '网络错误，请稍后重试');
                del.disabled = false;
            });
        }
    });

    document.addEventListener('submit', function (event) {
        var current = event.target && event.target.closest ? event.target.closest('.comment-form') : null;
        if (!current) {
            return;
        }
        event.preventDefault();
        var textarea = current.querySelector('textarea');
        var content = textarea ? textarea.value.trim() : '';
        if (!content) {
            showMessage(MESSAGES.commentEmpty);
            return;
        }
        var parentInput = current.querySelector('input[name="parentId"]');
        var button = current.querySelector('button[type="submit"]');
        if (button) {
            button.disabled = true;
        }
        post(apiBase(), {
            page: pageTitle(),
            content: content,
            parentId: parentInput ? parentInput.value : ''
        }).then(function (result) {
            if (button) {
                button.disabled = false;
            }
            if (result && result.code === 200) {
                // 立刻清空输入框（不整页刷新，避免浏览器恢复表单内容）
                if (textarea) {
                    textarea.value = '';
                }
                resetReply();
                refreshComments(result.data && result.data.id,
                    result.message || MESSAGES.commentPosted, true);
                return;
            }
            showMessage((result && result.message) || MESSAGES.operationFailed || '操作失败，请稍后重试');
        }).catch(function () {
            if (button) {
                button.disabled = false;
            }
            showMessage(MESSAGES.networkError || '网络错误，请稍后重试');
        });
    });
})();
