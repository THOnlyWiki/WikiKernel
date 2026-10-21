/*
 * Flow 风格评论区前端：
 *  - 默认自动显示；页面可用 <comment disabled /> 关闭，或用 <comment /> 手动开启
 *  - 发表 / 回复 / 删除都只刷新评论区，并保留顶部输入框里的草稿
 *  - 回复使用就地 composer（服务端输出 <template id="comment-composer-template">）
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    function root() {
        return document.querySelector('.wiki-comments');
    }

    function apiBase() {
        var section = root();
        return (section && section.getAttribute('data-api')) || '/api/comments';
    }

    function pageTitle() {
        var section = root();
        return (section && section.getAttribute('data-page')) || '';
    }

    function formOf(element) {
        return element && element.closest ? element.closest('.comment-form') : null;
    }

    function showMessage(form, text, success) {
        var box = form ? form.querySelector('.form-message') : null;
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    function resetReply(form) {
        if (!form) {
            return;
        }
        var parentInput = form.querySelector('input[name="parentId"]');
        if (parentInput) {
            parentInput.value = '';
        }
        var note = form.querySelector('.comment-reply-note');
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
     * clearDraft=true（发表成功）时不恢复顶部输入框；否则（删除）恢复草稿与回复状态。
     */
    function refreshComments(newId, successText, clearDraft) {
        var current = root();
        if (!current) {
            return;
        }
        var topForm = current.querySelector('.comment-form:not(.comment-inline-form)');
        var draft = topForm && topForm.querySelector('textarea')
            ? topForm.querySelector('textarea').value : '';

        fetch(window.location.href, {
            headers: { 'Accept': 'text/html', 'X-Requested-With': 'XMLHttpRequest' }
        }).then(function (response) {
            return response.text();
        }).then(function (html) {
            var doc = new DOMParser().parseFromString(html, 'text/html');
            var fresh = doc.querySelector('.wiki-comments');
            if (!fresh) {
                window.location.reload();
                return;
            }
            current.classList.add('wiki-comments-fading');
            window.setTimeout(function () {
                current.replaceWith(fresh);
                fresh.classList.add('wiki-comments-fading');
                window.setTimeout(function () {
                    fresh.classList.remove('wiki-comments-fading');
                }, 30);
                if (!clearDraft) {
                    var freshForm = fresh.querySelector('.comment-form:not(.comment-inline-form)');
                    var freshTextarea = freshForm && freshForm.querySelector('textarea');
                    if (freshTextarea) {
                        freshTextarea.value = draft;
                    }
                }
                if (successText) {
                    var box = fresh.querySelector('.comment-form:not(.comment-inline-form) .form-message');
                    if (box) {
                        box.textContent = successText;
                        box.className = 'form-message success';
                        box.hidden = false;
                    }
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
            }, 120);
        }).catch(function () {
            window.location.reload();
        });
    }

    function scrollBehavior() {
        if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            return 'auto';
        }
        return 'smooth';
    }

    function insertInlineComposer(reply) {
        var item = reply.closest('.comment-item');
        var template = document.getElementById('comment-composer-template');
        if (!item || !template || !template.content) {
            return false;
        }
        var main = item.querySelector(':scope > .comment-main');
        if (!main) {
            return false;
        }
        var existing = main.querySelector(':scope > .comment-inline-form');
        if (existing) {
            existing.remove();
        }
        var composer = template.content.firstElementChild.cloneNode(true);
        var parent = composer.querySelector('input[name="parentId"]');
        if (parent) {
            parent.value = reply.getAttribute('data-id') || '';
        }
        var name = composer.querySelector('.comment-reply-name');
        if (name) {
            name.textContent = reply.getAttribute('data-author') || '';
        }
        var note = composer.querySelector('.comment-reply-note');
        if (note) {
            note.hidden = false;
        }
        var actions = main.querySelector(':scope > .comment-actions');
        if (actions) {
            actions.insertAdjacentElement('afterend', composer);
        } else {
            main.appendChild(composer);
        }
        var textarea = composer.querySelector('textarea');
        if (textarea) {
            textarea.focus();
            if (textarea.scrollIntoView) {
                textarea.scrollIntoView({ block: 'center', behavior: scrollBehavior() });
            }
        }
        return true;
    }

    document.addEventListener('click', function (event) {
        var target = event.target;
        if (!target || !target.closest) {
            return;
        }
        var reply = target.closest('.comment-reply-link');
        if (reply) {
            if (!insertInlineComposer(reply)) {
                var top = root() && root().querySelector('.comment-form:not(.comment-inline-form)');
                if (!top) {
                    return;
                }
                var parentInput = top.querySelector('input[name="parentId"]');
                if (parentInput) {
                    parentInput.value = reply.getAttribute('data-id') || '';
                }
                var note = top.querySelector('.comment-reply-note');
                if (note) {
                    note.hidden = false;
                }
                var name = top.querySelector('.comment-reply-name');
                if (name) {
                    name.textContent = reply.getAttribute('data-author') || '';
                }
                var textarea = top.querySelector('textarea');
                if (textarea) {
                    textarea.focus();
                }
            }
            return;
        }

        if (target.closest('.comment-reply-cancel')) {
            var current = formOf(target);
            if (current && current.classList.contains('comment-inline-form')) {
                current.remove();
            } else {
                resetReply(current);
            }
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
        var current = formOf(event.target);
        if (!current) {
            return;
        }
        event.preventDefault();
        var textarea = current.querySelector('textarea');
        var content = textarea ? textarea.value.trim() : '';
        if (!content) {
            showMessage(current, MESSAGES.commentEmpty);
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
                if (textarea) {
                    textarea.value = '';
                }
                refreshComments(result.data && result.data.id,
                    result.message || MESSAGES.commentPosted, true);
                return;
            }
            showMessage(current, (result && result.message) || MESSAGES.operationFailed || '操作失败，请稍后重试');
        }).catch(function () {
            if (button) {
                button.disabled = false;
            }
            showMessage(current, MESSAGES.networkError || '网络错误，请稍后重试');
        });
    });
})();
