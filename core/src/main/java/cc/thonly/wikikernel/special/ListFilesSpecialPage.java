package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiFileVersion;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.UserService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code 特殊:文件列表} / {@code Special:ListFiles}：仿 MediaWiki 的上传文件列表（TablePager 风格）。
 * <p>
 * 表格列：日期 / 名称 / 缩略图 / 大小 / 用户 / 描述；点表头按该列排序
 * （{@code sort=date|name|size} + {@code dir=asc|desc}，默认日期倒序）；
 * 类型过滤（{@code kind=all|image|audio|video|other}）；每 {@link #PAGE_SIZE} 条一页
 * （{@code offset} 偏移分页）。无权限查看的文件不出现（名称也不泄露）。
 */
@Component
public class ListFilesSpecialPage implements SpecialPage {

    /** 每页显示条数（翻页块大小）。 */
    private static final int PAGE_SIZE = 50;

    private static final String SORT_DATE = "date";
    private static final String SORT_NAME = "name";
    private static final String SORT_SIZE = "size";
    private static final String KIND_ALL = "all";

    private final FileService fileService;
    private final AccessControlService accessControlService;
    private final UserService userService;
    private final FileViewMapper fileViewMapper;
    private final MessageSource messageSource;

    public ListFilesSpecialPage(FileService fileService,
                                AccessControlService accessControlService,
                                UserService userService,
                                FileViewMapper fileViewMapper,
                                MessageSource messageSource) {
        this.fileService = fileService;
        this.accessControlService = accessControlService;
        this.userService = userService;
        this.fileViewMapper = fileViewMapper;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "文件列表";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.fileList", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("ListFiles", "FileList", "Files");
    }

    @Override
    public String category() {
        return SpecialPageCategories.MEDIA;
    }

    @Override
    public String template() {
        return "special/list-files";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        String sort = normalizeSort(request.parameters().get("sort"));
        String dir = normalizeDir(sort, request.parameters().get("dir"));
        String kind = normalizeKind(request.parameters().get("kind"));
        int offset = parseOffset(request.parameters().get("offset"));
        // 线上（MediaWiki TablePager）的参数：limit / user / ilshowall
        int limit = normalizeLimit(request.parameters().get("limit"));
        String userFilter = Strings.trimToEmpty(request.parameters().get("user")).trim();
        boolean showAll = isTruthy(request.parameters().get("ilshowall"));

        List<WikiFile> readable = accessControlService.filterReadable(
                fileService.all(), file -> "文件:" + file.getName(), user);

        // 类型过滤（内部参数，线上表单里没有；保留兼容）
        List<WikiFile> filtered = new ArrayList<>();
        for (WikiFile file : readable) {
            if (KIND_ALL.equals(kind) || kind.equals(fileService.mediaKind(file))) {
                filtered.add(file);
            }
        }

        // 排序：主键按列，之后一律按名称（不区分大小写）保证稳定
        boolean ascending = "asc".equals(dir);
        Comparator<WikiFile> byName = Comparator.comparing(file -> normalize(file.getName()));
        Comparator<WikiFile> comparator = switch (sort) {
            case SORT_NAME -> byName;
            case SORT_SIZE -> Comparator.comparing(WikiFile::getSize,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(WikiFile::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (!ascending) {
            comparator = comparator.reversed();
        }
        if (!SORT_NAME.equals(sort)) {
            comparator = comparator.thenComparing(byName);
        }
        filtered.sort(comparator);

        // 按上传者过滤（线上「用户名：」输入框）
        List<WikiFile> byUploader = filtered;
        if (!userFilter.isEmpty()) {
            Map<Long, String> allNames = userService.usernames(filtered.stream()
                    .map(WikiFile::getUploaderId).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
            byUploader = new ArrayList<>();
            for (WikiFile file : filtered) {
                String uploader = file.getUploaderId() == null ? null : allNames.get(file.getUploaderId());
                if (uploader != null && uploader.equalsIgnoreCase(userFilter)) {
                    byUploader.add(file);
                }
            }
        }

        // 偏移分页（超界的 offset 回退到最后一页）
        int total = byUploader.size();
        if (offset > total && total > 0) {
            offset = Math.max(0, ((total - 1) / limit) * limit);
        }
        int start = Math.min(offset, total);
        int end = Math.min(start + limit, total);
        List<WikiFile> page = byUploader.subList(start, end);

        Map<Long, String> names = userService.usernames(fileViewMapper.uploaderIds(page));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiFile file : page) {
            Map<String, Object> row = fileViewMapper.row(file, names);
            // 线上「版本」列：该文件一共有多少个版本（当前版本号就是累计数）
            row.put("versionCount", file.getVersion() == null ? 1 : file.getVersion());
            rows.add(row);
            // 「包括文件的旧版本」：把历史版本也各列一行（同名列，时间/大小取旧版本）
            if (showAll) {
                List<WikiFileVersion> versions = fileService.versions(file);
                Map<Long, String> versionNames = userService.usernames(fileViewMapper.versionUploaderIds(versions));
                for (WikiFileVersion version : versions) {
                    Map<String, Object> versionRow = fileViewMapper.versionRow(version, versionNames);
                    versionRow.put("name", file.getName());
                    versionRow.put("pageUrl", fileService.pageUrlOf(file));
                    versionRow.put("image", fileService.isImage(file));
                    versionRow.put("url", fileService.urlOf(file));
                    versionRow.put("directUrl", fileService.urlOf(version));
                    versionRow.put("sizeText", fileViewMapper.formatSize(version.getSize()));
                    versionRow.put("description", file.getDescription());
                    versionRow.put("versionCount", file.getVersion() == null ? 1 : file.getVersion());
                    rows.add(versionRow);
                }
            }
        }

        // 每行的「原始文件」直链（名称列的「文件」链接，线上指 /images/…）
        for (Map<String, Object> row : rows) {
            row.putIfAbsent("directUrl", row.get("url"));
        }

        model.put("files", rows);
        model.put("total", total);
        model.put("rangeStart", total == 0 ? 0 : start + 1);
        model.put("rangeEnd", end);
        model.put("sortValue", sort);
        model.put("dirValue", dir);
        // 表头排序链接：当前列 = 反向；其它列 = 该列默认方向（日期/大小倒序、名称升序）
        model.put("dateDir", toggleDir(sort, dir, SORT_DATE));
        model.put("nameDir", toggleDir(sort, dir, SORT_NAME));
        model.put("sizeDir", toggleDir(sort, dir, SORT_SIZE));
        model.put("kindValue", kind);
        model.put("kindOptions", kindOptions(kind));
        // 线上表单字段
        model.put("limitValue", limit);
        model.put("limitOptions", LIMIT_OPTIONS);
        model.put("userFilter", userFilter);
        model.put("showAll", showAll);
        // 分页（线上 TablePager：首页 / 上一页 / 下一页 / 末页）
        model.put("prevOffset", start > 0 ? Math.max(0, start - limit) : null);
        model.put("nextOffset", end < total ? end : null);
        model.put("firstOffset", start > 0 ? 0 : null);
        model.put("lastOffset", end < total ? Math.max(0, ((total - 1) / limit) * limit) : null);
        model.put("canUpload", accessControlService.can(AccessAction.UPLOAD, "文件:*", user));
        model.put("canViewDeleted", accessControlService.can(AccessAction.DELETE, "文件:*", user));
    }

    /** 线上「每页项数」下拉的取值。 */
    private static final List<Integer> LIMIT_OPTIONS = List.of(20, 50, 100, 250, 500);

    /** 每页项数：只接受线上那几个值，其它一律回退 50。 */
    private static int normalizeLimit(String raw) {
        try {
            int value = Integer.parseInt(Strings.trimToEmpty(raw).trim());
            return LIMIT_OPTIONS.contains(value) ? value : PAGE_SIZE;
        } catch (NumberFormatException exception) {
            return PAGE_SIZE;
        }
    }

    /** 复选框参数（{@code 1/true/yes/on} 都算勾上）。 */
    private static boolean isTruthy(String raw) {
        String value = Strings.trimToEmpty(raw).trim().toLowerCase(Locale.ROOT);
        return "1".equals(value) || "true".equals(value) || "yes".equals(value) || "on".equals(value);
    }

    /** 点某一列表头时的目标方向：当前排序列 = 反向；否则该列默认方向（日期/大小倒序、名称升序）。 */
    private static String toggleDir(String sort, String dir, String column) {
        if (column.equals(sort)) {
            return "asc".equals(dir) ? "desc" : "asc";
        }
        return SORT_NAME.equals(column) ? "asc" : "desc";
    }

    private static String normalizeSort(String raw) {
        String sort = Strings.trimToEmpty(raw).trim().toLowerCase(Locale.ROOT);
        return switch (sort) {
            case SORT_NAME, SORT_SIZE -> sort;
            default -> SORT_DATE;
        };
    }

    /** 非法方向回退到该列默认：日期/大小倒序、名称升序。 */
    private static String normalizeDir(String sort, String raw) {
        String dir = Strings.trimToEmpty(raw).trim().toLowerCase(Locale.ROOT);
        if ("asc".equals(dir) || "desc".equals(dir)) {
            return dir;
        }
        return SORT_NAME.equals(sort) ? "asc" : "desc";
    }

    /** 允许的类型过滤值：all / image / audio / video / other（非法值回退 all）。 */
    private static String normalizeKind(String raw) {
        String kind = Strings.trimToEmpty(raw).trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "image", "audio", "video", "other" -> kind;
            default -> KIND_ALL;
        };
    }

    private static int parseOffset(String raw) {
        try {
            return Math.max(0, Integer.parseInt(Strings.trimToEmpty(raw).trim()));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private List<Map<String, Object>> kindOptions(String selected) {
        return List.of(
                option(KIND_ALL, message("listFiles.kindAll"), KIND_ALL.equals(selected)),
                option("image", message("listFiles.kindImage"), "image".equals(selected)),
                option("audio", message("listFiles.kindAudio"), "audio".equals(selected)),
                option("video", message("listFiles.kindVideo"), "video".equals(selected)),
                option("other", message("listFiles.kindOther"), "other".equals(selected)));
    }

    private static Map<String, Object> option(String id, String label, boolean selected) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("label", label);
        option.put("selected", selected);
        return option;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
