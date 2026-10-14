package cc.thonly.wikikernel.plugin.fingerprintlogin.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.plugin.fingerprintlogin.data.WikiFingerprint;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link FingerprintStore} 的插件实现：表 {@code wiki_fingerprint} **属于插件自己**——
 * 表结构由 {@code FingerprintLoginPlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列
 * （SQLite / MySQL / H2 一套规则），数据访问走 {@link PluginDatabase}（JDBC 桥）。
 * <p>
 * 行为与迁移前 core 的 {@code FingerprintStoreImpl}（MyBatis-Plus）一致：只能删自己的、
 * 每用户上限 {@link #MAX_PER_USER}、同一摘要更新、别人的摘要拒绝；UA 摘要按列长（300）截断
 * 由调用方完成（插件服务层已截断，这里按原样入库）。
 */
@Service
public class FingerprintStoreJdbc implements FingerprintStore {

    private final PluginDatabase database;

    public FingerprintStoreJdbc(PluginDatabase database) {
        this.database = database;
    }

    @Override
    public List<WikiFingerprint> byUser(long userId) {
        List<DbRow> rows = database.query(
                "SELECT * FROM wiki_fingerprint WHERE user_id = ? ORDER BY id DESC", userId);
        List<WikiFingerprint> fingerprints = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            fingerprints.add(fingerprint(row));
        }
        return fingerprints;
    }

    @Override
    public Optional<WikiFingerprint> byHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return Optional.empty();
        }
        return database.queryOne(
                        "SELECT * FROM wiki_fingerprint WHERE fingerprint_hash = ? LIMIT 1", hash.trim())
                .map(FingerprintStoreJdbc::fingerprint);
    }

    @Override
    public int countByUser(long userId) {
        return database.queryOne(
                        "SELECT COUNT(*) AS total FROM wiki_fingerprint WHERE user_id = ?", userId)
                .map(row -> (int) row.longValue("total", 0L))
                .orElse(0);
    }

    @Override
    public WikiFingerprint add(long userId, String label, String hash, String userAgent) {
        String normalizedHash = hash == null ? "" : hash.trim();
        String normalizedLabel = label == null ? "" : label.trim();
        String normalizedAgent = userAgent == null ? "" : userAgent.trim();
        if (!normalizedAgent.isEmpty() && normalizedAgent.length() > 300) {
            normalizedAgent = normalizedAgent.substring(0, 300);
        }
        WikiFingerprint existing = byHash(normalizedHash).orElse(null);
        if (existing != null) {
            if (!existing.getUserId().equals(userId)) {
                // 别人的指纹：不抢注、也不泄露归属（调用方给出中性提示）
                throw new IllegalArgumentException("fingerprint registered to another user");
            }
            database.update("UPDATE wiki_fingerprint SET label = ?, user_agent = ? WHERE id = ?",
                    normalizedLabel, normalizedAgent, existing.getId());
            existing.setLabel(normalizedLabel);
            existing.setUserAgent(normalizedAgent);
            return existing;
        }
        if (countByUser(userId) >= MAX_PER_USER) {
            throw new IllegalStateException("fingerprint limit reached");
        }
        WikiFingerprint created = new WikiFingerprint(null, userId, normalizedLabel,
                normalizedHash, normalizedAgent, LocalDateTime.now(), null);
        long id = database.insert("INSERT INTO wiki_fingerprint"
                        + "(user_id, label, fingerprint_hash, user_agent, created_at, last_used_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                created.getUserId(), created.getLabel(), created.getFingerprintHash(),
                created.getUserAgent(), created.getCreatedAt(), created.getLastUsedAt());
        created.setId(id);
        return created;
    }

    @Override
    public boolean remove(long userId, long id) {
        // 只删自己的：先按 (id, userId) 查，查不到就当不存在
        boolean owned = database.queryOne(
                        "SELECT id FROM wiki_fingerprint WHERE id = ? AND user_id = ? LIMIT 1", id, userId)
                .isPresent();
        if (!owned) {
            return false;
        }
        return database.update("DELETE FROM wiki_fingerprint WHERE id = ?", id) > 0;
    }

    @Override
    public void touch(long id) {
        database.update("UPDATE wiki_fingerprint SET last_used_at = ? WHERE id = ?",
                LocalDateTime.now(), id);
    }

    // ==================== 行 → 实体 ====================

    private static WikiFingerprint fingerprint(DbRow row) {
        WikiFingerprint fingerprint = new WikiFingerprint();
        fingerprint.setId(row.longOrNull("id"));
        fingerprint.setUserId(row.longOrNull("user_id"));
        fingerprint.setLabel(row.string("label"));
        fingerprint.setFingerprintHash(row.string("fingerprint_hash"));
        fingerprint.setUserAgent(row.string("user_agent"));
        fingerprint.setCreatedAt(row.dateTime("created_at"));
        fingerprint.setLastUsedAt(row.dateTime("last_used_at"));
        return fingerprint;
    }
}
