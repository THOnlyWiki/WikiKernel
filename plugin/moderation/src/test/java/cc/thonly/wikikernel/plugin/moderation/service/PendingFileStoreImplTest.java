package cc.thonly.wikikernel.plugin.moderation.service;

import cc.thonly.wikikernel.common.plugin.support.SimplePluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PendingFileStoreImpl} 的落盘行为测试：暂存 / 读取 / 删除、扩展名白名单与路径穿越防护。
 * <p>
 * 暂存目录来自 {@code PluginContext.dataDir()}（这里用 {@link SimplePluginContext} 指向临时目录），
 * 生产环境是 {@code data/plugins/moderation/pending/}。
 */
class PendingFileStoreImplTest {

    private static PendingFileStore store(Path dataDir) {
        return new PendingFileStoreImpl(new SimplePluginContext("moderation", dataDir, null));
    }

    @Test
    void stageReadAndDeleteRoundTrip(@TempDir Path dataDir) {
        PendingFileStore store = store(dataDir);

        String storedName = store.stage("示例.png", new byte[]{1, 2, 3});
        assertTrue(storedName.endsWith(".png"), storedName);
        assertArrayEquals(new byte[]{1, 2, 3}, store.read(storedName).orElseThrow());
        assertTrue(Files.exists(dataDir.resolve("pending").resolve(storedName)), "文件应落在 dataDir/pending 下");

        assertTrue(store.delete(storedName));
        assertTrue(store.read(storedName).isEmpty());
        assertFalse(store.delete(storedName), "已经删掉的文件再删返回 false");
    }

    @Test
    void stagedNamesKeepSafeExtensionsAndEmptyContent(@TempDir Path dataDir) {
        PendingFileStore store = store(dataDir);

        assertTrue(store.stage("IMAGE.JPG", new byte[]{1}).endsWith(".jpg"), "扩展名小写");
        assertTrue(store.stage("没有扩展名", new byte[]{1}).matches("[0-9a-f]{32}"), "非法扩展名不拼接");
        assertTrue(store.stage("x.", new byte[]{1}).matches("[0-9a-f]{32}"), "点号结尾不拼接");
        assertTrue(store.stage("x.tar.gz", new byte[]{1}).endsWith(".gz"));

        String empty = store.stage("空.bin", null);
        assertArrayEquals(new byte[0], store.read(empty).orElseThrow(), "内容为 null 时写空文件");
    }

    @Test
    void rejectsPathsAndMissingNames(@TempDir Path dataDir) {
        PendingFileStore store = store(dataDir);

        assertTrue(store.read("../../etc/passwd").isEmpty());
        assertTrue(store.read("a/b").isEmpty());
        assertTrue(store.read("a\\b").isEmpty());
        assertTrue(store.read("").isEmpty());
        assertTrue(store.read(null).isEmpty());
        assertTrue(store.read("不存在的文件").isEmpty());
        assertFalse(store.delete("../../etc/passwd"));
        assertFalse(store.delete(""));
        assertFalse(store.delete(null));

        // 暂存名只含随机 hex + 扩展名：目录不会被创建到别处
        String storedName = store.stage("路径穿越.png", new byte[]{9});
        assertFalse(storedName.contains(".."));
        assertFalse(storedName.contains("/"));
        assertTrue(Files.exists(dataDir.resolve("pending").resolve(storedName)));
        assertEquals(1, store.read(storedName).orElseThrow().length);
    }
}
