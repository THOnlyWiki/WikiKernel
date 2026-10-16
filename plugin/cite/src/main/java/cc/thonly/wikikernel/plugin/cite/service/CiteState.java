package cc.thonly.wikikernel.plugin.cite.service;

import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.WikiText;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次渲染里收集到的全部 {@code <ref>}（Cite 扩展的核心状态）：
 * 编号按**首次出现**、同名的引用合并成一条、先使用后定义也支持、错误内联提示。
 * <p>
 * 状态挂在渲染上下文（{@code WikiRenderContext.store()}）里，渲染结束即丢；
 * 标记 / 列表用 {@code deferHtml} 延迟到「整页 HTML 收尾」再生成（那时才收得齐所有引用）。
 */
public class CiteState {

    /** 渲染变量键 / 页面属性键（页面属性用于 head 注入 cite.css）。 */
    public static final String STORE_KEY = "cite-state";
    public static final String USED_PROPERTY = "cite-used";

    private static final Pattern SINGLE_PARAGRAPH = Pattern.compile("(?s)^\\s*<p>(.*)</p>\\s*$");

    private final List<Event> events = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();
    private final Map<String, Note> byName = new LinkedHashMap<>();
    private boolean resolved;
    private boolean listTaken;

    /** 一次 {@code <ref>} 的出现（定义或复用）。 */
    private static final class Event {
        private final String name;
        private final boolean selfClosing;
        private final String content;
        private Note note;
        private String errorKey;
        private String errorArg;

        private Event(String name, boolean selfClosing, String content) {
            this.name = name;
            this.selfClosing = selfClosing;
            this.content = content;
        }
    }

    /** 一条脚注（同名合并；number 按首次使用赋值）。 */
    private static final class Note {
        private final String content;
        private int number;
        private final List<Event> uses = new ArrayList<>();

        private Note(String content) {
            this.content = content;
        }
    }

    public static CiteState of(WikiRenderContext context) {
        Object existing = context.store().get(STORE_KEY);
        if (existing instanceof CiteState state) {
            return state;
        }
        CiteState state = new CiteState();
        context.store().put(STORE_KEY, state);
        return state;
    }

    /** 登记一次引用，返回序号（用于延迟生成标记）。 */
    public int addRef(String name, String content, boolean empty) {
        events.add(new Event(name, empty, empty ? null : content));
        return events.size() - 1;
    }

    /** {@code <references/>}：只有第一个位置出列表，其余清掉（与迁移前 core 行为一致）。 */
    public boolean takeList() {
        if (listTaken) {
            return false;
        }
        listTaken = true;
        return true;
    }

    /** 上标标记（或错误提示）；{@code message(key, arg)} 取本地化文案。 */
    public String markerHtml(int index, BiFunction<String, String, String> message) {
        resolve();
        Event event = events.get(index);
        if (event.errorKey != null) {
            return "<span class=\"error mw-cite-error\">" + HtmlUtils.htmlEscape(message.apply(event.errorKey, event.errorArg))
                    + "</span>";
        }
        Note note = event.note;
        int seen = note.uses.indexOf(event) + 1;
        String id = seen == 1 ? "cite_ref-" + note.number : "cite_ref-" + note.number + "-" + seen;
        return "<sup class=\"reference\" id=\"" + id + "\"><a href=\"#cite_note-"
                + note.number + "\">[" + note.number + "]</a></sup>";
    }

    /** 脚注列表 {@code <ol class="references">…</ol>}；条目正文按 wikitext 渲染（{@link WikiText}）。 */
    public String listHtml(String pageTitle, WikiText wikiText) {
        resolve();
        if (notes.isEmpty()) {
            return "";
        }
        List<Note> ordered = new ArrayList<>(notes);
        ordered.sort((left, right) -> Integer.compare(left.number, right.number));
        StringBuilder out = new StringBuilder("<ol class=\"references\">");
        for (Note note : ordered) {
            out.append("<li id=\"cite_note-").append(note.number).append("\">");
            out.append("<span class=\"mw-cite-backlink\">");
            for (int index = 0; index < note.uses.size(); index++) {
                if (index > 0) {
                    out.append(' ');
                }
                String id = index == 0
                        ? "cite_ref-" + note.number
                        : "cite_ref-" + note.number + "-" + (index + 1);
                out.append("<a href=\"#").append(id).append("\">↑</a>");
            }
            out.append("</span> <span class=\"reference-text\">");
            out.append(renderContent(note.content, pageTitle, wikiText));
            out.append("</span></li>");
        }
        return out.append("</ol>").toString();
    }

    /** 脚注正文：按 wikitext 渲染；整段被包成单个 {@code <p>} 时去掉外层（保持行内观感）。 */
    private static String renderContent(String content, String pageTitle, WikiText wikiText) {
        String text = content == null ? "" : content.replaceAll("[\\r\\n]+", " ").trim();
        if (text.isEmpty()) {
            return "";
        }
        if (wikiText == null) {
            return HtmlUtils.htmlEscape(text);
        }
        String html = wikiText.toHtml(text, pageTitle);
        Matcher single = SINGLE_PARAGRAPH.matcher(html == null ? "" : html.trim());
        return single.matches() ? single.group(1) : html;
    }

    /** 结算：先收集定义（支持先使用后定义），再按出现顺序编号。 */
    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        for (Event event : events) {
            if (event.selfClosing || event.content == null) {
                continue;
            }
            if (event.content.trim().isEmpty()) {
                event.errorKey = "cite.error.empty";
                continue;
            }
            if (event.name == null || event.name.isBlank()) {
                Note note = new Note(event.content);
                notes.add(note);
                event.note = note;
                continue;
            }
            Note existing = byName.get(event.name);
            if (existing == null) {
                Note note = new Note(event.content);
                notes.add(note);
                byName.put(event.name, note);
                event.note = note;
            } else if (existing.content.equals(event.content)) {
                event.note = existing;
            } else {
                event.errorKey = "cite.error.duplicate";
                event.errorArg = event.name;
            }
        }
        int counter = 0;
        for (Event event : events) {
            if (event.errorKey != null) {
                continue;
            }
            if (event.note == null) {
                if (event.name == null || event.name.isBlank()) {
                    event.errorKey = "cite.error.unnamedUse";
                    continue;
                }
                Note note = byName.get(event.name);
                if (note == null) {
                    event.errorKey = "cite.error.undefined";
                    event.errorArg = event.name;
                    continue;
                }
                event.note = note;
            }
            event.note.uses.add(event);
            if (event.note.number == 0) {
                event.note.number = ++counter;
            }
        }
    }
}
