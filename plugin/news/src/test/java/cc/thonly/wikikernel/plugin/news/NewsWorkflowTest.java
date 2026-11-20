package cc.thonly.wikikernel.plugin.news;

import cc.thonly.wikikernel.common.ai.AiTasks;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NewsWorkflowTest {
    private SingleConnectionDataSource source;
    private PluginDatabase db;
    private NewsService news;
    private AiTasks ai;
    private final Viewer author = Viewer.of(1, "投稿者", Set.of("用户"));
    private final Viewer reviewer = Viewer.of(2, "审核员", Set.of("新闻审核"));

    @BeforeEach
    void prepare() {
        source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE wiki_news (id INTEGER PRIMARY KEY AUTOINCREMENT, draft TEXT, published TEXT, status TEXT, version INTEGER, owner_id INTEGER, updated_at TEXT, created_at TEXT, released_at TEXT, expires_at TEXT, pin_until TEXT, public_category TEXT, public_region TEXT, public_search TEXT)");
        jdbc.execute("CREATE TABLE wiki_news_revision (id INTEGER PRIMARY KEY AUTOINCREMENT, news_id INTEGER, version INTEGER, payload TEXT, action TEXT, actor_id INTEGER, created_at TEXT)");
        jdbc.execute("CREATE TABLE wiki_news_job (id INTEGER PRIMARY KEY AUTOINCREMENT, dedup_key TEXT UNIQUE, actor_id INTEGER, input TEXT, source_url TEXT, state TEXT, news_id INTEGER, error TEXT, created_at TEXT, leased_at TEXT)");
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        db = new PluginDatabase() {
            public String databaseProductName() { return "SQLite"; }
            public List<String> tableNames() { return List.of(); }
            public int update(String sql, Object... args) { return jdbc.update(sql, args); }
            public long insert(String sql, Object... args) {
                jdbc.update(sql, args); return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            }
            public List<DbRow> query(String sql, Object... args) { return jdbc.queryForList(sql, args).stream().map(DbRow::new).toList(); }
            public Optional<DbRow> queryOne(String sql, Object... args) { return query(sql, args).stream().findFirst(); }
            public <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
        };
        AccessCheck access = mock(AccessCheck.class);
        when(access.can(any(), any(), any())).thenReturn(true);
        when(access.canRead(anyString(), any())).thenReturn(true);
        when(access.blockNotice(any())).thenReturn(Optional.empty());
        SiteConfig settings = mock(SiteConfig.class);
        when(settings.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        ActivityDirectory activities = mock(ActivityDirectory.class);
        when(activities.activities(anyInt())).thenReturn(List.of());
        ai = mock(AiTasks.class);
        news = new NewsService(db, access, settings, new ObjectMapper(), mock(LogWriter.class), activities, ai);
    }

    @AfterEach void close() { source.destroy(); }

    private NewsContent content(String body) {
        return new NewsContent("活动消息", "摘要", body, "定档", "江苏省", "主办方", "https://example.com/notice", "",
                0, 0, "", "", "", "", true, "test-model");
    }

    @Test
    void publicationRequiresReviewedCurrentRevisionAndKeepsPreviousPublicSnapshot() {
        long id = news.save(author, 0, 0, content("第一版"));
        assertTrue(news.published(Viewer.ANONYMOUS, true, "", "", "", 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> news.transition(reviewer, id, 1, "publish"));
        news.transition(author, id, 1, "submit");
        assertThrows(IllegalArgumentException.class, () -> news.transition(author, id, 2, "publish"));
        news.transition(reviewer, id, 2, "publish");
        assertEquals("第一版", news.article(Viewer.ANONYMOUS, id).body());
        news.save(author, id, 3, content("第二版草稿"));
        assertEquals("第一版", news.article(Viewer.ANONYMOUS, id).body());
        assertThrows(IllegalArgumentException.class, () -> news.transition(reviewer, id, 4, "publish"));
        news.transition(author, id, 4, "submit");
        assertThrows(IllegalArgumentException.class, () -> news.transition(reviewer, id, 2, "publish"));
        assertEquals("PENDING", news.draft(author, id).get("status"));
        news.transition(reviewer, id, 5, "publish");
        assertEquals("第二版草稿", news.article(Viewer.ANONYMOUS, id).body());
        news.transition(reviewer, id, 6, "withdraw");
        assertThrows(IllegalArgumentException.class, () -> news.article(Viewer.ANONYMOUS, id));
        assertEquals(7, news.history(reviewer, id).size());
    }

    @Test
    void otherUsersCannotReadDraftsAndExpiredItemsLeaveHomepage() {
        NewsContent original = content("正文");
        NewsContent expired = new NewsContent(original.title(), original.summary(), original.body(), original.category(), original.region(),
                original.sourceName(), original.sourceUrl(), "", 0, 0, "", "", Instant.now().minusSeconds(60).toString(), "", true, "model");
        long id = news.save(author, 0, 0, expired);
        Viewer other = Viewer.of(3, "他人", Set.of("用户"));
        assertThrows(IllegalArgumentException.class, () -> news.draft(other, id));
        assertThrows(IllegalArgumentException.class, () -> news.history(other, id));
        news.transition(author, id, 1, "submit"); news.transition(reviewer, id, 2, "publish");
        assertTrue(news.published(Viewer.ANONYMOUS, true, "", "", "", 0).isEmpty());
        assertEquals(1, news.published(Viewer.ANONYMOUS, false, "", "", "", 0).size());
    }

    @Test void newsLogVisibilityTracksDraftPublicationAndWithdrawal() {
        long id = news.save(author, 0, 0, content("私密草稿正文"));
        var target = "新闻/" + id;
        assertFalse(news.canViewLog(Viewer.ANONYMOUS, target));
        assertFalse(news.canViewLog(Viewer.of(8, "其他用户", Set.of("用户")), target));
        assertTrue(news.canViewLog(author, target)); assertTrue(news.canViewLog(reviewer, target));
        news.transition(author, id, 1, "submit"); news.transition(reviewer, id, 2, "publish");
        assertTrue(news.canViewLog(Viewer.ANONYMOUS, target));
        news.transition(reviewer, id, 3, "withdraw");
        assertFalse(news.canViewLog(Viewer.ANONYMOUS, target));
        assertTrue(news.canViewLog(reviewer, target));
    }

    @Test void listLimitControlsActualResultsWhileKeepingDefaultsAndPagination() {
        for (int i = 0; i < 9; i++) {
            long id = news.save(author, 0, 0, content("第 " + i + " 条新闻"));
            news.transition(author, id, 1, "submit"); news.transition(reviewer, id, 2, "publish");
        }
        var all = news.published(Viewer.ANONYMOUS, false, "", "", "", 0);
        assertEquals(9, all.size());
        assertEquals(6, news.published(Viewer.ANONYMOUS, true, "", "", "", 0).size());
        assertEquals(2, news.published(Viewer.ANONYMOUS, true, "", "", "", 0, 2).size());
        assertEquals(9, news.published(Viewer.ANONYMOUS, true, "", "", "", 0, 10).size());
        assertEquals(all.subList(2, 5), news.published(Viewer.ANONYMOUS, true, "", "", "", 2, 3));
        for (int invalid : List.of(0, -1, 51)) {
            assertThrows(IllegalArgumentException.class,
                    () -> news.published(Viewer.ANONYMOUS, true, "", "", "", 0, invalid));
        }
    }

    @Test
    void generatedNewsIsDeduplicatedAndOnlyEntersPendingReview() throws Exception {
        when(ai.configured("news")).thenReturn(true);
        when(ai.allowed(eq("news"), any())).thenReturn(true);
        when(ai.model("news")).thenReturn("fake-model");
        when(ai.complete(eq("news"), any(), anyString(), anyString())).thenReturn(
                "{\"title\":\"定档消息\",\"summary\":\"来自公告\",\"body\":\"活动定档\",\"category\":\"定档\",\"region\":\"江苏省\",\"sourceName\":\"主办方\"}");
        UserStore users = mock(UserStore.class);
        when(users.byId(1)).thenReturn(Optional.of(new UserStore.UserRef(1, "投稿者", "", true)));
        when(users.groups(1)).thenReturn(author.groups());
        try (NewsJobs jobs = new NewsJobs(db, news, users, new ObjectMapper())) {
            long id = jobs.enqueue(author, "主办方提供的活动公告", "https://example.com/notice");
            assertEquals(id, jobs.enqueue(author, "主办方提供的活动公告", "https://example.com/notice"));
            jobs.start();
            long deadline = System.nanoTime() + 12_000_000_000L;
            while (System.nanoTime() < deadline && !jobs.status(author, id).get("state").equals("COMPLETED")) Thread.sleep(100);
            var result = jobs.status(author, id);
            assertEquals("COMPLETED", result.get("state"), result.toString());
            long newsId = (long) result.get("newsId");
            assertEquals("PENDING", news.draft(author, newsId).get("status"));
            assertTrue(((NewsContent) news.draft(author, newsId).get("content")).aiAssisted());
            assertTrue(news.published(Viewer.ANONYMOUS, false, "", "", "", 0).isEmpty());
        }
    }
}
