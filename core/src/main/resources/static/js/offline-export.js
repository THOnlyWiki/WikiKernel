/*
 * 特殊:导出离线 的前端控制器。
 * 只负责启动任务、轮询进度和展示下载链接；实际导出在 OfflineExportServiceImpl 的后台线程执行。
 */
(function () {
    'use strict';

    var button = document.getElementById('offline-export-start');
    if (!button) {
        return;
    }
    var progress = document.getElementById('offline-export-progress');
    var bar = document.getElementById('offline-export-progress-value');
    var status = document.getElementById('offline-export-status');
    var result = document.getElementById('offline-export-result');
    var resultText = document.getElementById('offline-export-done');
    var download = document.getElementById('offline-export-download');
    var messages = window.OFFLINE_EXPORT_MESSAGES || {};
    var startUrl = window.OFFLINE_EXPORT_START_URL || '/api/offline-export';
    var pollTimer = null;

    function setStatus(text) {
        if (status) {
            status.textContent = text || '';
        }
    }

    function setProgress(done, total) {
        if (!progress || !bar) {
            return;
        }
        progress.hidden = false;
        var percent = total > 0 ? Math.min(100, Math.round(done * 100 / total)) : 0;
        bar.style.width = percent + '%';
    }

    function stopPolling() {
        if (pollTimer) {
            window.clearTimeout(pollTimer);
            pollTimer = null;
        }
    }

    function fail(message) {
        stopPolling();
        if (result) {
            result.hidden = true;
        }
        setStatus(message || messages.failed || '导出失败');
        button.disabled = false;
    }

    function poll(jobId) {
        fetch(startUrl + '/' + encodeURIComponent(jobId), {
            headers: {'Accept': 'application/json'},
            credentials: 'same-origin'
        }).then(function (response) {
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            return response.json();
        }).then(function (data) {
            if (data.state === 'failed') {
                fail(data.message || messages.failed);
                return;
            }
            setProgress(data.pagesDone || 0, data.totalPages || 0);
            setStatus(data.message || messages.running);
            if (data.state === 'done') {
                stopPolling();
                button.disabled = false;
                if (result) {
                    result.hidden = false;
                }
                if (resultText) {
                    resultText.textContent = messages.done || '导出完成';
                }
                if (download && data.downloadUrl) {
                    download.href = data.downloadUrl;
                }
                return;
            }
            pollTimer = window.setTimeout(function () {
                poll(jobId);
            }, 1000);
        }).catch(function () {
            fail(messages.failed || '导出状态查询失败');
        });
    }

    button.addEventListener('click', function () {
        stopPolling();
        button.disabled = true;
        if (result) {
            result.hidden = true;
        }
        setProgress(0, 0);
        setStatus(messages.running || '正在生成离线站点…');
        fetch(startUrl, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
            },
            credentials: 'same-origin',
            body: 'limit=0'
        }).then(function (response) {
            return response.json().catch(function () {
                return {};
            }).then(function (data) {
                if (!response.ok) {
                    throw new Error(data.message || ('HTTP ' + response.status));
                }
                return data;
            });
        }).then(function (data) {
            if (!data.jobId) {
                throw new Error('服务器没有返回任务 ID');
            }
            poll(data.jobId);
        }).catch(function (error) {
            fail(error && error.message ? error.message : (messages.failed || '导出失败'));
        });
    });
})();
