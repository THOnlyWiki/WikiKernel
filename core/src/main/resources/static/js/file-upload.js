/*
 * 文件功能前端：
 *  - #file-upload-form：multipart 提交到 /api/files/upload，本地即时预览选中的图片
 *  - .file-delete：POST /api/files/delete（勾选「彻底删除」时不进回收站）
 *  - .file-purge：POST /api/files/delete + hard=true（已删除文件列表里彻底删除）
 *  - .file-restore：POST /api/files/restore（恢复软删除文件）
 *  - .file-revert：POST /api/files/revert（回滚到历史版本）
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};

    function showMessage(text, success) {
        var box = document.getElementById('file-message');
        if (!box) {
            return;
        }
        box.textContent = text;
        box.className = 'form-message' + (success ? ' success' : '');
        box.hidden = false;
    }

    function post(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: body
        }).then(function (response) {
            return response.json();
        });
    }

    function onError(result, button) {
        showMessage((result && result.message) || MESSAGES.operationFailed || 'auth.operationFailed');
        if (button) {
            button.disabled = false;
        }
    }

    function onNetworkError(button) {
        showMessage(MESSAGES.networkError || 'auth.networkError');
        if (button) {
            button.disabled = false;
        }
    }

    function confirmText(template, fallback, name) {
        return (template || fallback).replace('$1', name);
    }

    // ==================== 上传表单（单文件；多文件批量由插件 multi-upload 增强） ====================

    var input = document.getElementById('file-input');
    var preview = document.getElementById('file-preview');
    var form = document.getElementById('file-upload-form');

    function isImage(file) {
        return !!(file && file.type && file.type.indexOf('image/') === 0);
    }

    if (input && preview) {
        input.addEventListener('change', function () {
            var file = input.files && input.files[0];
            if (isImage(file)) {
                preview.src = URL.createObjectURL(file);
                preview.hidden = false;
            } else {
                preview.hidden = true;
                preview.removeAttribute('src');
            }
        });
    }

    if (form) {
        form.addEventListener('submit', function (event) {
            // 多文件批量上传（插件 multi-upload）接管时跳过：插件会把该属性设为 true 并自行处理提交
            if (form.getAttribute('data-multi-upload') === 'true') {
                return;
            }
            event.preventDefault();
            var button = form.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
            }
            var body = new FormData();
            if (!input || !input.files || !input.files[0]) {
                showMessage(MESSAGES.uploadEmpty || 'files.empty');
                if (button) {
                    button.disabled = false;
                }
                return;
            }
            body.append('file', input.files[0]);
            var name = document.getElementById('file-name');
            if (name && name.value) {
                body.append('name', name.value);
            }
            var description = document.getElementById('file-description');
            if (description) {
                body.append('description', description.value);
            }
            var license = document.getElementById('file-license');
            if (license) {
                body.append('license', license.value);
            }

            post('/api/files/upload', body).then(function (result) {
                if (result && result.code === 200 && result.data && result.data.pending) {
                    // 审核：不跳转文件页（文件还没通过审核）
                    showMessage(result.message || MESSAGES.moderationUploadPending || 'moderation.uploadPendingBackend');
                    if (button) {
                        button.disabled = false;
                    }
                    return;
                }
                if (result && result.code === 200 && result.data) {
                    window.location.href = result.data.pageUrl;
                    return;
                }
                onError(result, button);
            }).catch(function () {
                onNetworkError(button);
            });
        });
    }

    // 删除（文件页；勾选彻底删除时不进回收站）
    Array.prototype.forEach.call(document.querySelectorAll('.file-delete'), function (button) {
        button.addEventListener('click', function () {
            var container = button.closest('.file-delete-form');
            var checkbox = container ? container.querySelector('input[name="hard"]') : null;
            var hard = !!(checkbox && checkbox.checked);
            var name = button.getAttribute('data-name') || '';
            var prompt = hard
                ? confirmText(MESSAGES.hardDeleteConfirm, '确定要彻底删除该文件及其所有历史版本吗？此操作不可恢复！', name)
                : confirmText(MESSAGES.deleteConfirm, '确定删除该文件吗？', name);
            if (!window.confirm(prompt)) {
                return;
            }
            var body = new URLSearchParams();
            body.append('id', button.getAttribute('data-id'));
            if (hard) {
                body.append('hard', 'true');
            }
            button.disabled = true;
            post('/api/files/delete', body).then(function (result) {
                if (result && result.code === 200) {
                    if (hard) {
                        window.location.href = '/wiki/特殊:文件列表';
                    } else {
                        window.location.reload();
                    }
                    return;
                }
                onError(result, button);
            }).catch(function () {
                onNetworkError(button);
            });
        });
    });

    // 彻底删除（已删除文件列表）
    Array.prototype.forEach.call(document.querySelectorAll('.file-purge'), function (button) {
        button.addEventListener('click', function () {
            var name = button.getAttribute('data-name') || '';
            var prompt = confirmText(MESSAGES.hardDeleteConfirm,
                '确定要彻底删除该文件及其所有历史版本吗？此操作不可恢复！', name);
            if (!window.confirm(prompt)) {
                return;
            }
            var body = new URLSearchParams();
            body.append('id', button.getAttribute('data-id'));
            body.append('hard', 'true');
            button.disabled = true;
            post('/api/files/delete', body).then(function (result) {
                if (result && result.code === 200) {
                    window.location.reload();
                    return;
                }
                onError(result, button);
            }).catch(function () {
                onNetworkError(button);
            });
        });
    });

    // 恢复（已删除文件 / 文件页）
    Array.prototype.forEach.call(document.querySelectorAll('.file-restore'), function (button) {
        button.addEventListener('click', function () {
            var body = new URLSearchParams();
            body.append('id', button.getAttribute('data-id'));
            button.disabled = true;
            post('/api/files/restore', body).then(function (result) {
                if (result && result.code === 200) {
                    window.location.reload();
                    return;
                }
                onError(result, button);
            }).catch(function () {
                onNetworkError(button);
            });
        });
    });

    // 回滚到历史版本（文件页「文件历史」）
    Array.prototype.forEach.call(document.querySelectorAll('.file-revert'), function (button) {
        button.addEventListener('click', function () {
            if (!window.confirm(MESSAGES.revertConfirm || 'files.revertConfirm')) {
                return;
            }
            var body = new URLSearchParams();
            body.append('name', button.getAttribute('data-name') || '');
            body.append('version', button.getAttribute('data-version') || '');
            button.disabled = true;
            post('/api/files/revert', body).then(function (result) {
                if (result && result.code === 200) {
                    window.location.reload();
                    return;
                }
                onError(result, button);
            }).catch(function () {
                onNetworkError(button);
            });
        });
    });
})();