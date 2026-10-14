package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiInterwiki;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.InterwikiService;
import cc.thonly.wikikernel.service.PermissionService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:跨Wiki} / {@code Special:Interwiki}（仿 MediaWiki 的 Interwiki 功能）：
 * 维护「前缀 → 另一个 wiki 的 URL 模板」，决定 {@code [[前缀:页面]]} 的去向。
 * <ul>
 *     <li>普通前缀 → 正文里的外部链接（{@code [[w:页面]]}、{@code [[commons:文件]]}）；</li>
 *     <li>语言前缀（有语言码）→ 跨语言链接：不出现在正文，收集到页面侧栏「语言」区块；</li>
 *     <li>本地前缀 → 本站内部链接（{@code /wiki/前缀:页面}）；</li>
 *     <li>可嵌入（{@code iw_trans}）目前只存储，尚未实现远端模板嵌入。</li>
 * </ul>
 * 表格所有人都能看，增删改只有管理员 / 行政员（表单由 {@code /js/interwiki.js} 提交到 {@code /api/interwiki/*}）。
 */
@Component
public class InterwikiSpecialPage implements SpecialPage {

    private final InterwikiService interwikiService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public InterwikiSpecialPage(InterwikiService interwikiService,
                                PermissionService permissionService,
                                MessageSource messageSource) {
        this.interwikiService = interwikiService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "跨Wiki";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.interwiki", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Interwiki");
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String template() {
        return "special/interwiki";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        model.put("canManage", user != null && permissionService.canManage(user.getId()));
        model.put("rows", rows());
        model.put("exportText", interwikiService.exportText());
        model.put("saved", request.parameters().containsKey("saved"));
        model.put("deleted", request.parameters().containsKey("deleted"));

        // ?edit=<id>：把该行预填到表单里
        Long editId = parseId(request.parameters().get("edit"));
        Map<String, String> editing = null;
        if (editId != null) {
            editing = rows().stream()
                    .filter(row -> row.get("id").equals(String.valueOf(editId)))
                    .findFirst()
                    .orElse(null);
        }
        model.put("editing", editing);
        model.put("editingId", editing == null ? "" : editing.get("id"));
        model.put("formPrefix", editing == null ? "" : editing.get("prefix"));
        model.put("formUrl", editing == null ? "" : editing.get("url"));
        model.put("formLanguage", editing == null ? "" : editing.get("language"));
        model.put("formLocal", editing != null && "true".equals(editing.get("local")));
        model.put("formTranscludable", editing != null && "true".equals(editing.get("transcludable")));
    }

    /** 表格行（前缀 / URL / 语言名 / 标志）。 */
    private List<Map<String, String>> rows() {
        List<Map<String, String>> rows = new ArrayList<>();
        for (WikiInterwiki row : interwikiService.all()) {
            Map<String, String> view = new LinkedHashMap<>();
            view.put("id", String.valueOf(row.getId()));
            view.put("prefix", row.getPrefix());
            view.put("url", row.getUrl());
            view.put("language", Strings.trimToEmpty(row.getLanguage()));
            view.put("languageName", row.getLanguage() == null || row.getLanguage().isBlank()
                    ? "" : interwikiService.languageName(row.getLanguage()));
            view.put("local", String.valueOf(Boolean.TRUE.equals(row.getLocal())));
            view.put("transcludable", String.valueOf(Boolean.TRUE.equals(row.getTranscludable())));
            rows.add(view);
        }
        return rows;
    }

    private static Long parseId(String value) {
        try {
            return Strings.isBlank(value) ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
