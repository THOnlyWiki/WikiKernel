package cc.thonly.wikikernel.offline;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一次「导出离线」任务。任务在后台线程运行，前端轮询 {@code /api/offline-export/{id}}。
 */
public final class OfflineExportJob {

    public enum State {
        QUEUED, RUNNING, DONE, FAILED
    }

    private final String id;
    private final long ownerId;
    private final Instant createdAt = Instant.now();

    private volatile State state = State.QUEUED;
    private volatile int pagesDone;
    private volatile int totalPages;
    private volatile int assets;
    private volatile String message = "等待开始";
    private volatile Path archive;
    private volatile Instant finishedAt;

    public OfflineExportJob(String id, long ownerId) {
        this.id = id;
        this.ownerId = ownerId;
    }

    public String id() {
        return id;
    }

    public long ownerId() {
        return ownerId;
    }

    public State state() {
        return state;
    }

    public void state(State state) {
        this.state = state;
    }

    public int pagesDone() {
        return pagesDone;
    }

    public void pagesDone(int pagesDone) {
        this.pagesDone = Math.max(0, pagesDone);
    }

    public int totalPages() {
        return totalPages;
    }

    public void totalPages(int totalPages) {
        this.totalPages = Math.max(0, totalPages);
    }

    public int assets() {
        return assets;
    }

    public void assets(int assets) {
        this.assets = Math.max(0, assets);
    }

    public String message() {
        return message;
    }

    public void message(String message) {
        this.message = message == null ? "" : message;
    }

    public Path archive() {
        return archive;
    }

    public void archive(Path archive) {
        this.archive = archive;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public void finish(State state, String message) {
        this.finishedAt = Instant.now();
        this.state = state;
        this.message = message == null ? "" : message;
    }

    public Map<String, Object> view() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("state", state.name().toLowerCase(Locale.ROOT));
        view.put("pagesDone", pagesDone);
        view.put("totalPages", totalPages);
        view.put("assets", assets);
        view.put("message", message);
        view.put("createdAt", createdAt.toString());
        view.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        if (state == State.DONE) {
            view.put("downloadUrl", "/api/offline-export/" + id + "/download");
        }
        return view;
    }
}
