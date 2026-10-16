/*
 * CodeMirror 5 的 wikitext（MediaWiki 语法）高亮模式。
 *
 * 为什么自带一份：MediaWiki 自己的 CodeMirror 媒体模式依赖 mw.config 与各站点魔术字配置；
 * WikiKernel 需要的是一个轻量、可离线内置的版本。token 名沿用 MediaWiki 那套
 * （strong / em / link / image / template / table / header / hr / meta / tag / url / atom），
 * 颜色由皮肤 CSS 决定（base.css 的 .cm-s-default 规则 + 皮肤变量），换皮肤不用改本文件。
 *
 * 覆盖：注释、<nowiki>/<pre>/<syntaxhighlight> 等原样块、标题、粗体/斜体、
 *       内部链接（[[文件:]]、[[分类:]] 单独着色）、外部链接、模板 {{…}}/{{{…}}}、
 *       表格 {| … |}、列表与缩进标记、水平线、签名 ~~~~、HTML 标签、字符实体。
 */
(function (CodeMirror) {
    'use strict';

    if (!CodeMirror || CodeMirror.modes.wikitext) {
        return;
    }

    /** 原样块：这些标签内部不再解析（整段按注释色显示）。 */
    var RAW_NAME = 'nowiki|pre|syntaxhighlight|source|code|math|score|timeline|graph';
    var RAW_OPEN = new RegExp('^<(' + RAW_NAME + ')\\b[^>]*>', 'i');
    var TAG = /^<\/?[A-Za-z][A-Za-z0-9]*\b[^>]*>/;
    var ENTITY = /^&(#\d+|[A-Za-z][A-Za-z0-9]{1,31});/;
    var HEADING = /^(={1,6})\s*/;
    var LIST = /^[#*:;]+/;
    var HR = /^-{4,}\s*$/;
    var NAMESPACE = /^(文件|档案|File|Image|图像|图片|分类|Category)\s*:/i;
    var EXTERNAL = /^\[(https?|ftp|mailto|irc|ircs):/i;

    CodeMirror.defineMode('wikitext', function () {
        return {
            startState: function () {
                return { raw: null, comment: false };
            },
            token: function (stream, state) {
                // ---------- 注释（可跨行） ----------
                if (state.comment) {
                    if (stream.match('-->')) {
                        state.comment = false;
                    } else {
                        stream.skipToEnd();
                    }
                    return 'comment';
                }
                if (stream.match('<!--')) {
                    if (!stream.match('-->')) {
                        state.comment = true;
                    }
                    return 'comment';
                }

                // ---------- 原样块（<nowiki> / <pre> / <syntaxhighlight> …） ----------
                if (state.raw) {
                    var close = new RegExp('</' + state.raw + '\\s*>', 'i');
                    if (stream.match(close)) {
                        state.raw = null;
                    } else {
                        stream.skipToEnd();
                    }
                    return 'comment';
                }
                var open = stream.match(RAW_OPEN);
                if (open) {
                    var name = open[1];
                    var end = new RegExp('</' + name + '\\s*>', 'i');
                    // 同行闭合就不进入原样状态
                    if (!stream.match(end)) {
                        state.raw = name;
                    }
                    return 'tag';
                }

                // ---------- 行首结构：标题 / 表格 / 列表 / 水平线 ----------
                if (stream.sol()) {
                    var heading = stream.match(HEADING);
                    if (heading) {
                        stream.skipToEnd();
                        return 'header header-' + heading[1].length;
                    }
                    if (stream.match('{|') || stream.match('|-') || stream.match('|}')) {
                        stream.skipToEnd();
                        return 'table';
                    }
                    if (stream.match(HR)) {
                        return 'hr';
                    }
                    if (stream.match(LIST)) {
                        return 'meta';
                    }
                }
                // 表格行内的单元格分隔（! / | / || / !!）
                if (stream.match(/^[!|]/)) {
                    return 'table';
                }

                // ---------- 模板 / 参数 ----------
                if (stream.match('{{{') || stream.match('{{') || stream.match('}}}') || stream.match('}}')) {
                    return 'template';
                }

                // ---------- 内部链接 ----------
                if (stream.match('[[')) {
                    stream.skipToEnd();
                    return stream.match(NAMESPACE) ? 'image' : 'link';
                }
                if (stream.match(']]')) {
                    return 'link';
                }

                // ---------- 外部链接 ----------
                if (stream.match(EXTERNAL) || stream.match(/^\[(?!\[)/)) {
                    stream.skipToEnd();
                    return 'url';
                }

                // ---------- 签名 / 粗体 / 斜体 ----------
                if (stream.match('~~~~~') || stream.match('~~~~') || stream.match('~~~')) {
                    return 'meta';
                }
                if (stream.match("'''")) {
                    stream.skipTo("'''");
                    return 'strong';
                }
                if (stream.match("''")) {
                    stream.skipTo("''");
                    return 'em';
                }

                // ---------- HTML 标签 / 字符实体 ----------
                if (stream.match(TAG)) {
                    return 'tag';
                }
                if (stream.match(ENTITY)) {
                    return 'atom';
                }

                stream.next();
                return null;
            }
        };
    });

    CodeMirror.defineMIME('text/x-wiki', 'wikitext');
    CodeMirror.defineMIME('text/mediawiki', 'wikitext');
    CodeMirror.defineMIME('mediawiki', 'wikitext');
})(window.CodeMirror);
