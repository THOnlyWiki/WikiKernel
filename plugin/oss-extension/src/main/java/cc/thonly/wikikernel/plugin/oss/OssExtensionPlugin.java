package cc.thonly.wikikernel.plugin.oss;

import cc.thonly.wikikernel.common.extension.FileChangeListener;
import cc.thonly.wikikernel.common.extension.FileUrlResolver;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.FileCatalog;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * OSS 扩展入口：
 * <ul>
 *     <li>{@link FileUrlResolver}：按 thumbnail / all 模式改写图片 URL；</li>
 *     <li>{@link FileChangeListener}：文件上传 / 回滚 / 删除写入本地 outbox，后台同步 OSS；</li>
 *     <li>首次启用时把现有文件和历史版本全量排入 outbox。</li>
 * </ul>
 * 配置在 {@code wikikernel.plugins.settings.oss-extension}，默认关闭，避免未部署 OSS 时误切换 URL。
 */
public class OssExtensionPlugin implements WikiPlugin {

    @Autowired
    private FileCatalog fileCatalog;

    private OssSyncService syncService;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("oss-extension", "OSS Storage", "0.0.1",
                "Sync Wiki originals / history to OSS and cache thumbnails there");
    }

    @Override
    public void onStart(PluginContext context) {
        OssSettings settings = new OssSettings(context.config());
        if (!settings.enabled()) {
            context.logger().log(System.Logger.Level.INFO,
                    "OSS 扩展未启用（oss-extension.enabled=false 或 operation-url/read-url 为空）");
            return;
        }
        OssClient client = new OssClient(settings);
        context.register(FileUrlResolver.class, new OssResolver(settings, client));
        syncService = new OssSyncService(settings, client, fileCatalog, context.dataDir());
        context.register(FileChangeListener.class, syncService);
        syncService.start();
        context.logger().log(System.Logger.Level.INFO,
                "OSS 扩展已启用：mode=" + settings.mode() + "，operation=" + settings.operationUrl()
                        + "，read=" + settings.readBase());
    }

    @Override
    public void onStop(PluginContext context) {
        if (syncService != null) {
            syncService.close();
            syncService = null;
        }
    }
}
