package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.service.PageBody;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * 进程内 LRU 后端：{@code LinkedHashMap(accessOrder = true)} + 手工淘汰，
 * 因为条目上限是<b>运行时配置</b>（{@code cache.page.max-entries} 可随时改），
 * 不能写死在 {@code removeEldestEntry} 里 —— 上限每次都从 supplier 现取。
 * <p>
 * 只保存有界条数（默认 500），过期条目在读时惰性删除，不会无限增长；
 * 没有 Redis 也完全可用，是 Redis 不可用时的兜底后端。
 */
final class MemoryPageCacheBackend implements PageCacheBackend {

    private final Map<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final IntSupplier capacity;

    /**
     * @param capacity 条目上限（每次写入时现取，支持运行时改配置）
     */
    MemoryPageCacheBackend(IntSupplier capacity) {
        this.capacity = capacity;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public synchronized Optional<PageBody> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtNanos() <= System.nanoTime()) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.body());
    }

    @Override
    public synchronized void put(String key, PageBody body, Duration ttl) {
        entries.put(key, new Entry(body, System.nanoTime() + ttl.toNanos()));
        evict();
    }

    @Override
    public synchronized void remove(String key) {
        entries.remove(key);
    }

    @Override
    public synchronized void clear() {
        entries.clear();
    }

    @Override
    public boolean available() {
        return true;
    }

    /** 当前条目数（诊断 / 测试用）。 */
    synchronized int size() {
        return entries.size();
    }

    /** 超出上限时按 LRU 淘汰（访问过的条目会被 {@code LinkedHashMap} 移到队尾）。 */
    private void evict() {
        int limit = capacity.getAsInt();
        if (limit <= 0) {
            return;
        }
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > limit && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    /** 缓存条目：正文产出 + 过期时间（纳秒，用 {@code System.nanoTime()} 不受系统时间调整影响）。 */
    private record Entry(PageBody body, long expiresAtNanos) {
    }
}
