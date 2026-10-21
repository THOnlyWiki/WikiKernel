/*
 * 多文件批量上传（插件 multi-upload；仿 MediaWiki 的 MsUpload / 上传向导）。
 *
 * 两处增强（都由本脚本接管，core 只保留单文件流程）：
 *   ① 特殊:上传文件 页面：文件框可多选，已选文件显示成可改名 / 可移除的列表，
 *      提交时逐个 POST /api/files/upload（每个文件独立成文件页，单个失败不影响其它），
 *      完成后把新文件插进「最近上传」画廊；
 *   ② 编辑页工具栏的「上传并插入图片」对话框：文件框可多选，逐个上传后把
 *      [[文件:名|thumb]] 全部插到光标处（有失败时保留对话框并提示成功 / 失败数）。
 *
 * 开关由插件注入的 window.WIKI_CONFIG.multiUpload 决定（站点设置 features.multi-upload）。
 */
( function () {
    'use strict';

    var CONFIG = window.WIKI_CONFIG || {};
    if ( !CONFIG.multiUpload ) {
        return;
    }
    var MESSAGES = window.WIKI_MESSAGES || {};

    function msg( key, fallback ) {
        return MESSAGES[ key ] || fallback;
    }

    function formatMessage( template, first, second, third ) {
        return String( template || '' )
            .replace( '{0}', first == null ? '' : first )
            .replace( '{1}', second == null ? '' : second )
            .replace( '{2}', third == null ? '' : third );
    }

    function isImage( file ) {
        return !!( file && file.type && file.type.indexOf( 'image/' ) === 0 );
    }

    function fileSize( bytes ) {
        if ( bytes >= 1024 * 1024 ) {
            return ( bytes / ( 1024 * 1024 ) ).toFixed( 1 ) + ' MB';
        }
        if ( bytes >= 1024 ) {
            return Math.round( bytes / 1024 ) + ' KB';
        }
        return bytes + ' B';
    }

    function upload( file, name, description ) {
        var body = new FormData();
        body.append( 'file', file );
        if ( name ) {
            body.append( 'name', name );
        }
        if ( description ) {
            body.append( 'description', description );
        }
        var license = document.getElementById( 'file-license' );
        if ( license && license.value ) {
            body.append( 'license', license.value );
        }
        return fetch( '/api/files/upload', {
            method: 'POST',
            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
            body: body
        } ).then( function ( response ) {
            return response.json().catch( function () {
                return null;
            } );
        } );
    }

    // ==================== ① 上传页面：多文件列表 + 批量上传 ====================

    function setupUploadPage() {
        var form = document.getElementById( 'file-upload-form' );
        var input = document.getElementById( 'file-input' );
        if ( !form || !input ) {
            return;
        }
        var description = document.getElementById( 'file-description' );
        var singleNameField = document.getElementById( 'file-name' );
        var messageBox = document.getElementById( 'file-message' );
        if ( singleNameField && singleNameField.closest ) {
            var wrapper = singleNameField.closest( '.form-field' );
            if ( wrapper ) {
                wrapper.hidden = true;
            }
        }

        // 提示 + 列表容器（core 的单文件表单里没有这两块，由插件按需插入）
        var hint = document.createElement( 'p' );
        hint.className = 'wiki-hint';
        hint.textContent = msg( 'multiUploadHint',
            '已启用多文件批量上传：可以一次选择多个文件，列表里可分别修改目标文件名，逐个上传、各自独立成文件页。' );
        form.parentNode.insertBefore( hint, form );

        var listField = document.createElement( 'div' );
        listField.className = 'form-field';
        var listLabel = document.createElement( 'label' );
        listLabel.textContent = msg( 'multiListLabel', '已选择的文件' );
        var list = document.createElement( 'div' );
        list.id = 'file-multi-list';
        list.className = 'file-multi-list';
        list.hidden = true;
        var listHint = document.createElement( 'div' );
        listHint.className = 'wiki-hint';
        listHint.textContent = msg( 'multiNameHint',
            '每个文件可在列表里单独改名；同名文件会创建新版本（旧版本保留在文件历史中）。' );
        listField.appendChild( listLabel );
        listField.appendChild( list );
        listField.appendChild( listHint );
        if ( description && description.closest ) {
            var descriptionField = description.closest( '.form-field' );
            ( descriptionField && descriptionField.parentNode ? descriptionField.parentNode : form )
                .insertBefore( listField, descriptionField || null );
        } else {
            form.appendChild( listField );
        }
        var descriptionHint = document.createElement( 'div' );
        descriptionHint.className = 'wiki-hint';
        descriptionHint.textContent = msg( 'multiDescriptionHint', '说明会应用到本次上传的每个文件。' );
        if ( description && description.parentNode ) {
            description.parentNode.appendChild( descriptionHint );
        }

        input.setAttribute( 'multiple', 'multiple' );
        form.setAttribute( 'data-multi-upload', 'true' );

        function showMessage( text, success ) {
            if ( !messageBox ) {
                return;
            }
            messageBox.textContent = text;
            messageBox.hidden = false;
            messageBox.classList.toggle( 'error', !success );
            messageBox.classList.toggle( 'success', !!success );
        }

        function renderList() {
            var files = Array.prototype.slice.call( input.files || [] );
            list.textContent = '';
            list.hidden = files.length === 0;
            files.forEach( function ( file ) {
                var row = document.createElement( 'div' );
                row.className = 'file-multi-row';
                row.fileRef = file;

                if ( isImage( file ) ) {
                    var thumb = document.createElement( 'img' );
                    thumb.className = 'file-multi-thumb';
                    thumb.alt = '';
                    thumb.src = URL.createObjectURL( file );
                    row.appendChild( thumb );
                }
                var original = document.createElement( 'span' );
                original.className = 'file-multi-original';
                original.textContent = file.name + '（' + fileSize( file.size ) + '）';
                row.appendChild( original );

                var name = document.createElement( 'input' );
                name.type = 'text';
                name.className = 'file-multi-name';
                name.value = file.name;
                row.appendChild( name );

                var status = document.createElement( 'span' );
                status.className = 'file-multi-status';
                status.textContent = msg( 'multiStatusQueued', '等待上传' );
                row.appendChild( status );

                var remove = document.createElement( 'button' );
                remove.type = 'button';
                remove.className = 'wiki-button file-multi-remove';
                remove.textContent = msg( 'multiRemove', '移除' );
                remove.addEventListener( 'click', function () {
                    row.remove();
                    if ( list.children.length === 0 ) {
                        list.hidden = true;
                    }
                } );
                row.appendChild( remove );

                list.appendChild( row );
            } );
        }

        input.addEventListener( 'change', renderList );

        function refreshGallery() {
            var gallery = document.querySelector( '.file-gallery' );
            if ( !gallery ) {
                return;
            }
            Array.prototype.forEach.call( gallery.querySelectorAll( 'li.file-multi-uploaded' ), function ( item ) {
                item.remove();
            } );
            var uploaded = [];
            Array.prototype.forEach.call( list.querySelectorAll( '.file-multi-row' ), function ( row ) {
                if ( row.uploaded ) {
                    uploaded.push( row.uploaded );
                }
            } );
            uploaded.reverse().forEach( function ( data ) {
                if ( gallery.querySelector( 'a[href="' + data.pageUrl + '"]' ) ) {
                    return;
                }
                var item = document.createElement( 'li' );
                item.className = 'file-multi-uploaded';
                var link = document.createElement( 'a' );
                link.href = data.pageUrl;
                link.title = data.name;
                if ( /\.(png|jpe?g|gif|webp)$/i.test( data.name ) ) {
                    var image = document.createElement( 'img' );
                    image.src = data.url;
                    image.alt = data.name;
                    link.appendChild( image );
                } else {
                    var placeholder = document.createElement( 'span' );
                    placeholder.className = 'file-placeholder';
                    placeholder.textContent = data.name;
                    link.appendChild( placeholder );
                }
                var label = document.createElement( 'div' );
                label.className = 'file-gallery-name';
                var labelLink = document.createElement( 'a' );
                labelLink.href = data.pageUrl;
                labelLink.textContent = data.name;
                label.appendChild( labelLink );
                item.appendChild( link );
                item.appendChild( label );
                gallery.insertBefore( item, gallery.firstChild );
            } );
        }

        form.addEventListener( 'submit', function ( event ) {
            event.preventDefault();
            var button = form.querySelector( 'button[type="submit"]' );
            var rows = Array.prototype.slice.call( list.querySelectorAll( '.file-multi-row' ) );
            if ( rows.length === 0 ) {
                showMessage( msg( 'uploadEmpty', '请选择要上传的文件' ), false );
                return;
            }
            var total = rows.length;
            var ok = 0;
            var pending = 0;
            var failed = 0;
            if ( button ) {
                button.disabled = true;
            }

            function next( position ) {
                if ( position >= total ) {
                    var summary = pending > 0
                        ? formatMessage( msg( 'multiSummaryPending',
                            '上传完成：成功 {0} 个，待审核 {1} 个，失败 {2} 个。' ), ok, pending, failed )
                        : formatMessage( msg( 'multiSummary', '上传完成：成功 {0} 个，失败 {1} 个。' ),
                            ok, failed );
                    showMessage( summary, failed === 0 );
                    if ( button ) {
                        button.disabled = false;
                    }
                    refreshGallery();
                    return;
                }
                var row = rows[ position ];
                var status = row.querySelector( '.file-multi-status' );
                status.className = 'file-multi-status uploading';
                status.textContent = msg( 'multiStatusUploading', '上传中…' );
                showMessage( formatMessage( msg( 'multiUploading', '正在上传（{0}/{1}）…' ),
                    position + 1, total ), false );

                var nameInput = row.querySelector( '.file-multi-name' );
                upload( row.fileRef, nameInput && nameInput.value.trim() ? nameInput.value.trim() : '',
                    description ? description.value : '' ).then( function ( result ) {
                    if ( result && result.code === 200 && result.data && result.data.pending ) {
                        pending++;
                        status.className = 'file-multi-status queued';
                        status.textContent = msg( 'multiStatusQueued', '已提交审核' );
                    } else if ( result && result.code === 200 && result.data ) {
                        ok++;
                        row.uploaded = result.data;
                        status.className = 'file-multi-status done';
                        status.textContent = msg( 'multiStatusDone', '已上传' ) + ' ';
                        var link = document.createElement( 'a' );
                        link.href = result.data.pageUrl;
                        link.target = '_blank';
                        link.textContent = result.data.name;
                        status.appendChild( link );
                    } else {
                        failed++;
                        status.className = 'file-multi-status failed';
                        status.textContent = ( result && result.message ) || msg( 'multiStatusFailed', '失败' );
                    }
                    next( position + 1 );
                } ).catch( function () {
                    failed++;
                    status.className = 'file-multi-status failed';
                    status.textContent = msg( 'networkError', '网络错误，请稍后重试' );
                    next( position + 1 );
                } );
            }

            next( 0 );
        } );
    }

    // ==================== ② 编辑页工具栏的上传对话框：多选 + 批量插入 ====================

    function setupEditorDialog() {
        var form = document.getElementById( 'wiki-edit-form' );
        var toolbar = document.getElementById( 'wiki-editor-toolbar' );
        if ( !form || !toolbar ) {
            return;
        }
        var textarea = form.querySelector( 'textarea[name="content"]' );
        if ( !textarea ) {
            return;
        }

        /** 把插入内容写进编辑器：语法高亮（CodeMirror）激活时走 core 的编辑器桥。 */
        function insertLinks( links ) {
            if ( links.length === 0 ) {
                return;
            }
            var inserted = links.join( '\n' );
            var bridge = window.wikiCodeEditor && window.wikiCodeEditor.active()
                ? window.wikiCodeEditor : null;
            if ( bridge ) {
                var range = bridge.getSelectionRange();
                bridge.replaceRange( inserted, range.start, range.end,
                    range.start + inserted.length, range.start + inserted.length );
                return;
            }
            var start = textarea.selectionStart;
            var end = textarea.selectionEnd;
            textarea.setRangeText( inserted, start, end, 'end' );
            textarea.focus();
            textarea.setSelectionRange( start + inserted.length, start + inserted.length );
            textarea.dispatchEvent( new Event( 'input', { bubbles: true } ) );
        }

        /** 批量上传（按钮多选与拖入文件共用）：按顺序上传，成功即插入链接。 */
        function uploadFiles( files, showStatus, done ) {
            var links = [];
            var pending = 0;
            var failed = 0;
            var total = files.length;

            function next( position ) {
                if ( position >= total ) {
                    insertLinks( links );
                    done( links, pending, failed );
                    return;
                }
                showStatus( formatMessage( msg( 'multiUploading', '正在上传（{0}/{1}）…' ),
                    position + 1, total ), false );
                upload( files[ position ], '', '' ).then( function ( result ) {
                    if ( result && result.code === 200 && result.data && result.data.pending ) {
                        pending++;
                    } else if ( result && result.code === 200 && result.data && result.data.name ) {
                        links.push( '[[文件:' + result.data.name + '|thumb]]' );
                    } else {
                        failed++;
                    }
                    next( position + 1 );
                } ).catch( function () {
                    failed++;
                    next( position + 1 );
                } );
            }

            next( 0 );
        }

        // ---------- 拖入文件：直接把图片拖到编辑器上即上传并插入 ----------

        ( function setupDropZone() {
            var zone = form;
            var hint = null;
            var depth = 0;

            function showHint( show ) {
                if ( show ) {
                    if ( !hint ) {
                        hint = document.createElement( 'div' );
                        hint.className = 'wiki-editor-drop-hint';
                        hint.textContent = msg( 'toolbarFileDrop', '松开即可上传并插入到正文' );
                        document.body.appendChild( hint );
                    }
                    hint.hidden = false;
                } else if ( hint ) {
                    hint.hidden = true;
                }
            }

            function hasFiles( event ) {
                var transfer = event.dataTransfer;
                if ( !transfer ) {
                    return false;
                }
                if ( transfer.types && Array.prototype.indexOf.call( transfer.types, 'Files' ) >= 0 ) {
                    return true;
                }
                return !!( transfer.files && transfer.files.length );
            }

            zone.addEventListener( 'dragenter', function ( event ) {
                if ( !hasFiles( event ) ) {
                    return;
                }
                event.preventDefault();
                depth++;
                zone.classList.add( 'wiki-editor-dragover' );
                showHint( true );
            } );
            zone.addEventListener( 'dragover', function ( event ) {
                if ( !hasFiles( event ) ) {
                    return;
                }
                event.preventDefault();
                if ( event.dataTransfer ) {
                    event.dataTransfer.dropEffect = 'copy';
                }
            } );
            zone.addEventListener( 'dragleave', function ( event ) {
                if ( !hasFiles( event ) ) {
                    return;
                }
                depth = Math.max( 0, depth - 1 );
                if ( depth === 0 ) {
                    zone.classList.remove( 'wiki-editor-dragover' );
                    showHint( false );
                }
            } );
            zone.addEventListener( 'drop', function ( event ) {
                if ( !hasFiles( event ) ) {
                    return;
                }
                event.preventDefault();
                depth = 0;
                zone.classList.remove( 'wiki-editor-dragover' );
                showHint( false );
                var files = Array.prototype.slice.call( event.dataTransfer.files || [] );
                if ( files.length === 0 ) {
                    return;
                }
                var names = files.map( function ( file ) { return file.name; } ).join( '、' );
                dropStatus( formatMessage( msg( 'multiUploading', '正在上传（{0}/{1}）…' ), 1, files.length ), false );
                uploadFiles( files, dropStatus, function ( links, pending, failed ) {
                    if ( pending > 0 && failed === 0 ) {
                        dropStatus( formatMessage( msg( 'multiSummaryPending',
                            '上传完成：成功 {0} 个，待审核 {1} 个，失败 {2} 个。' ),
                            links.length, pending, failed ), false );
                    } else if ( failed === 0 ) {
                        dropStatus( formatMessage( msg( 'multiSummary', '上传完成：成功 {0} 个，失败 {1} 个。' ),
                            links.length, failed ), false );
                        setTimeout( function () { dropStatus( '', false ); }, 2000 );
                    } else {
                        dropStatus( formatMessage( msg( 'multiSummary', '上传完成：成功 {0} 个，失败 {1} 个。' ),
                            links.length, failed ), true );
                    }
                } );
                console.log( '[multi-upload] 拖入文件：' + names );
            } );

            function dropStatus( text, error ) {
                var status = document.getElementById( 'multi-upload-drop-status' );
                if ( !status ) {
                    status = document.createElement( 'div' );
                    status.id = 'multi-upload-drop-status';
                    status.className = 'form-message wiki-editor-drop-status';
                    status.hidden = true;
                    form.appendChild( status );
                }
                status.textContent = text;
                status.className = 'form-message wiki-editor-drop-status' + ( error ? '' : ' success' );
                status.hidden = !text;
            }
        } )();

        function enhance( dialog ) {
            if ( dialog.getAttribute( 'data-multi-upload-ready' ) === '1' ) {
                return;
            }
            dialog.setAttribute( 'data-multi-upload-ready', '1' );
            var input = dialog.querySelector( '#wiki-file-input' );
            var nameInput = dialog.querySelector( '#wiki-file-name' );
            var status = dialog.querySelector( '.wiki-dialog-status' );
            var button = dialog.querySelector( '.wiki-dialog-upload' );
            if ( !input || !button ) {
                return;
            }
            input.setAttribute( 'multiple', 'multiple' );
            if ( nameInput ) {
                nameInput.hidden = true;
                var label = dialog.querySelector( 'label[for="wiki-file-name"]' );
                if ( label ) {
                    label.hidden = true;
                }
            }
            var chooseLabel = dialog.querySelector( 'label[for="wiki-file-input"]' );
            if ( chooseLabel ) {
                chooseLabel.textContent = msg( 'uploadChooseMany', '选择图片（可多选）：' );
            }

            function showStatus( text, error ) {
                if ( !status ) {
                    return;
                }
                status.textContent = text;
                status.className = 'wiki-dialog-status form-message' + ( error ? '' : ' success' );
                status.hidden = !text;
            }

            // 捕获阶段拦下「上传并插入」：core 的单文件逻辑不再触发，由插件批量处理
            button.addEventListener( 'click', function ( event ) {
                var files = Array.prototype.slice.call( input.files || [] );
                if ( files.length === 0 ) {
                    showStatus( msg( 'toolbarFileEmpty', '请先选择要上传的图片' ), true );
                    return;
                }
                if ( files.length === 1 ) {
                    // 单选时交给 core 的单文件流程（保持一致的行为与提示）
                    return;
                }
                event.stopPropagation();
                event.preventDefault();
                button.disabled = true;
                var original = button.textContent;
                button.textContent = msg( 'uploading', '正在上传…' );
                showStatus( '', false );

                uploadFiles( files, showStatus, function ( links, pending, failed ) {
                    button.disabled = false;
                    button.textContent = original;
                    if ( failed === 0 && pending === 0 ) {
                        dialog.hidden = true;
                        return;
                    }
                    if ( pending > 0 && failed === 0 ) {
                        showStatus( formatMessage( msg( 'multiSummaryPending',
                            '上传完成：成功 {0} 个，待审核 {1} 个，失败 {2} 个。' ),
                            links.length, pending, failed ), false );
                        return;
                    }
                    showStatus( formatMessage( msg( 'multiSummary', '上传完成：成功 {0} 个，失败 {1} 个。' ),
                        links.length, failed ), true );
                } );
            }, true );
        }

        // 对话框由 core 的工具栏脚本在点击「文件」按钮时创建：观察 DOM，出现即增强
        function scan() {
            var dialog = document.querySelector( '.wiki-dialog' );
            if ( dialog ) {
                var wrapper = dialog.parentElement;
                if ( wrapper ) {
                    enhance( wrapper );
                }
            }
        }
        var observer = new MutationObserver( scan );
        observer.observe( document.body, { childList: true, subtree: true } );
        scan();
    }

    function start() {
        setupUploadPage();
        setupEditorDialog();
    }

    if ( document.readyState === 'loading' ) {
        document.addEventListener( 'DOMContentLoaded', start );
    } else {
        start();
    }
}() );
