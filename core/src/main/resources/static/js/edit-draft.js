/*
 * 编辑页草稿保护（本地 localStorage，仿“手残救星”）：
 *  - 输入停顿 5 秒自动保存草稿；关闭 / 刷新 / 切后台前也会落盘；
 *  - 还有未保存修改时关闭页面 → 触发浏览器自带的离开确认弹窗（文案由浏览器决定，无法自定义）；
 *  - 下次打开同一页面自动恢复草稿，提示保存时间；页面在草稿之后被改过会额外给出冲突提示；
 *  - 保存成功（提交表单）后自动清除草稿，不再弹确认。
 *  键：tempedit-cache-<页面标题>（段落编辑为 tempedit-cache-<页面标题>@s<段落号>）
 * 值格式：JSON { v, t, rev, base, text }；“text” 原样存储（localStorage 原生支持 Unicode，无需 base64）。
 */
(function () {
    'use strict';

    var MESSAGES = window.WIKI_MESSAGES || {};
    var form = document.getElementById('wiki-edit-form');
    if (!form) {
        return;
    }
    var title = form.getAttribute('data-title') || document.title;
    var currentRevision = form.getAttribute('data-revision') || '';
    var section = form.getAttribute('data-section') || '';
    // 段落编辑用独立草稿键（@s<段落号>），避免与整页草稿互相覆盖
    var STORAGE_KEY = 'tempedit-cache-' + title + (section ? '@s' + section : '');
    var AUTOSAVE_DELAY = 5000;

    var textarea = form.querySelector('textarea[name="content"]');
    var wrapper = form.querySelector('.CodeMirror');
    var codeMirror = wrapper && wrapper.CodeMirror ? wrapper.CodeMirror : null;
    if (!textarea && !codeMirror) {
        return;
    }

    function getText() {
        return codeMirror ? codeMirror.getValue() : textarea.value;
    }

    function setText(text) {
        if (codeMirror) {
            codeMirror.setValue(text);
        } else {
            textarea.value = text;
        }
    }

    /** 32 位 FNV-1a：判断草稿基于哪一版服务端内容。 */
    function hash(text) {
        var h = 0x811c9dc5;
        for (var i = 0; i < text.length; i++) {
            h ^= text.charCodeAt(i);
            h = Math.imul(h, 0x01000193) >>> 0;
        }
        return h;
    }

    function readDraft() {
        try {
            var raw = localStorage.getItem(STORAGE_KEY);
            if (!raw) {
                return null;
            }
            var draft = JSON.parse(raw);
            return draft && typeof draft.text === 'string' ? draft : null;
        } catch (e) {
            return null;
        }
    }

    function removeDraft() {
        try {
            localStorage.removeItem(STORAGE_KEY);
        } catch (e) {
            /* 忽略 */
        }
    }

    /** 有草稿才显示提示条 /「放弃草稿」；没有就隐藏。 */
    function hideNotice() {
        if (notice) {
            notice.hidden = true;
        }
        if (viewLatest) {
            viewLatest.hidden = true;
        }
    }

    /** 自动保存成功后更新提示（恢复 / 冲突文案优先保留）。 */
    function showDraftSaved() {
        if (!notice || !noticeText || (!notice.hidden && restoredText)) {
            return;
        }
        noticeText.textContent = (MESSAGES.draftSaved
            || 'edit.draftSaved').replace('$1', new Date().toLocaleString());
        notice.hidden = false;
    }

    function saveDraft() {
        if (submitted) {
            return;
        }
        var text = getText();
        if (text === originalText) {
            // 内容与页面当前版本一致：草稿没有意义，删掉并隐藏提示
            removeDraft();
            hideNotice();
            return;
        }
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify({
                v: 1,
                t: Date.now(),
                rev: currentRevision,
                base: originalHash,
                text: text
            }));
        } catch (e) {
            return; // 配额满 / 隐私模式：不提示
        }
        showDraftSaved();
    }

    function isDirty() {
        return !submitted && getText() !== originalText;
    }

    var originalText = getText();
    var originalHash = hash(originalText);
    var submitted = false;
    var timer = null;
    var notice = document.getElementById('edit-draft-notice');
    var noticeText = document.getElementById('edit-draft-notice-text');
    var viewLatest = document.getElementById('edit-draft-view-latest');
    var discardButton = document.getElementById('edit-draft-discard');
    var restoredText = null; // 加载时「已恢复草稿 / 冲突」文案（本次会话保留，不被自动保存文案覆盖）

    // ---------- 自动保存（输入停顿 5 秒；切后台 / 关闭前也落盘） ----------
    function scheduleSave() {
        if (timer) {
            clearTimeout(timer);
        }
        timer = setTimeout(saveDraft, AUTOSAVE_DELAY);
    }

    if (codeMirror) {
        codeMirror.on('change', scheduleSave);
    } else {
        textarea.addEventListener('input', scheduleSave);
        textarea.addEventListener('change', scheduleSave);
    }

    window.addEventListener('beforeunload', function (event) {
        if (!isDirty()) {
            return;
        }
        saveDraft();
        event.preventDefault();
        event.returnValue = ''; // 现代浏览器显示自带的离开确认，必须确认才会关闭
        return '';
    });
    window.addEventListener('pagehide', function () {
        if (isDirty()) {
            saveDraft();
        }
    });
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'hidden' && isDirty()) {
            saveDraft();
        }
    });

    // 保存成功：清掉草稿，提交导航不再弹确认
    form.addEventListener('submit', function () {
        submitted = true;
        if (timer) {
            clearTimeout(timer);
        }
        removeDraft();
    });

    // ---------- 打开时恢复草稿 ----------
    /** 冲突：别人在草稿之后保存过（修订号 / 内容哈希任一不匹配）。 */
    function isConflicting(draft) {
        if (draft.rev && currentRevision) {
            return String(draft.rev) !== String(currentRevision);
        }
        return draft.base !== originalHash;
    }

    function showNotice(draft) {
        if (!notice || !noticeText) {
            return;
        }
        var time = new Date(draft.t || Date.now()).toLocaleString();
        var text = (MESSAGES.draftRestored || 'edit.draftRestored').replace('$1', time);
        var conflicting = isConflicting(draft);
        if (conflicting) {
            text += ' ' + (MESSAGES.draftConflict
                || 'edit.draftConflict')
                .replace('$1', draft.rev || '?')
                .replace('$2', currentRevision || '?');
        }
        noticeText.textContent = text;
        restoredText = text;
        notice.hidden = false;
        if (viewLatest) {
            viewLatest.href = location.pathname;
            viewLatest.hidden = !conflicting;
        }
    }

    var draft = readDraft();
    if (draft && draft.text && draft.text !== originalText) {
        setText(draft.text);
        showNotice(draft);
    } else if (draft) {
        removeDraft();
    }

    if (discardButton) {
        discardButton.addEventListener('click', function () {
            removeDraft();
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            restoredText = null;
            hideNotice(); // 立即隐藏提示条与「放弃草稿」
            setText(originalText);
            if (codeMirror) {
                codeMirror.focus();
            }
        });
    }

    // 供调试 / 自动化测试使用
    window.wikiEditDraft = {
        key: STORAGE_KEY,
        save: saveDraft,
        discard: removeDraft,
        original: originalText
    };
})();
