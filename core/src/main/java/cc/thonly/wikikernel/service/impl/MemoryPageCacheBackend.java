package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.config.PageCacheProperties;
import cc.thonly.wikikernel.service.PageBody;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** 内置 LRU 缓存：同时限制条数与估算驻留内存，Redis 故障时复用同一个后端。 */
final class MemoryPageCacheBackend implements PageCacheBackend {

    private final Map<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final IntSupplier capacity;
    private final LongSupplier memoryCapacity;
    private long usedBytes;

    MemoryPageCacheBackend(IntSupplier capacity) {
        this(capacity, () -> PageCacheProperties.DEFAULT_MEMORY_SIZE_MB * 1024L * 1024L);
    }

    MemoryPageCacheBackend(IntSupplier capacity, LongSupplier memoryCapacity) {
        this.capacity = capacity;
        this.memoryCapacity = memoryCapacity;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public synchronized Optional<PageBody> get(String key) {
        trim();
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtNanos() - System.nanoTime() <= 0) {
            remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.body());
    }

    @Override
    public synchronized void put(String key, PageBody body, Duration ttl) {
        trim();
        remove(key);
        long weight = estimatedBytes(key, body);
        // 单条超限时不挤掉其它有效条目；替换 / 删除 / 过期均同步更新记账。
        if (ttl.isZero() || ttl.isNegative() || weight > Math.max(0L, memoryCapacity.getAsLong())) {
            return;
        }
        entries.put(key, new Entry(body, System.nanoTime() + ttl.toNanos(), weight));
        usedBytes += weight;
        trim();
    }

    @Override
    public synchronized void remove(String key) {
        Entry removed = entries.remove(key);
        if (removed != null) {
            usedBytes -= removed.weight();
        }
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        usedBytes = 0L;
    }

    @Override
    public boolean available() {
        return true;
    }

    synchronized int size() {
        trim();
        return entries.size();
    }

    synchronized long usedBytes() {
        trim();
        return usedBytes;
    }

    /** 调小容量后立即收缩；容量压力下先回收过期条目，再按最近使用顺序淘汰。 */
    synchronized void trim() {
        int countLimit = capacity.getAsInt();
        long byteLimit = Math.max(0L, memoryCapacity.getAsLong());
        if (!overLimit(countLimit, byteLimit)) {
            return;
        }
        long now = System.nanoTime();
        Iterator<Entry> expired = entries.values().iterator();
        while (expired.hasNext()) {
            Entry entry = expired.next();
            if (entry.expiresAtNanos() - now <= 0) {
                usedBytes -= entry.weight();
                expired.remove();
            }
        }
        Iterator<Entry> oldest = entries.values().iterator();
        while (overLimit(countLimit, byteLimit) && oldest.hasNext()) {
            usedBytes -= oldest.next().weight();
            oldest.remove();
        }
    }

    private boolean overLimit(int countLimit, long byteLimit) {
        return usedBytes > byteLimit || (countLimit > 0 && entries.size() > countLimit);
    }

    /**
     * 保守估算键、正文、显示标题、分类、跨语言链接、页面属性与容器开销。
     * 字符串按每字符两字节估算（包含非 ASCII 与补充字符）；不依赖 JVM 的压缩字符串实现。
     * 这是缓存自身的容量预算，并非整个 JVM 的堆内存上限。
     */
    static long estimatedBytes(String key, PageBody body) {
        long bytes = 384L + stringBytes(key) + stringBytes(body.html()) + stringBytes(body.displayTitle());
        for (var category : body.categories()) {
            bytes += 64L + stringBytes(category.title()) + stringBytes(category.sortKey());
        }
        for (var link : body.interwikiLinks()) {
            bytes += 96L + stringBytes(link.prefix()) + stringBytes(link.title()) + stringBytes(link.url())
                    + stringBytes(link.language()) + stringBytes(link.languageName());
        }
        for (var property : body.properties().entrySet()) {
            bytes += 96L + stringBytes(property.getKey()) + stringBytes(property.getValue());
        }
        return bytes;
    }

    private static long stringBytes(String value) {
        return value == null ? 0L : 48L + ((2L * value.length() + 7L) & ~7L);
    }

    private record Entry(PageBody body, long expiresAtNanos, long weight) {
    }
}
