package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.TransferProperties;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiImportService;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.transfer.ImportFormat;
import cc.thonly.wikikernel.transfer.ImportResult;
import cc.thonly.wikikernel.transfer.ImportedPage;
import cc.thonly.wikikernel.transfer.ImportedRevision;
import cc.thonly.wikikernel.transfer.MediaWikiXmlImporter;
import cc.thonly.wikikernel.transfer.WikiKernelJsonImporter;
import cc.thonly.wikikernel.transfer.WikiKernelXmlImporter;
import cc.thonly.wikikernel.transfer.WikiSha1;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.web.WikiTitles;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import javax.xml.stream.XMLStreamException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 导入实现：
 * <ul>
 *     <li>格式自动识别（MediaWiki XML / WikiKernel XML / WikiKernel JSON）；</li>
 *     <li>页面不存在则新建（WikiKernel 格式保留保护 / 隐藏 / 删除状态）；已存在则追加缺失版本；</li>
 *     <li>去重键 = 正文 sha1 + 时间（精确到秒）：重复导入同一文件不会产生重复版本；</li>
 *     <li>版本时间沿用文件里的时间；贡献者按用户名关联本地账号，找不到则匿名；</li>
 *     <li>逐页写导入日志，并触发分类 / 模板索引重建事件。</li>
 * </ul>
 */
@Service
public class WikiImportServiceImpl implements WikiImportService {

    /** 单个页面对应的错误信息最多收集这么多条，避免响应过大。 */
    private static final int MAX_ERRORS = 50;

    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final UserService userService;
    private final LogService logService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransferProperties properties;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    public WikiImportServiceImpl(WikiPageMapper wikiPageMapper,
                                 WikiRevisionMapper wikiRevisionMapper,
                                 UserService userService,
                                 LogService logService,
                                 ApplicationEventPublisher eventPublisher,
                                 TransferProperties properties,
                                 ObjectMapper objectMapper,
                                 MessageSource messageSource) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.userService = userService;
        this.logService = logService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    @Override
    @Transactional
    public ImportResult importFile(byte[] bytes, String targetNamespaceId, WikiUser importer) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("import.emptyFile");
        }
        ImportFormat format = ImportFormat.detect(bytes);
        if (format == ImportFormat.UNKNOWN) {
            throw new IllegalArgumentException("import.unknownFormat");
        }
        List<ImportedPage> pages;
        try {
            pages = switch (format) {
                case MEDIAWIKI_XML -> MediaWikiXmlImporter.parse(bytes);
                case WIKIKERNEL_XML -> WikiKernelXmlImporter.parse(bytes);
                case WIKIKERNEL_JSON -> WikiKernelJsonImporter.parse(bytes, objectMapper);
                default -> List.of();
            };
        } catch (XMLStreamException | JacksonException exception) {
            throw new IllegalArgumentException("import.parseFailed");
        }
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("import.noPages");
        }

        boolean truncated = false;
        if (pages.size() > Math.max(1, properties.getImportMaxPages())) {
            pages = pages.subList(0, Math.max(1, properties.getImportMaxPages()));
            truncated = true;
        }

        int budget = Math.max(1, properties.getImportMaxRevisions());
        int pagesCreated = 0;
        int pagesUpdated = 0;
        int pagesSkipped = 0;
        int revisionsImported = 0;
        int revisionsSkipped = 0;
        List<String> errors = new ArrayList<>();

        for (ImportedPage page : pages) {
            if (budget <= 0) {
                truncated = true;
                break;
            }
            try {
                ApplyResult result = applyPage(page, targetNamespaceId, importer, budget);
                if (result.limitHit()) {
                    truncated = true;
                }
                budget -= result.imported();
                revisionsImported += result.imported();
                revisionsSkipped += result.skipped();
                if (result.imported() == 0) {
                    pagesSkipped++;
                } else if (result.created()) {
                    pagesCreated++;
                } else {
                    pagesUpdated++;
                }
            } catch (Exception exception) {
                if (errors.size() < MAX_ERRORS) {
                    errors.add(page.title() + ": " + exception.getMessage());
                }
            }
        }

        return new ImportResult(pagesCreated, pagesUpdated, pagesSkipped,
                revisionsImported, revisionsSkipped, List.copyOf(errors), truncated);
    }

    /** 应用单个页面，返回本页统计。 */
    private ApplyResult applyPage(ImportedPage page, String targetNamespaceId, WikiUser importer, int budget) {
        String title = applyNamespace(page.title(), targetNamespaceId);
        if (title.isBlank()) {
            throw new IllegalArgumentException(message("import.error.emptyTitle"));
        }
        // 非法标题不导入（仿 MediaWiki：跳过标题无效的页面并在结果里报告）
        WikiTitles.validate(title).ifPresent(error -> {
            throw new IllegalArgumentException(message(error.messageKey(), error.detail()));
        });
        // 导入的标题同样走规范化（首字母大写 / 下划线折叠，与读写路径一致）
        title = WikiTitles.normalize(title);
        WikiPage existing = wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, title)
                .eq(WikiPage::getDeleted, false)
                .last("LIMIT 1"));
        boolean created = existing == null;
        LocalDateTime now = LocalDateTime.now();
        if (created) {
            // WikiKernel 格式携带的状态在新建时保留；MediaWiki 格式没有这些字段（全 false）
            existing = new WikiPage(null, title, null,
                    page.hided(), page.deleted(), page.protectedPage(), now, now);
            wikiPageMapper.insert(existing);
        }

        Set<String> fingerprints = new HashSet<>();
        for (WikiRevision revision : wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getPageId, existing.getId()))) {
            fingerprints.add(fingerprint(revision.getContent(), revision.getCreateAt()));
        }

        List<ImportedRevision> ordered = new ArrayList<>(page.revisions());
        ordered.sort(Comparator.comparing(revision ->
                revision.time() == null ? LocalDateTime.MAX : revision.time()));

        int imported = 0;
        int skipped = 0;
        boolean limitHit = false;
        for (ImportedRevision source : ordered) {
            if (imported >= budget) {
                limitHit = true;
                break;
            }
            String content = source.content() == null ? "" : source.content();
            String fingerprint = fingerprint(content, source.time());
            if (fingerprints.contains(fingerprint)) {
                skipped++;
                continue;
            }
            WikiRevision revision = new WikiRevision();
            revision.setPageId(existing.getId());
            revision.setContent(content);
            revision.setUserId(resolveUserId(source));
            revision.setComment(source.comment() == null ? "" : source.comment().trim());
            revision.setBot(source.bot());
            revision.setTags(source.tags() == null ? "" : source.tags().trim());
            revision.setDeleted(source.deleted());
            revision.setCreateAt(source.time() == null ? now : source.time());
            wikiRevisionMapper.insert(revision);
            fingerprints.add(fingerprint);
            imported++;
        }

        if (imported == 0) {
            return new ApplyResult(created, imported, skipped, limitHit);
        }

        // 最新版本 = 全部版本里时间最晚（同时间取 ID 大的）；新建页面的创建时间取最早版本时间
        List<WikiRevision> all = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getPageId, existing.getId()));
        WikiRevision latest = all.stream()
                .max(Comparator.comparing(WikiRevision::getCreateAt).thenComparing(WikiRevision::getId))
                .orElse(null);
        if (latest != null) {
            existing.setLatestRevisionId(latest.getId());
        }
        LocalDateTime last = latest == null ? now : latest.getCreateAt();
        if (created) {
            LocalDateTime earliest = all.stream()
                    .map(WikiRevision::getCreateAt)
                    .min(Comparator.naturalOrder())
                    .orElse(now);
            existing.setCreateAt(earliest);
        }
        if (existing.getUpdateAt() == null || last.isAfter(existing.getUpdateAt())) {
            existing.setUpdateAt(last);
        }
        wikiPageMapper.updateById(existing);

        logService.record(LogTypeConfig.IMPORT, importer == null ? null : importer.getId(),
                title, null, String.valueOf(imported));
        eventPublisher.publishEvent(new WikiPageChangedEvent(existing.getId(), title,
                Boolean.TRUE.equals(existing.getDeleted())));
        return new ApplyResult(created, imported, skipped, limitHit);
    }

    /** 目标命名空间：空 / 未知 = 保持原样；{@code main} = 去掉原命名空间前缀；其它 = 换到该命名空间。 */
    private static String applyNamespace(String title, String targetNamespaceId) {
        String value = Strings.trimToEmpty(title);
        Optional<WikiNamespaces.Namespace> target = WikiNamespaces.byId(targetNamespaceId);
        if (target.isEmpty()) {
            return value;
        }
        String bare = stripNamespace(value);
        WikiNamespaces.Namespace namespace = target.get();
        if (WikiNamespaces.MAIN.equals(namespace)) {
            return bare;
        }
        return namespace.prefixes().get(0) + bare;
    }

    /** 去掉标题上任意已知命名空间前缀。 */
    private static String stripNamespace(String title) {
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            for (String prefix : namespace.prefixes()) {
                if (title.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return title.substring(prefix.length()).trim();
                }
            }
        }
        return title;
    }

    /** 贡献者：按用户名关联本地账号（先精确、再首字母大写重试）；找不到 = 匿名。 */
    private Long resolveUserId(ImportedRevision source) {
        String username = source.username();
        if (username == null || username.isBlank()) {
            return null;
        }
        Optional<WikiUser> user = userService.findByUsername(username.trim());
        if (user.isEmpty() && username.length() > 1) {
            String capitalized = Character.toUpperCase(username.charAt(0)) + username.substring(1);
            if (!capitalized.equals(username)) {
                user = userService.findByUsername(capitalized);
            }
        }
        return user.map(WikiUser::getId).orElse(null);
    }

    /**
     * 去重键：正文 sha1 + 时间（精确到秒；无时间只用 sha1）。
     * 换行先归一化为 {@code \n}：XML 解析器按规范会把 CRLF / CR 归一化，
     * 不归一化的话「导出 XML → 导入」的正文 sha1 会和本地存储的不一致，导致重复导入。
     */
    private static String fingerprint(String content, LocalDateTime time) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
        String stamp = time == null ? "" : time.truncatedTo(ChronoUnit.SECONDS).toString();
        return WikiSha1.base36(normalized) + "@" + stamp;
    }

    /** 单页应用结果。 */
    private record ApplyResult(boolean created, int imported, int skipped, boolean limitHit) {
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
