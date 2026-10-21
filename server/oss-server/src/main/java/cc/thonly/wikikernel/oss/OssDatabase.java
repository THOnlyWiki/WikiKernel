package cc.thonly.wikikernel.oss;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SQLite 元数据：当前 / 历史原图与缩略图缓存索引。 */
@Component
public class OssDatabase {

    private static final RowMapper<OssFile> FILE_MAPPER = (rs, rowNum) -> new OssFile(
            rs.getString("stored_name"),
            rs.getString("name"),
            rs.getString("content_type"),
            rs.getLong("size"),
            rs.getLong("file_id"),
            rs.getInt("version"),
            rs.getInt("current") != 0,
            rs.getInt("deleted") != 0,
            rs.getString("created_at"),
            rs.getString("updated_at"));

    private final JdbcTemplate jdbc;

    public OssDatabase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oss_file (
                    stored_name TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    content_type TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    file_id INTEGER NOT NULL DEFAULT 0,
                    version INTEGER NOT NULL DEFAULT 1,
                    current INTEGER NOT NULL DEFAULT 1,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oss_thumbnail (
                    stored_name TEXT NOT NULL,
                    variant TEXT NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (stored_name, variant)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_oss_file_id ON oss_file(file_id)");
    }

    /** 写入 / 覆盖某个原图版本，并同步同一文件的最新版本标记。 */
    public void save(OssFile file) {
        String now = Instant.now().toString();
        if (file.fileId() > 0 && file.current()) {
            jdbc.update("UPDATE oss_file SET current = 0 WHERE file_id = ?", file.fileId());
        }
        jdbc.update("""
                        INSERT INTO oss_file(stored_name, name, content_type, size, file_id, version,
                                             current, deleted, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(stored_name) DO UPDATE SET
                            name = excluded.name,
                            content_type = excluded.content_type,
                            size = excluded.size,
                            file_id = excluded.file_id,
                            version = excluded.version,
                            current = excluded.current,
                            deleted = excluded.deleted,
                            updated_at = excluded.updated_at
                        """,
                file.storedName(), file.name(), file.contentType(), file.size(), file.fileId(),
                file.version(), file.current() ? 1 : 0, file.deleted() ? 1 : 0,
                file.createdAt() == null ? now : file.createdAt(), now);
    }

    public Optional<OssFile> find(String storedName) {
        List<OssFile> rows = jdbc.query(
                "SELECT * FROM oss_file WHERE stored_name = ? LIMIT 1", FILE_MAPPER, storedName);
        return rows.stream().findFirst();
    }

    public List<OssFile> list() {
        return jdbc.query("SELECT * FROM oss_file ORDER BY updated_at DESC", FILE_MAPPER);
    }

    public void markDeleted(long fileId, boolean deleted) {
        jdbc.update("UPDATE oss_file SET deleted = ?, updated_at = ? WHERE file_id = ?",
                deleted ? 1 : 0, Instant.now().toString(), fileId);
    }

    public void delete(String storedName) {
        jdbc.update("DELETE FROM oss_thumbnail WHERE stored_name = ?", storedName);
        jdbc.update("DELETE FROM oss_file WHERE stored_name = ?", storedName);
    }

    public void saveThumbnail(String storedName, String variant, int width, int height, long size) {
        jdbc.update("""
                        INSERT INTO oss_thumbnail(stored_name, variant, width, height, size, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(stored_name, variant) DO UPDATE SET
                            width = excluded.width,
                            height = excluded.height,
                            size = excluded.size,
                            created_at = excluded.created_at
                        """,
                storedName, variant, width, height, size, Instant.now().toString());
    }
}
