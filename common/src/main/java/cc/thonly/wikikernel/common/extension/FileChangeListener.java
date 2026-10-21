package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/** 文件状态变化监听扩展点：OSS 等外部存储插件用它同步最新版本 / 历史版本。 */
public interface FileChangeListener extends Extension {

    void onFileChanged(FileChangeEvent event);
}
