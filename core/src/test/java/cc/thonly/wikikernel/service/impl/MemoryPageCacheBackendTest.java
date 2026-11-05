package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.service.PageBody;
import cc.thonly.wikikernel.service.InterwikiService;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPageCacheBackendTest {
    private static final Duration TTL = Duration.ofMinutes(5);

    @Test
    void byteBudgetEvictsLeastRecentlyUsedWithRoomForRecentlyReadEntries() {
        PageBody body = PageBody.of(1, "content");
        long weight = MemoryPageCacheBackend.estimatedBytes("a", body);
        MemoryPageCacheBackend cache = new MemoryPageCacheBackend(() -> 100, () -> 2 * weight);
        cache.put("a", body, TTL);
        cache.put("b", body, TTL);
        assertTrue(cache.get("a").isPresent());
        cache.put("c", body, TTL);
        assertTrue(cache.get("a").isPresent());
        assertTrue(cache.get("b").isEmpty());
        assertTrue(cache.get("c").isPresent());
        assertEquals(2 * weight, cache.usedBytes());
    }

    @Test
    void replacementDeletionAndClearReleaseTheirMemory() {
        MemoryPageCacheBackend cache = new MemoryPageCacheBackend(() -> 100, () -> 100_000L);
        cache.put("a", PageBody.of(1, "large".repeat(1000)), TTL);
        long original = cache.usedBytes();
        cache.put("a", PageBody.of(2, "small"), TTL);
        assertTrue(cache.usedBytes() < original);
        assertEquals(1, cache.size());
        cache.remove("a");
        assertEquals(0, cache.usedBytes());
        cache.put("a", PageBody.of(3, "another"), TTL);
        cache.clear();
        assertEquals(0, cache.usedBytes());
        assertEquals(0, cache.size());
    }

    @Test
    void oversizedMetadataCannotEvictUsefulEntriesOrBypassBudget() {
        PageBody small = PageBody.of(1, "keep");
        long budget = MemoryPageCacheBackend.estimatedBytes("a", small) * 2;
        MemoryPageCacheBackend cache = new MemoryPageCacheBackend(() -> 100, () -> budget);
        cache.put("a", small, TTL);
        PageBody withMetadata = new PageBody(2, "tiny", "title".repeat(100),
                List.of(new WikiLinkScanner.CategoryRef("category".repeat(100), "sort")),
                List.of(new InterwikiService.Link("en", "name", "https://example.test/".repeat(100), "en", "English")),
                Map.of("key", "metadata".repeat(1000)));
        cache.put("b", withMetadata, TTL);
        assertTrue(cache.get("a").isPresent());
        assertTrue(cache.get("b").isEmpty());
        assertTrue(cache.usedBytes() <= budget);
        cache.put("a", withMetadata, TTL);
        assertTrue(cache.get("a").isEmpty());
        assertEquals(0, cache.usedBytes());
    }

    @Test
    void reducingBudgetImmediatelyTrimsOldestEntries() {
        PageBody body = PageBody.of(1, "content");
        long weight = MemoryPageCacheBackend.estimatedBytes("a", body);
        AtomicLong budget = new AtomicLong(3 * weight);
        MemoryPageCacheBackend cache = new MemoryPageCacheBackend(() -> 100, budget::get);
        cache.put("a", body, TTL);
        cache.put("b", body, TTL);
        cache.put("c", body, TTL);
        cache.get("a");
        budget.set(weight);
        cache.trim();
        assertEquals(1, cache.size());
        assertTrue(cache.get("a").isPresent());
        assertEquals(weight, cache.usedBytes());
    }

    @Test
    void expirationAndEntryCountRemainEffectiveAlongsideByteBudget() throws Exception {
        MemoryPageCacheBackend cache = new MemoryPageCacheBackend(() -> 1, () -> 100_000L);
        cache.put("a", PageBody.of(1, "a"), TTL);
        cache.put("b", PageBody.of(2, "b"), TTL);
        assertTrue(cache.get("a").isEmpty());
        cache.put("b", PageBody.of(3, "expires"), Duration.ofMillis(10));
        Thread.sleep(30);
        assertTrue(cache.get("b").isEmpty());
        assertEquals(0, cache.usedBytes());
        cache.put("zero", PageBody.of(4, "zero"), Duration.ZERO);
        assertEquals(0, cache.usedBytes());
    }
}
